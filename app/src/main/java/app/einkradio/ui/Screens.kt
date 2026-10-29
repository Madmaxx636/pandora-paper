package app.einkradio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import app.einkradio.Prefs
import app.einkradio.Radio
import app.einkradio.pandora.Station
import app.einkradio.pandora.Track
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.zip.CRC32

private val CoverRadius = 12.dp

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun plainClick(onClick: () -> Unit): Modifier = Modifier.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)

/** Pill button. Flat tint in colour mode; black outline, no tint, in e-ink mode. */
@Composable
fun Btn(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val eink = Prefs.eink
    val shape = RoundedCornerShape(if (eink) 6.dp else 28.dp)
    val container = when {
        selected -> cs.primary
        eink -> cs.background
        else -> cs.onBackground.copy(alpha = 0.08f)
    }
    val content = if (selected) cs.onPrimary else cs.onBackground
    Box(
        modifier
            .heightIn(min = if (compact) 44.dp else 56.dp)
            .background(container, shape)
            .then(if (eink) Modifier.border(2.dp, cs.onBackground, shape) else Modifier)
            .then(plainClick(onClick))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    tint: Color? = null,
    rotate: Float = 0f,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val outlined = Prefs.eink && !filled
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) cs.primary else Color.Transparent)
            .then(if (outlined) Modifier.border(2.dp, cs.onBackground, CircleShape) else Modifier)
            .then(plainClick(onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            description,
            Modifier.size(iconSize).rotate(rotate),
            tint = tint ?: if (filled) cs.onPrimary else cs.onBackground,
        )
    }
}

@Composable
private fun Cover(urls: List<String>, modifier: Modifier = Modifier, radius: Dp = CoverRadius) {
    val shape = RoundedCornerShape(radius)
    val grey = if (Prefs.eink) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null
    // Try each candidate in turn; if every one fails, the placeholder note stays.
    var index by remember(urls) { mutableIntStateOf(0) }
    val url = urls.getOrNull(index)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))
            .then(if (Prefs.eink) Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text("♪", color = mutedColor(), style = MaterialTheme.typography.headlineMedium)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = grey,
                onError = { index++ },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Logo(size: Dp) {
    val cs = MaterialTheme.colorScheme
    val mono = Prefs.eink
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(
                if (mono) Brush.verticalGradient(listOf(cs.onBackground, cs.onBackground))
                else Brush.verticalGradient(listOf(AccentTop, AccentBottom)),
            ),
    ) {
        Image(
            AppIcons.Logo,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            colorFilter = if (mono) ColorFilter.tint(cs.background) else null,
        )
    }
}

private fun fmt(ms: Long): String {
    if (ms < 0) return "--:--"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ---------------------------------------------------------------------------------------------
// Sign in
// ---------------------------------------------------------------------------------------------

@Composable
fun LoginScreen() {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    val busy by Radio.busy.collectAsState()
    val fieldShape = RoundedCornerShape(if (Prefs.eink) 6.dp else 28.dp)

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo(96.dp)
        Spacer(Modifier.height(16.dp))
        Text("E-Ink Radio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Sign in with your Pandora account", color = mutedColor())
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Email") },
            singleLine = true,
            shape = fieldShape,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password") },
            singleLine = true,
            shape = fieldShape,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Btn(if (busy) "Signing in…" else "Sign in", Modifier.fillMaxWidth(), selected = true) {
            if (!busy && user.isNotBlank() && pass.isNotEmpty()) Radio.signIn(user.trim(), pass)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Now playing
// ---------------------------------------------------------------------------------------------

private const val VIEW_HISTORY = 0
private const val VIEW_NOW = 1
private const val VIEW_QUEUE = 2

@Composable
fun NowPlayingScreen() {
    val track by Radio.track.collectAsState()
    val station by Radio.station.collectAsState()
    val playing by Radio.isPlaying.collectAsState()
    val busy by Radio.busy.collectAsState()
    val loved by Radio.loved.collectAsState()
    val upNext by Radio.upNext.collectAsState()
    val history by Radio.history.collectAsState()
    var view by rememberSaveable { mutableIntStateOf(VIEW_NOW) }

    val t = track
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        station?.let {
            Text(
                it.name,
                style = MaterialTheme.typography.labelLarge,
                color = mutedColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            when {
                view == VIEW_HISTORY -> TrackList(history, "Nothing played yet")
                view == VIEW_QUEUE -> TrackList(upNext, "Nothing queued yet")
                t == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (busy) "Loading…" else "Nothing playing",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!busy) Text("Pick a station to start", color = mutedColor())
                }
                Prefs.showArt -> Cover(t.artUrls, Modifier.fillMaxHeight())
            }
        }

        if (t != null) {
            if (view == VIEW_NOW) {
                Text(
                    t.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    t.artist,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    t.album,
                    color = mutedColor(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
            }
            ProgressRow()
            Spacer(Modifier.height(12.dp))

            val isLoved = loved.containsKey(t.id)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(AppIcons.ThumbUp, "Ban song", 56.dp, 26.dp, rotate = 180f) { Radio.ban() }
                RoundButton(
                    if (playing) AppIcons.Pause else AppIcons.Play,
                    if (playing) "Pause" else "Play",
                    76.dp, 38.dp,
                    filled = true,
                ) { Radio.togglePlay() }
                RoundButton(AppIcons.SkipNext, "Skip", 56.dp, 28.dp) { Radio.skip() }
                RoundButton(
                    AppIcons.ThumbUp,
                    if (isLoved) "Remove love" else "Love song",
                    56.dp, 26.dp,
                    filled = Prefs.eink && isLoved,
                    tint = if (!Prefs.eink && isLoved) MaterialTheme.colorScheme.primary else null,
                ) { Radio.toggleLove() }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("History", Modifier.weight(1f), selected = view == VIEW_HISTORY, compact = true) { view = VIEW_HISTORY }
            Btn("Now playing", Modifier.weight(1f), selected = view == VIEW_NOW, compact = true) { view = VIEW_NOW }
            Btn("Up next", Modifier.weight(1f), selected = view == VIEW_QUEUE, compact = true) { view = VIEW_QUEUE }
        }
    }
}

@Composable
private fun TrackRow(t: Track) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (Prefs.showArt) {
            Cover(t.artUrls, Modifier.size(48.dp), radius = 6.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                t.artist,
                color = mutedColor(),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A list of songs: paged with Prev/Next when "Paged lists" is on, otherwise scrollable. */
@Composable
private fun TrackList(tracks: List<Track>, emptyText: String) {
    if (tracks.isEmpty()) {
        Text(emptyText, color = mutedColor())
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (Prefs.pagedLists) {
            val rowHeight = 64.dp
            val navHeight = 56.dp
            val perPage = ((maxHeight - navHeight) / rowHeight).toInt().coerceAtLeast(1)
            val pages = ((tracks.size + perPage - 1) / perPage).coerceAtLeast(1)
            var page by rememberSaveable { mutableIntStateOf(0) }
            val now = page.coerceIn(0, pages - 1)
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f)) {
                    tracks.drop(now * perPage).take(perPage).forEach { TrackRow(it) }
                }
                if (pages > 1) {
                    Row(
                        Modifier.fillMaxWidth().height(navHeight),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Btn("Prev", Modifier.weight(1f), compact = true) { page = (now - 1).coerceAtLeast(0) }
                        Text("${now + 1} / $pages", Modifier.weight(1f), textAlign = TextAlign.Center)
                        Btn("Next", Modifier.weight(1f), compact = true) { page = (now + 1).coerceAtMost(pages - 1) }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(tracks) { _, t -> TrackRow(t) }
            }
        }
    }
}

/**
 * Elapsed time, bar and time remaining. Colour mode refreshes every second; e-ink mode follows the
 * "Progress updates" setting so the panel isn't redrawn constantly.
 */
@Composable
private fun ProgressRow() {
    val interval = if (Prefs.eink) {
        when (Prefs.progressMode) { 1 -> 15_000L; 2 -> 5_000L; else -> 0L }
    } else {
        1_000L
    }
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

    val cs = MaterialTheme.colorScheme
    val fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(fmt(pos), color = mutedColor(), style = MaterialTheme.typography.bodySmall)
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(cs.onBackground.copy(alpha = if (Prefs.eink) 0f else 0.15f))
                .then(if (Prefs.eink) Modifier.border(1.5.dp, cs.onBackground, RoundedCornerShape(3.dp)) else Modifier),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(cs.primary))
        }
        Text(
            if (dur > 0) "-" + fmt(dur - pos) else "--:--",
            color = mutedColor(),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Stations
// ---------------------------------------------------------------------------------------------

private fun stationColor(name: String): Color {
    // Same recipe as the desktop app: hue from the name's CRC32, HLS lightness 0.38, saturation 0.45.
    val hue = (CRC32().apply { update(name.toByteArray()) }.value % 360).toFloat()
    val l = 0.38f
    val s = 0.45f
    val v = l + s * minOf(l, 1 - l)
    val sv = if (v == 0f) 0f else 2 * (1 - l / v)
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sv, v)))
}

@Composable
private fun StationAvatar(station: Station, size: Dp) {
    val cs = MaterialTheme.colorScheme
    val eink = Prefs.eink
    val icon = when {
        station.isQuickMix -> AppIcons.Shuffle
        station.isThumbprint -> AppIcons.ThumbUp
        else -> null
    }
    val fg = if (eink) cs.onBackground else Color.White
    var artFailed by remember(station.artUrl) { mutableStateOf(false) }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (eink) cs.background else stationColor(station.name))
            .then(if (eink) Modifier.border(2.dp, cs.onBackground, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            station.artUrl != null && Prefs.showArt && !artFailed -> AsyncImage(
                model = station.artUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (eink) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                onError = { artFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
            icon != null -> Icon(icon, null, Modifier.size(size * 0.5f), tint = fg)
            else -> Text(
                station.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪",
                color = fg,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Three static bars, the "this one is playing" marker. */
@Composable
private fun PlayingBars() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(20.dp)) {
        val bar = size.width / 5f
        listOf(0.55f, 1f, 0.75f).forEachIndexed { i, h ->
            val height = size.height * h
            drawRoundRect(
                color,
                Offset(i * bar * 2f, size.height - height),
                Size(bar, height),
                CornerRadius(bar / 2),
            )
        }
    }
}

@Composable
private fun StationRow(station: Station, selected: Boolean, onClick: () -> Unit) {
    val subtitle = when {
        station.isQuickMix -> "A mix of your stations"
        station.isThumbprint -> "Built from your thumbs"
        station.isShared -> "Shared station"
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(plainClick(onClick))
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationAvatar(station, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = mutedColor(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) PlayingBars()
    }
}

@Composable
private fun Divider() {
    val fg = MaterialTheme.colorScheme.onBackground
    HorizontalDivider(color = if (Prefs.eink) fg else fg.copy(alpha = 0.12f))
}

/** Matches the desktop search: the name, or any word in it, starts with the query. */
private fun matches(name: String, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val n = name.lowercase()
    return n.startsWith(q) || n.split(' ').any { it.startsWith(q) }
}

@Composable
fun StationsScreen(onPicked: () -> Unit) {
    val all by Radio.stations.collectAsState()
    val current by Radio.station.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val sortAz = Prefs.sortAz

    // QuickMix and Thumbprint first, then Pandora's order or A-Z.
    val shown = remember(all, query, sortAz) {
        all.withIndex()
            .filter { matches(it.value.name, query) }
            .sortedWith(
                compareBy<IndexedValue<Station>>(
                    { !it.value.isSpecial },
                    { if (sortAz) it.value.name.lowercase() else "" },
                    { it.index },
                ),
            )
            .map { it.value }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search stations…") },
                singleLine = true,
                shape = RoundedCornerShape(if (Prefs.eink) 6.dp else 28.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Btn("A–Z", selected = sortAz, compact = true) { Prefs.toggleSort() }
        }
        Spacer(Modifier.height(8.dp))

        if (shown.isEmpty()) {
            Text(
                if (all.isEmpty()) "No stations yet" else "No matches",
                color = mutedColor(),
                modifier = Modifier.padding(top = 24.dp).fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        } else if (Prefs.pagedLists) {
            PagedStations(shown, current, onPicked, Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(shown, key = { _, s -> s.token }) { i, s ->
                    if (i > 0 && shown[i - 1].isSpecial && !s.isSpecial) Divider()
                    StationRow(s, selected = s == current) { Radio.play(s); onPicked() }
                }
            }
        }
    }
}

@Composable
private fun PagedStations(
    shown: List<Station>,
    current: Station?,
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowHeight = 64.dp
    BoxWithConstraints(modifier) {
        val navHeight = 64.dp
        val perPage = ((maxHeight - navHeight) / rowHeight).toInt().coerceAtLeast(1)
        val pages = ((shown.size + perPage - 1) / perPage).coerceAtLeast(1)
        var page by rememberSaveable { mutableStateOf(0) }
        val now = page.coerceIn(0, pages - 1)

        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) {
                val start = now * perPage
                shown.drop(start).take(perPage).forEachIndexed { i, s ->
                    if ((start + i) > 0 && shown[start + i - 1].isSpecial && !s.isSpecial) Divider()
                    StationRow(s, selected = s == current) { Radio.play(s); onPicked() }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(navHeight),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Btn("Prev", Modifier.weight(1f), compact = true) { page = (now - 1).coerceAtLeast(0) }
                Text("${now + 1} / $pages", Modifier.weight(1f), textAlign = TextAlign.Center)
                Btn("Next", Modifier.weight(1f), compact = true) { page = (now + 1).coerceAtMost(pages - 1) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize()) {
        Section("Appearance")
        SettingRow("Theme", Prefs.THEME_LABELS[Prefs.theme]) { Prefs.cycleTheme() }
        SettingRow("Album art", if (Prefs.showArt) "Shown" else "Hidden") { Prefs.toggleArt() }
        SettingRow("Text size", "${(Prefs.textScale * 100).toInt()}%") { Prefs.cycleTextScale() }

        Section("Monochrome themes")
        SettingRow("Paged station lists", if (Prefs.pagedLists) "On" else "Off") { Prefs.togglePaged() }
        SettingRow("Progress updates", Prefs.PROGRESS_LABELS[Prefs.progressMode]) { Prefs.cycleProgress() }

        Spacer(Modifier.weight(1f))
        Btn("Sign out", Modifier.fillMaxWidth()) { Radio.signOut() }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(plainClick(onClick)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
    Divider()
}

// ---------------------------------------------------------------------------------------------
// Chrome: mini player and tab bar
// ---------------------------------------------------------------------------------------------

/** Strip shown above the tab bar while a song is loaded; tapping it opens Now Playing. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val track by Radio.track.collectAsState()
    val playing by Radio.isPlaying.collectAsState()
    val t = track ?: return
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(if (Prefs.eink) cs.background else cs.onBackground.copy(alpha = 0.07f))
            .then(if (Prefs.eink) Modifier.border(2.dp, cs.onBackground, shape) else Modifier)
            .then(plainClick(onOpen))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (Prefs.showArt) {
            Cover(t.artUrls, Modifier.size(44.dp), radius = 6.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, color = mutedColor(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundButton(
            if (playing) AppIcons.Pause else AppIcons.Play,
            if (playing) "Pause" else "Play",
            44.dp, 24.dp,
            filled = true,
        ) { Radio.togglePlay() }
    }
}

@Composable
fun NavBar(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val eink = Prefs.eink
    Column(Modifier.fillMaxWidth()) {
        if (eink) HorizontalDivider(thickness = 2.dp, color = cs.onBackground)
        else HorizontalDivider(color = cs.onBackground.copy(alpha = 0.12f))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                Column(
                    Modifier
                        .weight(1f)
                        .background(if (on && eink) cs.onBackground else Color.Transparent)
                        .then(plainClick { onSelect(i) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(if (on && !eink) cs.primary else Color.Transparent),
                    )
                    Box(Modifier.heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                on && eink -> cs.background
                                on -> cs.primary
                                else -> mutedColor()
                            },
                        )
                    }
                }
            }
        }
    }
}
