package app.einkradio.pandora

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class PandoraException(message: String, val code: Int = -1) : Exception(message)

data class Station(
    val token: String,
    val name: String,
    val isQuickMix: Boolean = false,
    val isThumbprint: Boolean = false,
    val isShared: Boolean = false,
    val artUrl: String? = null,
) {
    val isSpecial: Boolean get() = isQuickMix || isThumbprint
}

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    /** The album art URL Pandora gave for this song, if any. */
    val artUrl: String?,
    /** Art URLs to try in order: larger renditions first, then the originals, then the station's art. */
    val artUrls: List<String>,
    val audioUrl: String,
)

/**
 * Client for Pandora's unofficial JSON v5 API (see http://6xq.net/playground/pandora-apidoc/json/).
 * Blocking; call from a background dispatcher.
 */
class PandoraApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var partnerId: String? = null
    private var partnerToken: String? = null
    private var userId: String? = null
    private var userToken: String? = null
    private var timeOffset = 0L

    private var savedUser: String? = null
    private var savedPass: String? = null

    @Synchronized
    fun login(username: String, password: String) {
        savedUser = username
        savedPass = password
        partnerId = null
        partnerToken = null
        userId = null
        userToken = null

        val partner = call(
            "auth.partnerLogin",
            JSONObject()
                .put("username", PARTNER_USER)
                .put("password", PARTNER_PASS)
                .put("deviceModel", DEVICE_MODEL)
                .put("version", "5"),
            encrypted = false,
        )
        partnerId = partner.getString("partnerId")
        partnerToken = partner.getString("partnerAuthToken")

        // The decrypted sync time is 4 bytes of garbage followed by 10 digits of unix time.
        val pandoraTime = String(decrypt(partner.getString("syncTime")), Charsets.ISO_8859_1)
            .substring(4, 14).toLong()
        timeOffset = pandoraTime - System.currentTimeMillis() / 1000

        val user = call(
            "auth.userLogin",
            JSONObject()
                .put("loginType", "user")
                .put("username", username)
                .put("password", password)
                .put("returnIsSubscriber", true),
        )
        userId = user.getString("userId")
        userToken = user.getString("userAuthToken")
    }

    fun getStations(): List<Station> = authed {
        val stations = call("user.getStationList", JSONObject().put("includeStationArtUrl", true))
            .getJSONArray("stations")
        (0 until stations.length()).map {
            val s = stations.getJSONObject(it)
            val name = s.getString("stationName")
            Station(
                token = s.getString("stationToken"),
                name = name,
                isQuickMix = s.optBoolean("isQuickMix"),
                isThumbprint = s.optBoolean("isThumbprint") || name == "Thumbprint Radio",
                isShared = s.optBoolean("isShared"),
                artUrl = s.optString("artUrl").takeIf { it.isNotEmpty() },
            )
        }
    }

    fun getPlaylist(station: Station): List<Track> = authed {
        val items = call("station.getPlaylist", JSONObject().put("stationToken", station.token))
            .getJSONArray("items")
        (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            // Only entries that carry an audio stream are playable tracks.
            val urls = o.optJSONObject("audioUrlMap") ?: return@mapNotNull null
            val audio = QUALITY_ORDER.firstNotNullOfOrNull { q ->
                urls.optJSONObject(q)?.optString("audioUrl")?.takeIf { it.isNotEmpty() }
            } ?: return@mapNotNull null
            val art = artCandidates(o)
            Track(
                id = o.getString("trackToken"),
                title = o.optString("songName"),
                artist = o.optString("artistName"),
                album = o.optString("albumName"),
                artUrl = o.optString("albumArtUrl").takeIf { it.isNotEmpty() } ?: art.firstOrNull(),
                artUrls = art,
                audioUrl = audio,
            )
        }
    }

    /**
     * Pandora is inconsistent about album art: some songs only carry a small `albumArtUrl`, some also
     * list several sizes in `albumArt`, and some have neither. Collect everything so the UI can fall
     * through the list when an image fails to load.
     */
    private fun artCandidates(item: JSONObject): List<String> {
        val urls = mutableListOf<String>()
        item.optJSONArray("albumArt")?.let { sizes ->
            (0 until sizes.length())
                .mapNotNull { sizes.optJSONObject(it) }
                .sortedByDescending { it.optInt("size") }
                .forEach { e -> e.optString("url").takeIf { it.isNotEmpty() }?.let(urls::add) }
        }
        item.optString("albumArtUrl").takeIf { it.isNotEmpty() }?.let { base ->
            // The same image is served at several sizes; the large one may not exist, so keep the original.
            urls.add(base.replace(ART_SIZE, "_500W_500H"))
            urls.add(base)
        }
        return urls.distinct()
    }

    /** Returns the feedback id, which [removeRating] needs to undo the rating. */
    fun rate(station: Station, track: Track, positive: Boolean): String? = authed {
        call(
            "station.addFeedback",
            JSONObject()
                .put("stationToken", station.token)
                .put("trackToken", track.id)
                .put("isPositive", positive),
        ).optString("feedbackId").takeIf { it.isNotEmpty() }
    }

    fun removeRating(feedbackId: String) {
        authed { call("station.deleteFeedback", JSONObject().put("feedbackId", feedbackId)) }
    }

    /** Runs [block], logging in again once if the auth token has expired. */
    @Synchronized
    private fun <T> authed(block: () -> T): T = try {
        block()
    } catch (e: PandoraException) {
        val user = savedUser
        val pass = savedPass
        if (e.code != INVALID_AUTH_TOKEN || user == null || pass == null) throw e
        login(user, pass)
        block()
    }

    private fun call(
        method: String,
        args: JSONObject = JSONObject(),
        encrypted: Boolean = true,
    ): JSONObject {
        val query = buildList {
            partnerId?.let { add("partner_id=$it") }
            userId?.let { add("user_id=$it") }
            (userToken ?: partnerToken)?.let { add("auth_token=" + URLEncoder.encode(it, "UTF-8")) }
            add("method=$method")
        }.joinToString("&")

        if (partnerId != null) args.put("syncTime", System.currentTimeMillis() / 1000 + timeOffset)
        val token = userToken
        if (token != null) args.put("userAuthToken", token)
        else partnerToken?.let { args.put("partnerAuthToken", it) }

        val payload = args.toString().toByteArray(Charsets.UTF_8)
        val body = (if (encrypted) encrypt(payload) else String(payload, Charsets.UTF_8))
            .toRequestBody("text/plain".toMediaType())

        val request = Request.Builder().url("https://$HOST/services/json/?$query").post(body).build()
        val text = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw PandoraException("HTTP ${response.code}")
            response.body?.string() ?: throw PandoraException("Empty response")
        }

        val tree = JSONObject(text)
        if (tree.getString("stat") == "fail") {
            throw PandoraException(tree.optString("message", "Pandora error"), tree.optInt("code", -1))
        }
        return tree.optJSONObject("result") ?: JSONObject()
    }

    private fun cipher(key: String, mode: Int): Cipher =
        Cipher.getInstance("Blowfish/ECB/NoPadding").apply {
            init(mode, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "Blowfish"))
        }

    private fun encrypt(plain: ByteArray): String {
        val padded = plain.copyOf((plain.size + 7) / 8 * 8) // zero padded
        return cipher(ENCRYPT_KEY, Cipher.ENCRYPT_MODE).doFinal(padded)
            .joinToString("") { "%02x".format(it) }
    }

    private fun decrypt(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return cipher(DECRYPT_KEY, Cipher.DECRYPT_MODE).doFinal(bytes)
    }

    private companion object {
        const val HOST = "tuner.pandora.com"
        const val INVALID_AUTH_TOKEN = 1001

        // Public "android" partner credentials, as documented in the API write-up above.
        const val PARTNER_USER = "android"
        const val PARTNER_PASS = "AC7IBG09A3DTSYM4R41UJWL07VLN8JI7"
        const val DEVICE_MODEL = "android-generic"
        const val ENCRYPT_KEY = "6#26FRL\$ZWD"
        const val DECRYPT_KEY = "R=U!LH\$O2B#"

        val QUALITY_ORDER = listOf("highQuality", "mediumQuality", "lowQuality")
        val ART_SIZE = Regex("_\\d+W_\\d+H")
    }
}
