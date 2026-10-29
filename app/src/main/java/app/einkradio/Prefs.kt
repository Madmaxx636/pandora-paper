package app.einkradio

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** UI settings (observable from Compose) plus the saved login. */
object Prefs {
    val TEXT_SCALES = floatArrayOf(1.0f, 1.25f, 1.5f)
    val PROGRESS_LABELS = arrayOf("Hidden", "Every 15 s", "Every 5 s")

    private lateinit var settings: SharedPreferences
    private lateinit var secure: SharedPreferences

    var eink by mutableStateOf(true)
        private set
    var pagedLists by mutableStateOf(true)
        private set
    var showArt by mutableStateOf(true)
        private set
    var textScaleIndex by mutableIntStateOf(1)
        private set
    var progressMode by mutableIntStateOf(1)
        private set

    val textScale: Float get() = TEXT_SCALES[textScaleIndex]

    fun init(context: Context) {
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        secure = EncryptedSharedPreferences.create(
            context, "login", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        eink = settings.getBoolean("eink", true)
        pagedLists = settings.getBoolean("paged", true)
        showArt = settings.getBoolean("art", true)
        textScaleIndex = settings.getInt("text_scale", 1).coerceIn(TEXT_SCALES.indices)
        progressMode = settings.getInt("progress", 1).coerceIn(PROGRESS_LABELS.indices)
    }

    fun toggleEink() { eink = !eink; settings.edit().putBoolean("eink", eink).apply() }
    fun togglePaged() { pagedLists = !pagedLists; settings.edit().putBoolean("paged", pagedLists).apply() }
    fun toggleArt() { showArt = !showArt; settings.edit().putBoolean("art", showArt).apply() }

    fun cycleTextScale() {
        textScaleIndex = (textScaleIndex + 1) % TEXT_SCALES.size
        settings.edit().putInt("text_scale", textScaleIndex).apply()
    }

    fun cycleProgress() {
        progressMode = (progressMode + 1) % PROGRESS_LABELS.size
        settings.edit().putInt("progress", progressMode).apply()
    }

    fun savedLogin(): Pair<String, String>? {
        val user = secure.getString("user", null) ?: return null
        val pass = secure.getString("pass", null) ?: return null
        return user to pass
    }

    fun saveLogin(user: String, pass: String) {
        secure.edit().putString("user", user).putString("pass", pass).apply()
    }

    fun clearLogin() {
        secure.edit().clear().apply()
    }
}
