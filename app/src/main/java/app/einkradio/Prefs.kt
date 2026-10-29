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

    // Audio quality. Pandora offers three streams per song; which ones exist depends on the account.
    const val QUALITY_LOW = 2
    val QUALITY_LABELS = arrayOf("High", "Medium", "Low")
    const val CELLULAR_LOW = 1
    const val CELLULAR_WIFI_ONLY = 2
    val CELLULAR_LABELS = arrayOf("Same quality", "Low quality", "Wi-Fi only")

    var quality by mutableIntStateOf(0)
        private set
    var cellular by mutableIntStateOf(0)
        private set

    // Power saving
    val BATTERY_LABELS = arrayOf("Off", "At 20%", "At 10%")
    private val BATTERY_PERCENTS = intArrayOf(0, 20, 10)
    val PAUSE_STOP_LABELS = arrayOf("Never", "After 10 min", "After 30 min")
    private val PAUSE_STOP_MINUTES = intArrayOf(0, 10, 30)

    var largeBuffer by mutableStateOf(false)
        private set
    var batteryStop by mutableIntStateOf(0)
        private set
    var pauseStop by mutableIntStateOf(0)
        private set

    val batteryStopPercent: Int get() = BATTERY_PERCENTS[batteryStop]
    val pauseStopMinutes: Int get() = PAUSE_STOP_MINUTES[pauseStop]

    // Background playback
    var keepOnClose by mutableStateOf(true)
        private set
    var pauseOnUnplug by mutableStateOf(true)
        private set
    var yieldFocus by mutableStateOf(true)
        private set
    var resumeLast by mutableStateOf(true)
        private set

    /** Token of the station that was playing last, so it can be resumed on the next launch. */
    val lastStation: String? get() = settings.getString("last_station", null)
    var showArt by mutableStateOf(true)
        private set
    var sortAz by mutableStateOf(false)
        private set

    /** Tokens of pinned stations; stale tokens for deleted stations are harmless. */
    var pinned by mutableStateOf(emptySet<String>())
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
        pinned = settings.getStringSet("pinned", emptySet())?.toSet() ?: emptySet()
        quality = settings.getInt("quality", 0).coerceIn(QUALITY_LABELS.indices)
        cellular = settings.getInt("cellular", 0).coerceIn(CELLULAR_LABELS.indices)
        largeBuffer = settings.getBoolean("large_buffer", false)
        batteryStop = settings.getInt("battery_stop", 0).coerceIn(BATTERY_LABELS.indices)
        pauseStop = settings.getInt("pause_stop", 0).coerceIn(PAUSE_STOP_LABELS.indices)
        keepOnClose = settings.getBoolean("keep_on_close", true)
        pauseOnUnplug = settings.getBoolean("pause_on_unplug", true)
        yieldFocus = settings.getBoolean("yield_focus", true)
        resumeLast = settings.getBoolean("resume_last", true)
        textScaleIndex = settings.getInt("text_scale", 1).coerceIn(TEXT_SCALES.indices)
        progressMode = settings.getInt("progress", 1).coerceIn(PROGRESS_LABELS.indices)
    }

    fun cycleTheme() {
        theme = (theme + 1) % THEME_LABELS.size
        settings.edit().putInt("theme", theme).apply()
    }

    fun togglePaged() { pagedLists = !pagedLists; settings.edit().putBoolean("paged", pagedLists).apply() }
    fun toggleArt() { showArt = !showArt; settings.edit().putBoolean("art", showArt).apply() }

    fun cycleQuality() {
        quality = (quality + 1) % QUALITY_LABELS.size
        settings.edit().putInt("quality", quality).apply()
    }

    fun cycleCellular() {
        cellular = (cellular + 1) % CELLULAR_LABELS.size
        settings.edit().putInt("cellular", cellular).apply()
    }

    fun toggleLargeBuffer() { largeBuffer = !largeBuffer; settings.edit().putBoolean("large_buffer", largeBuffer).apply() }

    fun cycleBatteryStop() {
        batteryStop = (batteryStop + 1) % BATTERY_LABELS.size
        settings.edit().putInt("battery_stop", batteryStop).apply()
    }

    fun cyclePauseStop() {
        pauseStop = (pauseStop + 1) % PAUSE_STOP_LABELS.size
        settings.edit().putInt("pause_stop", pauseStop).apply()
    }

    fun toggleKeepOnClose() { keepOnClose = !keepOnClose; settings.edit().putBoolean("keep_on_close", keepOnClose).apply() }
    fun togglePauseOnUnplug() { pauseOnUnplug = !pauseOnUnplug; settings.edit().putBoolean("pause_on_unplug", pauseOnUnplug).apply() }
    fun toggleYieldFocus() { yieldFocus = !yieldFocus; settings.edit().putBoolean("yield_focus", yieldFocus).apply() }

    fun toggleResumeLast() { resumeLast = !resumeLast; settings.edit().putBoolean("resume_last", resumeLast).apply() }

    fun saveLastStation(token: String?) {
        settings.edit().apply { if (token == null) remove("last_station") else putString("last_station", token) }.apply()
    }

    fun togglePin(token: String) {
        pinned = if (token in pinned) pinned - token else pinned + token
        // putStringSet needs its own copy; the stored set must not be mutated afterwards.
        settings.edit().putStringSet("pinned", HashSet(pinned)).apply()
    }

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
