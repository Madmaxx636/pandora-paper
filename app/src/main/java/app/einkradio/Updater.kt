package app.einkradio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Checks this app's GitHub releases for a newer version and installs it.
 *
 * Android doesn't let an ordinary app install updates silently, so "auto update" means checking on its
 * own and installing with one tap; Android still asks you to confirm. Before the installer opens, the
 * download is checked to be this app, newer, and signed with the same key as the installed copy.
 */
object Updater {
    private const val REPO = "Madmaxx636/pandora-paper"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val USER_AGENT = "PandoraPaper-Updater"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    data class Release(
        val tag: String,
        val version: String,
        val apkUrl: String,
        val size: Long,
        val notes: String,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val percent: Int) : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    /** The version the user chose "Later" for, so the banner stays away until a newer one appears. */
    val dismissed = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    fun dismiss(version: String) {
        dismissed.value = version
    }

    /**
     * Looks for a newer release. With [auto] set it only runs if checking is switched on, at most once a
     * day, and never on a metered connection; a manual check always runs and reports errors.
     */
    fun check(context: Context, auto: Boolean = false) {
        val current = state.value
        if (current is State.Checking || current is State.Downloading) return
        if (auto) {
            if (!Prefs.autoUpdate) return
            if (System.currentTimeMillis() - Prefs.lastUpdateCheck < CHECK_INTERVAL_MS) return
            if (Radio.isMetered()) return
        }
        val app = context.applicationContext
        scope.launch {
            state.value = State.Checking
            try {
                val release = withContext(Dispatchers.IO) { fetchLatest() }
                Prefs.saveUpdateCheck(System.currentTimeMillis())
                state.value =
                    if (release != null && isNewer(release.version, installedVersion(app))) State.Available(release)
                    else State.UpToDate
            } catch (e: Exception) {
                state.value = if (auto) State.Idle else State.Failed(e.message ?: "Couldn't check for updates")
            }
        }
    }

    /** Downloads the available release, verifies it, and opens Android's installer. */
    fun install(context: Context) {
        val release = (state.value as? State.Available)?.release ?: return
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            // Android needs a one-time "install unknown apps" allowance for this app.
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            state.value = State.Failed("Allow installs from this app in the screen that just opened, then tap Install again.")
            return
        }
        scope.launch {
            state.value = State.Downloading(0)
            try {
                val file = withContext(Dispatchers.IO) {
                    download(app, release) { state.value = State.Downloading(it) }
                }
                verify(app, file)
                state.value = State.Available(release)
                launchInstaller(app, file)
            } catch (e: Exception) {
                state.value = State.Failed(e.message ?: "The update failed")
            }
        }
    }

    private fun fetchLatest(): Release? {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        val body = http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IllegalStateException("GitHub returned ${response.code}")
            response.body?.string() ?: throw IllegalStateException("Empty response from GitHub")
        }
        val json = JSONObject(body)
        val tag = json.optString("tag_name").takeIf { it.isNotEmpty() } ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val url = asset.optString("browser_download_url")
            // Only ever download from this repository's own release files.
            if (asset.optString("name").endsWith(".apk") && url.startsWith(DOWNLOAD_PREFIX)) {
                return Release(
                    tag = tag,
                    version = tag.removePrefix("v"),
                    apkUrl = url,
                    size = asset.optLong("size"),
                    notes = json.optString("body").trim(),
                )
            }
        }
        return null
    }

    private fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, "pandora-paper-${release.version}.apk")
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("The download failed (HTTP ${response.code})")
            val body = response.body ?: throw IllegalStateException("The download was empty")
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            file.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            // Report in steps of ten so a slow screen isn't redrawn for every percent.
                            val step = (done * 10 / total).toInt().coerceIn(0, 10) * 10
                            if (step != last) {
                                last = step
                                onProgress(step)
                            }
                        }
                    }
                }
            }
        }
        if (release.size > 0 && file.length() != release.size) {
            file.delete()
            throw IllegalStateException("The download was incomplete. Try again.")
        }
        return file
    }

    /** Refuses a file that isn't this app, isn't newer, or is signed with a different key. */
    private fun verify(context: Context, file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return // Android still checks the signature itself
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.path, flags)
            ?: throw IllegalStateException("The download isn't a valid app file")
        if (archive.packageName != context.packageName) {
            throw IllegalStateException("The download is a different app")
        }
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.longVersionCode <= installed.longVersionCode) {
            throw IllegalStateException("That version isn't newer than the one installed")
        }
        if (fingerprints(archive) != fingerprints(installed)) {
            throw IllegalStateException(
                "The update is signed with a different key than the installed app, so Android won't install it " +
                    "over this one. Uninstall the app first (this clears its settings), then install the update.",
            )
        }
    }

    private fun fingerprints(info: android.content.pm.PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()

    private fun launchInstaller(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** True if [remote] is a higher dotted version than [local], comparing number by number. */
    internal fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-', '+')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
