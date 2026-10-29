package app.einkradio

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.einkradio.ui.AppTheme
import app.einkradio.ui.LoginScreen
import app.einkradio.ui.MiniPlayer
import app.einkradio.ui.NavBar
import app.einkradio.ui.NowPlayingScreen
import app.einkradio.ui.SettingsScreen
import app.einkradio.ui.StationsScreen
import app.einkradio.ui.mutedColor

class MainActivity : ComponentActivity() {
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { Root() } }
    }
}

private const val TAB_STATIONS = 0
private const val TAB_PLAYING = 1
private val TABS = listOf("Stations", "Playing", "Settings")

@Composable
private fun Root() {
    val loggedIn by Radio.loggedIn.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(TAB_STATIONS) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        ErrorBanner()

        if (!loggedIn) {
            Box(Modifier.weight(1f).padding(horizontal = 24.dp)) { LoginScreen() }
            return@Column
        }

        Box(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (tab) {
                TAB_STATIONS -> StationsScreen(onPicked = { tab = TAB_PLAYING })
                TAB_PLAYING -> NowPlayingScreen()
                else -> SettingsScreen()
            }
        }
        if (tab != TAB_PLAYING) MiniPlayer(onOpen = { tab = TAB_PLAYING })
        NavBar(tab, TABS) { tab = it }
    }
}

@Composable
private fun ErrorBanner() {
    val error by Radio.error.collectAsState()
    val message = error ?: return
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Text(
        "$message  (tap to dismiss)",
        color = cs.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(cs.onBackground.copy(alpha = if (app.einkradio.Prefs.eink) 0f else 0.08f), shape)
            .border(if (app.einkradio.Prefs.eink) 2.dp else 0.dp, cs.onBackground, shape)
            .clickable { Radio.error.value = null }
            .padding(12.dp),
    )
}
