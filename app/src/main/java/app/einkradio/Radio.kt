package app.einkradio

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.einkradio.pandora.PandoraApi
import app.einkradio.pandora.Station
import app.einkradio.pandora.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-wide player and Pandora session; the UI and [PlaybackService] both use it. */
object Radio {
    private const val REFILL_WHEN_REMAINING = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = PandoraApi()
    private val tracks = HashMap<String, Track>()
    private var refilling = false

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

    fun init(context: Context) {
        player = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                track.value = mediaItem?.let { tracks[it.mediaId] }
                publishQueue()
                if (player.mediaItemCount - player.currentMediaItemIndex <= REFILL_WHEN_REMAINING) refill()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Radio.isPlaying.value = isPlaying
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
        upNext.value = emptyList()
        loggedIn.value = false
    }

    fun play(s: Station) {
        scope.launch {
            busy.value = true
            error.value = null
            try {
                val batch = withContext(Dispatchers.IO) { api.getPlaylist(s) }
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

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Advances to the next queued track. Limits, if any, are whatever Pandora enforces. */
    fun skip() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else refill()
    }

    /** Thumbs-up, or undo it if this track is already loved. */
    fun toggleLove() {
        val s = station.value ?: return
        val t = track.value ?: return
        val existing = loved.value[t.id]
        scope.launch {
            try {
                if (existing != null) {
                    if (existing.isNotEmpty()) withContext(Dispatchers.IO) { api.removeRating(existing) }
                    loved.value = loved.value - t.id
                } else {
                    val feedbackId = withContext(Dispatchers.IO) { api.rate(s, t, true) }
                    loved.value = loved.value + (t.id to (feedbackId ?: ""))
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Could not save rating"
            }
        }
    }

    /** Thumbs-down; Pandora then moves on to the next song. */
    fun ban() {
        val s = station.value ?: return
        val t = track.value ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.rate(s, t, false) }
                skip()
            } catch (e: Exception) {
                error.value = e.message ?: "Could not save rating"
            }
        }
    }

    private fun refill() {
        val s = station.value ?: return
        if (refilling) return
        refilling = true
        scope.launch {
            try {
                val batch = withContext(Dispatchers.IO) { api.getPlaylist(s) }
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
        batch.forEach { tracks[it.id] = it }
        player.addMediaItems(batch.map(::mediaItem))
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
                .setArtworkUri(t.artUrl?.let(android.net.Uri::parse))
                .build(),
        )
        .build()
}
