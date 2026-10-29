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
import app.einkradio.pandora.Station
import app.einkradio.pandora.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    private lateinit var appContext: Context
    private var batteryJob: Job? = null
    private var pauseStopJob: Job? = null
    private var sleepJob: Job? = null

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // Held so the service binding lives as long as the process.
    private var controller: ListenableFuture<MediaController>? = null

    lateinit var player: ExoPlayer
        private set

    val loggedIn = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val stations = MutableStateFlow<List<Station>>(emptyList())
    val station = MutableStateFlow<Station?>(null)
    val track = MutableStateFlow<Track?>(null)
    val isPlaying = MutableStateFlow(false)

    /** Track id to feedback id for tracks loved this session (an empty id if Pandora sent none). */
    val loved = MutableStateFlow<Map<String, String>>(emptyMap())
    val upNext = MutableStateFlow<List<Track>>(emptyList())

    /** Same as [loved], for songs given a thumbs-down this session. */
    val banned = MutableStateFlow<Map<String, String>>(emptyMap())

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

    fun autoLogin() {
        Prefs.savedLogin()?.let { (user, pass) -> signIn(user, pass) }
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
            } catch (e: Exception) {
                error.value = e.message ?: "Sign-in failed"
            } finally {
                busy.value = false
            }
        }
    }

    fun signOut() {
        player.stop()
        player.clearMediaItems()
        tracks.clear()
        Prefs.clearLogin()
        stations.value = emptyList()
        station.value = null
        track.value = null
        loved.value = emptyMap()
        banned.value = emptyMap()
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

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Advances to the next queued track. Limits, if any, are whatever Pandora enforces. */
    fun skip() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else refill()
    }

    /** Thumbs-up, or undo it if the song is already loved. Works for the current, queued or past songs. */
    fun toggleLove(target: Track? = track.value) {
        val t = target ?: return
        val stationToken = t.stationToken ?: station.value?.token ?: return
        val existing = loved.value[t.id]
        scope.launch {
            try {
                if (existing != null) {
                    if (existing.isNotEmpty()) withContext(Dispatchers.IO) { api.removeRating(existing) }
                    loved.value = loved.value - t.id
                } else {
                    val feedbackId = withContext(Dispatchers.IO) { api.rate(stationToken, t, true) }
                    loved.value = loved.value + (t.id to (feedbackId ?: ""))
                    banned.value = banned.value - t.id // a thumbs-up replaces a thumbs-down
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
        val stationToken = t.stationToken ?: station.value?.token ?: return
        val existing = banned.value[t.id]
        scope.launch {
            try {
                if (existing != null) {
                    if (existing.isNotEmpty()) withContext(Dispatchers.IO) { api.removeRating(existing) }
                    banned.value = banned.value - t.id
                    return@launch
                }
                val feedbackId = withContext(Dispatchers.IO) { api.rate(stationToken, t, false) }
                banned.value = banned.value + (t.id to (feedbackId ?: ""))
                loved.value = loved.value - t.id // a thumbs-down replaces a thumbs-up
                if (t.id == track.value?.id) skip() else removeFromQueue(t.id)
            } catch (e: Exception) {
                error.value = e.message ?: "Could not save rating"
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
        player.addMediaItems(withArt.map(::mediaItem))
        publishQueue()
    }

    private fun publishQueue() {
        val from = player.currentMediaItemIndex + 1
        upNext.value = (from until player.mediaItemCount)
            .mapNotNull { tracks[player.getMediaItemAt(it).mediaId] }
    }

    private fun mediaItem(t: Track): MediaItem = MediaItem.Builder()
        .setMediaId(t.id)
        .setUri(t.audioUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album)
                .setArtworkUri((t.artUrl ?: t.artUrls.lastOrNull())?.let(android.net.Uri::parse))
                .build(),
        )
        .build()
}
