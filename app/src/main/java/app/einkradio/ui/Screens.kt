package app.einkradio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.einkradio.Prefs
import app.einkradio.Radio
import coil.compose.AsyncImage
import androidx.media3.common.C
import kotlinx.coroutines.delay

/** Bordered button with no ripple or animation, sized for a finger on a slow panel. */
@Composable
fun Btn(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = MaterialTheme.colorScheme.onBackground
    val bg = MaterialTheme.colorScheme.background
    val shape = RoundedCornerShape(if (Prefs.eink) 4.dp else 12.dp)
    Box(
        modifier
            .heightIn(min = 64.dp)
            .background(if (selected) fg else bg, shape)
            .border(BorderStroke(2.dp, fg), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) bg else fg,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun LoginScreen() {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    val busy by Radio.busy.collectAsState()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("Sign in", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Btn(
            if (busy) "Signing in…" else "Sign in",
            Modifier.fillMaxWidth(),
        ) { if (!busy && user.isNotBlank() && pass.isNotEmpty()) Radio.signIn(user.trim(), pass) }
    }
}

@Composable
fun NowPlayingScreen() {
    val track by Radio.track.collectAsState()
    val station by Radio.station.collectAsState()
    val playing by Radio.isPlaying.collectAsState()
    val busy by Radio.busy.collectAsState()

    val t = track
    if (t == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (busy) "Loading…" else "Pick a station",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        station?.let { Text(it.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (Prefs.showArt) {
            Box(Modifier.weight(1f).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = t.artUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (Prefs.eink) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f).border(2.dp, MaterialTheme.colorScheme.outline),
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(t.artist, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.album, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ProgressText()
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Dislike", Modifier.weight(1f)) { Radio.rate(false) }
            Btn("Like", Modifier.weight(1f)) { Radio.rate(true) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (playing) "Pause" else "Play", Modifier.weight(1f)) { Radio.togglePlay() }
            Btn("Skip", Modifier.weight(1f)) { Radio.skip() }
        }
    }
}

/** Elapsed time as text, refreshed on a slow timer so a panel isn't redrawn every second. */
@Composable
private fun ProgressText() {
    val interval = when (Prefs.progressMode) { 1 -> 15_000L; 2 -> 5_000L; else -> 0L }
    if (interval == 0L) return
    val track by Radio.track.collectAsState()
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(C.TIME_UNSET) }
    LaunchedEffect(interval, track) {
        while (true) {
            pos = Radio.player.currentPosition
            dur = Radio.player.duration
            delay(interval)
        }
    }
    Text("${fmt(pos)} / ${fmt(dur)}", style = MaterialTheme.typography.bodyMedium)
}

private fun fmt(ms: Long): String {
    if (ms < 0) return "--:--"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun StationsScreen(onPicked: () -> Unit) {
    val stations by Radio.stations.collectAsState()
    val current by Radio.station.collectAsState()
    val rowHeight = 72.dp

    if (!Prefs.pagedLists) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(stations, key = { it.token }) { s ->
                Btn(s.name, Modifier.fillMaxWidth(), selected = s == current) { Radio.play(s); onPicked() }
            }
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val navHeight = 72.dp
        val perPage = ((maxHeight - navHeight) / (rowHeight + 8.dp)).toInt().coerceAtLeast(1)
        val pages = ((stations.size + perPage - 1) / perPage).coerceAtLeast(1)
        var page by rememberSaveable { mutableStateOf(0) }
        val shown = page.coerceIn(0, pages - 1)

        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stations.drop(shown * perPage).take(perPage).forEach { s ->
                    Btn(s.name, Modifier.fillMaxWidth().heightIn(min = rowHeight), selected = s == current) {
                        Radio.play(s); onPicked()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(navHeight), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Btn("Prev", Modifier.weight(1f)) { page = (shown - 1).coerceAtLeast(0) }
                Text("${shown + 1} / $pages", Modifier.weight(1f), textAlign = TextAlign.Center)
                Btn("Next", Modifier.weight(1f)) { page = (shown + 1).coerceAtMost(pages - 1) }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Setting("E-ink mode", if (Prefs.eink) "On" else "Off") { Prefs.toggleEink() }
        Setting("Paged lists", if (Prefs.pagedLists) "On" else "Off") { Prefs.togglePaged() }
        Setting("Album art", if (Prefs.showArt) "Shown" else "Hidden") { Prefs.toggleArt() }
        Setting("Text size", "${(Prefs.textScale * 100).toInt()}%") { Prefs.cycleTextScale() }
        Setting("Progress updates", Prefs.PROGRESS_LABELS[Prefs.progressMode]) { Prefs.cycleProgress() }
        Spacer(Modifier.weight(1f))
        Btn("Sign out", Modifier.fillMaxWidth()) { Radio.signOut() }
    }
}

@Composable
private fun Setting(label: String, value: String, onClick: () -> Unit) {
    Btn("$label: $value", Modifier.fillMaxWidth(), onClick = onClick)
}
