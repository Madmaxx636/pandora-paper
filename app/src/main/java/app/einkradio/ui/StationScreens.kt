package app.einkradio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.einkradio.Prefs
import app.einkradio.Radio
import app.einkradio.pandora.SearchHit
import app.einkradio.pandora.Seed
import app.einkradio.pandora.Station
import kotlinx.coroutines.delay
import java.util.Date

private val TAB_LABELS = listOf("Liked", "Disliked", "Music")

/**
 * Opened by touching and holding a station: play, pin, rename and delete it, see what you've liked
 * and disliked on it, and change the artists, songs and genres it is built from.
 */
@Composable
fun StationDetailScreen(station: Station, onBack: () -> Unit, onPlay: () -> Unit) {
    val context = LocalContext.current
    val detail by Radio.detail.collectAsState()
    val loading by Radio.detailLoading.collectAsState()
    val loved by Radio.loved.collectAsState()
    val banned by Radio.banned.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val editable = !station.isSpecial
    val pinned = station.token in Prefs.pinned

    LaunchedEffect(station.token) { if (editable) Radio.loadDetail(station) }
    BackHandler { if (adding) adding = false else onBack() }

    val data = detail?.takeIf { it.first == station.token }?.second
    // Undoing a rating takes the song off these lists straight away.
    val liked = data?.feedback?.up.orEmpty().filter { it.ratingKey in loved }
    val disliked = data?.feedback?.down.orEmpty().filter { it.ratingKey in banned }
    val seeds = data?.seeds.orEmpty()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Btn("‹", compact = true, onClick = onBack)
            Spacer(Modifier.width(12.dp))
            StationAvatar(station, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    station.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val created = data?.created?.let {
                    "Created " + android.text.format.DateFormat.getMediumDateFormat(context).format(Date(it))
                }
                if (created != null) Text(created, color = mutedColor(), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Play", Modifier.weight(1f), selected = true, compact = true) {
                Radio.play(station)
                onPlay()
            }
            Btn(if (pinned) "Unpin" else "Pin", Modifier.weight(1f), compact = true) { Prefs.togglePin(station.token) }
            if (editable) {
                Btn("Rename", Modifier.weight(1f), compact = true) { renaming = true }
                Btn("Delete", Modifier.weight(1f), compact = true) { confirmDelete = true }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (!editable) {
            Text(
                "This station is built from your other stations and your thumbs, so there is nothing to edit " +
                    "here. Your liked and disliked songs are under Settings.",
                color = mutedColor(),
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val counts = listOf(liked.size, disliked.size, seeds.size)
                TAB_LABELS.forEachIndexed { i, label ->
                    Btn("$label (${counts[i]})", Modifier.weight(1f), selected = tab == i, compact = true) {
                        tab = i
                        adding = false
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val empty = if (loading) "Loading…" else null
                when {
                    tab == 0 -> TrackList(liked, empty ?: "Nothing liked on this station yet", allowBan = false)
                    tab == 1 -> TrackList(disliked, empty ?: "Nothing disliked on this station yet", allowLove = false)
                    adding -> Column(Modifier.fillMaxSize()) {
                        Btn("Done", Modifier.fillMaxWidth(), compact = true) { adding = false }
                        Spacer(Modifier.height(8.dp))
                        SearchPanel("Add") { hit -> Radio.addSeed(station, hit) }
                    }
                    else -> Column(Modifier.fillMaxSize()) {
                        Btn("Add artists, songs or genres", Modifier.fillMaxWidth(), selected = true, compact = true) {
                            adding = true
                        }
                        Spacer(Modifier.height(8.dp))
                        if (seeds.isEmpty()) {
                            Text(empty ?: "No music listed for this station", color = mutedColor())
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(seeds, key = { it.id }) { seed -> SeedRow(seed) { Radio.removeSeed(station, seed) } }
                            }
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        var name by rememberSaveable { mutableStateOf(station.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename station") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty() && trimmed != station.name) Radio.renameStation(station, trimmed)
                    renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete station?") },
            text = { Text("\"${station.name}\" will be removed from your Pandora account.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    Radio.deleteStation(station)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SeedRow(seed: Seed, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (Prefs.showArt) {
            Cover(listOfNotNull(seed.artUrl), Modifier.size(48.dp), radius = 6.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(seed.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            seed.subtitle?.let {
                Text(
                    it,
                    color = mutedColor(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RoundButton(AppIcons.Close, "Remove from station", 44.dp, 20.dp, onClick = onRemove)
    }
}

/** Search for an artist, song or genre to create a station from. */
@Composable
fun NewStationScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Btn("‹", compact = true, onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("New station", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        SearchPanel("Create") { hit -> Radio.createStation(hit, onDone = onBack) }
    }
}

@Composable
private fun SearchPanel(actionLabel: String, onPick: (SearchHit) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by Radio.searchResults.collectAsState()
    val searching by Radio.searching.collectAsState()

    DisposableEffect(Unit) { onDispose { Radio.clearSearch() } }
    LaunchedEffect(query) {
        val text = query.trim()
        if (text.length < 2) {
            Radio.clearSearch()
            return@LaunchedEffect
        }
        delay(450) // wait for a pause in typing
        Radio.search(text)
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search artists, songs, genres…") },
            singleLine = true,
            shape = RoundedCornerShape(if (Prefs.eink) 6.dp else 28.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        val r = results
        when {
            r == null -> Text(
                if (searching) "Searching…" else "Type at least two letters",
                color = mutedColor(),
            )
            r.isEmpty -> Text("No results", color = mutedColor())
            else -> LazyColumn(Modifier.fillMaxSize()) {
                hitSection("Artists", r.artists, actionLabel, onPick)
                hitSection("Songs", r.songs, actionLabel, onPick)
                hitSection("Genres", r.genres, actionLabel, onPick)
            }
        }
    }
}

private fun LazyListScope.hitSection(
    title: String,
    hits: List<SearchHit>,
    actionLabel: String,
    onPick: (SearchHit) -> Unit,
) {
    if (hits.isEmpty()) return
    item(key = "header:$title") {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        )
    }
    items(hits.take(6), key = { "${it.kind}:${it.token}" }) { hit ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(hit.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                hit.subtitle?.let {
                    Text(
                        it,
                        color = mutedColor(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Btn(actionLabel, compact = true) { onPick(hit) }
        }
    }
}
