package app.einkradio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.einkradio.pandora.PandoraApi
import app.einkradio.pandora.SearchHit
import app.einkradio.pandora.SearchResults
import app.einkradio.pandora.Seed
import app.einkradio.pandora.StationDetail
import app.einkradio.pandora.Station
import app.einkradio.pandora.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Process-wide player and Pandora session; the UI and [PlaybackService] both use it. */
object Radio {
    private const val REFILL_WHEN_REMAINING = 2
    private const val HISTORY_LIMIT = 50
    private const val WIFI_ONLY_MESSAGE = "Wi-Fi only is on and this connection is metered."

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = PandoraApi()
    private val tracks = HashMap<String, Track>()
    private var refilling = false
    private var resumePending = false

    private lateinit var appContext: Context
    private var batteryJob: Job? = null
    private var pauseStopJob: Job? = null
    private var sleepJob: Job? = null
    private var libraryJob: Job? = null
    private var searchJob: Job? = null

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // Held so the service binding lives as long as the process.
    private var controller: ListenableFuture<MediaController>? = null

    lateinit var player: ExoPlayer
        private set

    val loggedIn = MutableStateFlow(false)

    /** True from launch until the saved login has succeeded or failed; the UI shows the splash meanwhile. */
    val restoring = MutableStateFlow(false)

    /** Set once the startup splash has played, so it runs once per process. */
    var splashPlayed = false
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val stations = MutableStateFlow<List<Station>>(emptyList())
    val station = MutableStateFlow<Station?>(null)
    val track = MutableStateFlow<Track?>(null)
    val isPlaying = MutableStateFlow(false)

    /** Rating key ([Track.ratingKey]) to feedback id for loved songs (an empty id if not known yet). */
    val loved = MutableStateFlow<Map<String, String>>(emptyMap())
    val upNext = MutableStateFlow<List<Track>>(emptyList())

    /** Same as [loved], for disliked songs. */
    val banned = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Everything thumbed up and down on Pandora, newest first, as of the last [refreshLibrary]. */
    val thumbsUp = MutableStateFlow<List<Track>>(emptyList())
    val thumbsDown = MutableStateFlow<List<Track>>(emptyList())
    val libraryLoading = MutableStateFlow(false)

    /** The open station screen's data, tagged with the station token it belongs to. */
    val detail = MutableStateFlow<Pair<String, StationDetail>?>(null)
    val detailLoading = MutableStateFlow(false)
    val searchResults = MutableStateFlow<SearchResults?>(null)
    val searching = MutableStateFlow(false)

    /** Songs already played this session, most recent first. */
    val history = MutableStateFlow<List<Track>>(emptyList())

    /** Sleep timer length in minutes (0 = off) and the wall-clock time it fires. */
    val sleepMinutes = MutableStateFlow(0)
    val sleepEndsAt = MutableStateFlow<Long?>(null)

    fun init(context: Context) {
        appContext = context.applicationContext

        // A bigger buffer downloads more of each song in one go, so the radio can sleep longer.
        // The buffer size is fixed when the player is built, so changing it needs a restart.
        val loadControl = DefaultLoadControl.Builder().apply {
            if (Prefs.largeBuffer) {
                setBufferDurationsMs(60_000, 240_000, 2_500, 5_000)
                setPrioritizeTimeOverSizeThresholds(true)
            }
        }.build()

        player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, Prefs.yieldFocus)
            .setHandleAudioBecomingNoisy(Prefs.pauseOnUnplug)
            // Keep the CPU and Wi-Fi awake while playing so streaming survives a screen-off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val previous = track.value
                val next = mediaItem?.let { tracks[it.mediaId] }
                if (previous != null && previous.id != next?.id) {
                    history.value = (listOf(previous) + history.value).take(HISTORY_LIMIT)
                }
                track.value = next
                publishQueue()
                // Songs already played stay in the queue otherwise, and "previous" would go back to them.
                scope.launch(Dispatchers.Main) { prunePlayed() }
                if (player.mediaItemCount - player.currentMediaItemIndex <= REFILL_WHEN_REMAINING) refill()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Radio.isPlaying.value = isPlaying
                watchBattery(isPlaying)
                schedulePauseStop(paused = !isPlaying && !player.playWhenReady)
            }

            override fun onPlayerError(e: PlaybackException) {
                error.value = "Playback error: ${e.errorCodeName}"
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                } else {
                    refill()
                }
            }
        })

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controller = MediaController.Builder(context, token).buildAsync()
    }

    /**
     * Starts the station that was playing when the app was last used. Called when the screen is first
     * opened; if the saved login hasn't finished yet, it starts as soon as it has.
     */
    fun resumeLastStation() {
        if (!Prefs.resumeLast || station.value != null) return
        if (loggedIn.value) startLastStation() else resumePending = true
    }

    private fun startLastStation() {
        val token = Prefs.lastStation ?: return
        stations.value.firstOrNull { it.token == token }?.let(::play)
    }

    fun autoLogin() {
        Prefs.savedLogin()?.let { (user, pass) ->
            restoring.value = true
            signIn(user, pass)
        }
    }

    fun signIn(user: String, pass: String) {
        scope.launch {
            busy.value = true
            error.value = null
            try {
                val list = withContext(Dispatchers.IO) {
                    api.login(user, pass)
                    api.getStations()
                }
                Prefs.saveLogin(user, pass)
                stations.value = list
                loggedIn.value = true
                refreshLibrary()
                if (resumePending) {
                    resumePending = false
                    startLastStation()
                }
            } catch (e: Exception) {
                resumePending = false
                error.value = e.message ?: "Sign-in failed"
            } finally {
                restoring.value = false
                busy.value = false
            }
        }
    }

    fun signOut() {
        player.stop()
        player.clearMediaItems()
        tracks.clear()
        Prefs.clearLogin()
        Prefs.saveLastStation(null)
        Prefs.clearRecentStations()
        resumePending = false
        stations.value = emptyList()
        station.value = null
        track.value = null
        loved.value = emptyMap()
        banned.value = emptyMap()
        thumbsUp.value = emptyList()
        thumbsDown.value = emptyList()
        upNext.value = emptyList()
        history.value = emptyList()
        loggedIn.value = false
    }

    fun play(s: Station) {
        if (blockedByNetwork()) {
            error.value = WIFI_ONLY_MESSAGE
            return
        }
        scope.launch {
            busy.value = true
            error.value = null
            try {
                val quality = audioQuality()
                val batch = withContext(Dispatchers.IO) { api.getPlaylist(s, quality) }
                station.value = s
                Prefs.saveLastStation(s.token)
                Prefs.addRecentStation(s.token)
                player.clearMediaItems()
                tracks.clear()
                enqueue(batch)
                player.prepare()
                player.play()
            } catch (e: Exception) {
                error.value = e.message ?: "Could not start station"
            } finally {
                busy.value = false
            }
        }
    }

    /** Applies the background-playback settings that can change while the player is running. */
    fun applyPlaybackPrefs() {
        player.setAudioAttributes(audioAttributes, Prefs.yieldFocus)
        player.setHandleAudioBecomingNoisy(Prefs.pauseOnUnplug)
    }

    /**
     * Shuts everything down: timers, the service binding, the player and the service itself. The
     * caller then closes the activity and ends the process; the player can't be used after this.
     */
    fun quit() {
        sleepJob?.cancel()
        batteryJob?.cancel()
        pauseStopJob?.cancel()
        // Our own controller keeps the service bound, so it has to go before the service can stop.
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        player.release()
        appContext.stopService(Intent(appContext, PlaybackService::class.java))
    }

    /** Stops playback and empties the queue; the station and history stay. */
    fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
        tracks.clear()
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepMinutes.value = minutes
        if (minutes <= 0) {
            sleepEndsAt.value = null
            return
        }
        sleepEndsAt.value = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            player.pause()
            sleepMinutes.value = 0
            sleepEndsAt.value = null
        }
    }

    private fun onMeteredNetwork(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    }

    /** True on a connection Android treats as metered (usually mobile data). */
    fun isMetered(): Boolean = onMeteredNetwork()

    private fun blockedByNetwork(): Boolean =
        Prefs.cellular == Prefs.CELLULAR_WIFI_ONLY && onMeteredNetwork()

    /** The quality to request for new songs: the chosen one, or low on a metered connection if asked. */
    private fun audioQuality(): Int =
        if (Prefs.cellular == Prefs.CELLULAR_LOW && onMeteredNetwork()) Prefs.QUALITY_LOW else Prefs.quality

    /** While playing, check the battery once a minute and pause if it has dropped below the limit. */
    private fun watchBattery(playing: Boolean) {
        batteryJob?.cancel()
        if (!playing) return
        batteryJob = scope.launch {
            while (true) {
                val limit = Prefs.batteryStopPercent
                if (limit > 0) {
                    val status = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val charging = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                    if (level >= 0 && scale > 0 && !charging) {
                        val percent = level * 100 / scale
                        if (percent <= limit) {
                            player.pause()
                            error.value = "Paused: battery at $percent%"
                            return@launch
                        }
                    }
                }
                delay(60_000L)
            }
        }
    }

    /** After the user pauses, stop the player entirely once the chosen time has passed. */
    private fun schedulePauseStop(paused: Boolean) {
        pauseStopJob?.cancel()
        val minutes = Prefs.pauseStopMinutes
        if (!paused || minutes <= 0) return
        pauseStopJob = scope.launch {
            delay(minutes * 60_000L)
            if (!player.playWhenReady) stopPlayback()
        }
    }

    /** Starts the current song again from the beginning. */
    fun replay() {
        if (player.currentMediaItem != null) player.seekTo(0)
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Advances to the next queued track. Limits, if any, are whatever Pandora enforces. */
    fun skip() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else refill()
    }

    /**
     * Thumbs-up, or undo it if the song is already loved. Works for the current, queued or past songs.
     * Ratings are matched by artist and title ([Track.ratingKey]), so a song loved on one station
     * shows as loved on every station.
     */
    fun toggleLove(target: Track? = track.value) {
        val t = target ?: return
        val key = t.ratingKey
        val existing = loved.value[key]
        scope.launch {
            try {
                if (existing != null) {
                    withContext(Dispatchers.IO) { deleteRating(t, existing) }
                    loved.value = loved.value - key
                } else {
                    val stationToken = t.stationToken ?: station.value?.token ?: return@launch
                    val feedbackId = withContext(Dispatchers.IO) { api.rate(stationToken, t, true) }
                    loved.value = loved.value + (key to (feedbackId ?: ""))
                    banned.value = banned.value - key // a thumbs-up replaces a thumbs-down
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Could not save rating"
            }
        }
    }

    /**
     * Thumbs-down, or undo it if the song is already disliked. Disliking the playing song skips it,
     * disliking a queued song drops it from the queue, and a played song just keeps the rating.
     */
    fun ban(target: Track? = track.value) {
        val t = target ?: return
        val key = t.ratingKey
        val existing = banned.value[key]
        scope.launch {
            try {
                if (existing != null) {
                    withContext(Dispatchers.IO) { deleteRating(t, existing) }
                    banned.value = banned.value - key
                    return@launch
                }
                val stationToken = t.stationToken ?: station.value?.token ?: return@launch
                val feedbackId = withContext(Dispatchers.IO) { api.rate(stationToken, t, false) }
                banned.value = banned.value + (key to (feedbackId ?: ""))
                loved.value = loved.value - key // a thumbs-down replaces a thumbs-up
                if (t.id == track.value?.id) skip() else removeFromQueue(t.id)
            } catch (e: Exception) {
                error.value = e.message ?: "Could not save rating"
            }
        }
    }

    /**
     * Deletes a rating. A song that arrived already rated has no feedback id yet, so look it up on the
     * station first.
     */
    private fun deleteRating(t: Track, feedbackId: String) {
        val id = feedbackId.ifEmpty {
            val stationToken = t.stationToken ?: station.value?.token
                ?: throw IllegalStateException("Don't know which station rated this song")
            val feedback = api.getFeedback(stationToken)
            (feedback.up + feedback.down).firstOrNull { it.ratingKey == t.ratingKey }?.id
                ?: throw IllegalStateException("Couldn't find that rating on Pandora")
        }
        api.removeRating(id)
    }

    /**
     * Pulls every thumbs-up and thumbs-down from Pandora, station by station, and merges them into
     * the rating state so songs you rated elsewhere show correctly here.
     */
    fun refreshLibrary() {
        if (libraryJob?.isActive == true) return
        val list = stations.value.filter { !it.isQuickMix }
        if (list.isEmpty()) return
        libraryJob = scope.launch {
            libraryLoading.value = true
            try {
                val gate = Semaphore(4)
                val results = withContext(Dispatchers.IO) {
                    coroutineScope {
                        list.map { s ->
                            async { gate.withPermit { runCatching { api.getFeedback(s.token) }.getOrNull() } }
                        }.awaitAll()
                    }
                }.filterNotNull()
                if (results.isEmpty()) {
                    error.value = "Couldn't load your liked and disliked songs"
                    return@launch
                }
                val up = results.flatMap { it.up }.distinctBy { it.ratingKey }
                val down = results.flatMap { it.down }.distinctBy { it.ratingKey }
                val upIds = up.associate { it.ratingKey to it.id }
                val downIds = down.associate { it.ratingKey to it.id }
                thumbsUp.value = up
                thumbsDown.value = down
                // Pandora is the source of truth; it also fills in ids we were missing.
                loved.value = (loved.value + upIds) - downIds.keys
                banned.value = (banned.value + downIds) - upIds.keys
            } finally {
                libraryLoading.value = false
            }
        }
    }

    // --- Station management (the screen that opens when you touch and hold a station) ---

    fun loadDetail(s: Station) {
        scope.launch {
            detailLoading.value = true
            try {
                val d = withContext(Dispatchers.IO) { api.getStationDetail(s.token) }
                detail.value = s.token to d
                // The thumbs lists also tell us the feedback ids needed to undo a rating.
                val upIds = d.feedback.up.associate { it.ratingKey to it.id }
                val downIds = d.feedback.down.associate { it.ratingKey to it.id }
                loved.value = (loved.value + upIds) - downIds.keys
                banned.value = (banned.value + downIds) - upIds.keys
            } catch (e: Exception) {
                error.value = e.message ?: "Couldn't load station details"
            } finally {
                detailLoading.value = false
            }
        }
    }

    fun search(text: String) {
        searchJob?.cancel()
        searchJob = scope.launch {
            searching.value = true
            try {
                searchResults.value = withContext(Dispatchers.IO) { api.search(text) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "Search failed"
            } finally {
                searching.value = false
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchResults.value = null
        searching.value = false
    }

    fun addSeed(s: Station, hit: SearchHit) =
        manage("Couldn't add that music", { api.addSeed(s.token, hit.token) }) { loadDetail(s) }

    fun removeSeed(s: Station, seed: Seed) =
        manage("Couldn't remove that music", { api.removeSeed(seed.id) }) { loadDetail(s) }

    fun renameStation(s: Station, name: String) =
        manage("Couldn't rename the station", { api.renameStation(s.token, name) }) {
            reloadStations {
                val current = station.value ?: return@reloadStations
                stations.value.firstOrNull { it.token == current.token }?.let { station.value = it }
            }
        }

    fun deleteStation(s: Station) =
        manage("Couldn't delete the station", { api.deleteStation(s.token) }) {
            if (station.value?.token == s.token) {
                stopPlayback()
                station.value = null
                Prefs.saveLastStation(null)
            }
            if (s.token in Prefs.pinned) Prefs.togglePin(s.token)
            reloadStations()
        }

    /** Creates a station from a search result (an artist, song or genre). */
    fun createStation(hit: SearchHit, onDone: () -> Unit) =
        manage("Couldn't create that station", { api.createStation(hit.token) }) {
            reloadStations()
            onDone()
        }

    /** Runs [work] off the main thread, then [after] on it; failures show in the error banner. */
    private fun manage(failure: String, work: () -> Unit, after: () -> Unit = {}) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
                after()
            } catch (e: Exception) {
                error.value = e.message ?: failure
            }
        }
    }

    private fun reloadStations(then: () -> Unit = {}) {
        scope.launch {
            try {
                stations.value = withContext(Dispatchers.IO) { api.getStations() }
                then()
            } catch (e: Exception) {
                error.value = e.message ?: "Couldn't refresh your stations"
            }
        }
    }

    private fun removeFromQueue(id: String) {
        for (i in player.mediaItemCount - 1 downTo player.currentMediaItemIndex + 1) {
            if (player.getMediaItemAt(i).mediaId == id) player.removeMediaItem(i)
        }
        publishQueue()
        if (player.mediaItemCount - player.currentMediaItemIndex <= REFILL_WHEN_REMAINING) refill()
    }

    private fun refill() {
        val s = station.value ?: return
        if (refilling) return
        if (blockedByNetwork()) {
            error.value = WIFI_ONLY_MESSAGE
            return
        }
        refilling = true
        scope.launch {
            try {
                val quality = audioQuality()
                val batch = withContext(Dispatchers.IO) { api.getPlaylist(s, quality) }
                if (station.value == s) {
                    val wasEnded = player.playbackState == Player.STATE_ENDED
                    enqueue(batch)
                    if (wasEnded) {
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    }
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Could not load more tracks"
            } finally {
                refilling = false
            }
        }
    }

    private fun enqueue(batch: List<Track>) {
        // A song with no art of its own falls back to the station's art.
        val stationArt = station.value?.artUrl
        val stationToken = station.value?.token
        val withArt = batch.map {
            val art = if (stationArt != null && stationArt !in it.artUrls) it.artUrls + stationArt else it.artUrls
            it.copy(artUrls = art, stationToken = stationToken)
        }
        withArt.forEach { tracks[it.id] = it }
        // Pandora marks songs you've already thumbed up; show them as loved.
        val alreadyLoved = withArt.filter { it.rating > 0 && it.ratingKey !in loved.value }
        if (alreadyLoved.isNotEmpty()) loved.value = loved.value + alreadyLoved.associate { it.ratingKey to "" }
        player.addMediaItems(withArt.map(::mediaItem))
        publishQueue()
    }

    private fun prunePlayed() {
        val index = player.currentMediaItemIndex
        if (index > 0) {
            player.removeMediaItems(0, index)
            publishQueue()
        }
    }

    private fun publishQueue() {
        val from = player.currentMediaItemIndex + 1
        upNext.value = (from until player.mediaItemCount)
            .mapNotNull { tracks[player.getMediaItemAt(it).mediaId] }
    }

    /**
     * What the notification, lock screen, car and launchers show. They display a title and one line
     * under it, so that line carries the artist and the station ("Artist · Station"). The album is left
     * out: the car appended it in brackets after the artist, which cluttered the line.
     */
    private fun mediaItem(t: Track): MediaItem {
        val stationName = station.value?.name
        val byline = listOfNotNull(t.artist.takeIf { it.isNotBlank() }, stationName?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(t.audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(byline)
                    .setArtworkUri((t.artUrl ?: t.artUrls.lastOrNull())?.let(android.net.Uri::parse))
                    .build(),
            )
            .build()
    }
}
