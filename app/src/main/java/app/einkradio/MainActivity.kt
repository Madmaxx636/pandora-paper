package app.einkradio

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import app.einkradio.ui.Btn
import app.einkradio.ui.LoginScreen
import app.einkradio.ui.NowPlayingScreen
import app.einkradio.ui.SettingsScreen
import app.einkradio.ui.StationsScreen

class MainActivity : ComponentActivity() {
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { Root() } }
    }
}

private val TABS = listOf("Playing", "Stations", "Settings")

@Composable
private fun Root() {
    val loggedIn by Radio.loggedIn.collectAsState()
    val error by Radio.error.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(1) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { Radio.error.value = null }
                    .padding(bottom = 8.dp),
            )
        }

        if (!loggedIn) {
            LoginScreen()
            return@Column
        }

        Column(Modifier.weight(1f)) {
            when (tab) {
                0 -> NowPlayingScreen()
                1 -> StationsScreen(onPicked = { tab = 0 })
                else -> SettingsScreen()
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TABS.forEachIndexed { i, label ->
                Btn(label, Modifier.weight(1f), selected = tab == i) { tab = i }
            }
        }
    }
}
