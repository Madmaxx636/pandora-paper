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

    const val THEME_COLOR = 0
    const val THEME_WHITE = 1 // black on white
    const val THEME_BLACK = 2 // white on black
    val THEME_LABELS = arrayOf("Color", "White", "Black")

    var theme by mutableIntStateOf(THEME_WHITE)
        private set

    /** True for either monochrome theme; this is what switches on the e-ink behaviour. */
    val eink: Boolean get() = theme != THEME_COLOR

    var pagedLists by mutableStateOf(true)
        private set
    var showArt by mutableStateOf(true)
        private set
    var sortAz by mutableStateOf(false)
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
        // Older builds stored a single e-ink flag instead of a theme.
        val legacy = if (settings.getBoolean("eink", true)) THEME_WHITE else THEME_COLOR
        theme = settings.getInt("theme", legacy).coerceIn(THEME_LABELS.indices)
        pagedLists = settings.getBoolean("paged", true)
        showArt = settings.getBoolean("art", true)
        sortAz = settings.getBoolean("sort_az", false)
        textScaleIndex = settings.getInt("text_scale", 1).coerceIn(TEXT_SCALES.indices)
        progressMode = settings.getInt("progress", 1).coerceIn(PROGRESS_LABELS.indices)
    }

    fun cycleTheme() {
        theme = (theme + 1) % THEME_LABELS.size
        settings.edit().putInt("theme", theme).apply()
    }

    fun togglePaged() { pagedLists = !pagedLists; settings.edit().putBoolean("paged", pagedLists).apply() }
    fun toggleArt() { showArt = !showArt; settings.edit().putBoolean("art", showArt).apply() }

    fun toggleSort() { sortAz = !sortAz; settings.edit().putBoolean("sort_az", sortAz).apply() }

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
