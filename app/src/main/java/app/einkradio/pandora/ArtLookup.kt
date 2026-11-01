package app.einkradio.pandora

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Finds cover art for songs Pandora sent without any, using Apple's public iTunes Search (no key,
 * no account). Only the artist and song title are sent. A miss returns null and the caller falls
 * back to the station's art.
 */
internal object ArtLookup {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    fun find(artist: String, title: String): String? = try {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", "$artist $title")
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "5")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val results = JSONObject(response.body?.string() ?: return@use null).optJSONArray("results")
                ?: return@use null
            // Take the first hit by the same artist, so a similarly named song doesn't borrow its cover.
            (0 until results.length()).asSequence()
                .mapNotNull { results.optJSONObject(it) }
                .firstOrNull { same(it.optString("artistName"), artist) && it.optString("artworkUrl100").isNotEmpty() }
                ?.optString("artworkUrl100")
                ?.replace("100x100bb", "600x600bb")
        }
    } catch (e: Exception) {
        null
    }

    private fun same(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val x = norm(a)
        val y = norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
    }
}
