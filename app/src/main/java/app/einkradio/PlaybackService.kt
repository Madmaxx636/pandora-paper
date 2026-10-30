package app.einkradio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.einkradio.pandora.Station
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Exposes [Radio.player] as a media session (notification, lock screen, Bluetooth) and as a browsable
 * library of stations, which is what Android Auto and other media browsers use to show and start
 * stations. Like and dislike are offered as custom buttons on the session.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val librarySession = MediaLibrarySession.Builder(this, Radio.player, LibraryCallback()).build()
        session = librarySession

        // Keep the Like button's state in step with the playing song.
        scope.launch {
            combine(Radio.track, Radio.loved) { track, loved -> track != null && track.ratingKey in loved }
                .collect { liked -> librarySession.setCustomLayout(buttons(liked)) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Prefs.keepOnClose) {
            // Swiping the app away quits it completely, as chosen in Power saving.
            Radio.quit()
            stopSelf()
            Process.killProcess(Process.myPid())
            return
        }
        if (!Radio.player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(LIKE_COMMAND)
                .add(DISLIKE_COMMAND)
                .build()
            val liked = Radio.track.value?.let { it.ratingKey in Radio.loved.value } == true
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(buttons(liked))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_LIKE -> Radio.toggleLove()
                ACTION_DISLIKE -> Radio.ban()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = if (mediaId == ROOT_ID) {
                rootItem()
            } else {
                Radio.stations.value.firstOrNull { STATION_PREFIX + it.token == mediaId }?.let(::stationItem)
            }
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                // If the app was just started, give the saved login a moment to finish so the car
                // doesn't see an empty list.
                withTimeoutOrNull(LOGIN_WAIT_MS) { Radio.restoring.first { !it } }
                val items = if (parentId == ROOT_ID) stationItems() else emptyList()
                result.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return result
        }

        /**
         * A browsed station arrives as a bare media item. Starting it goes through [Radio.play], which
         * fetches the playlist and fills the queue itself, so hand the session nothing to set.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val id = mediaItems.firstOrNull()?.mediaId
            if (id != null && id.startsWith(STATION_PREFIX)) {
                Radio.stations.value.firstOrNull { STATION_PREFIX + it.token == id }?.let { Radio.play(it) }
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET),
                )
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        /** A play button press with nothing loaded (for example from the car) resumes the last station. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Radio.resumeLastStation()
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET),
            )
        }
    }

    private fun buttons(liked: Boolean): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder()
            .setDisplayName("Dislike")
            .setIconResId(R.drawable.ic_dislike)
            .setSessionCommand(DISLIKE_COMMAND)
            .build(),
        CommandButton.Builder()
            .setDisplayName(if (liked) "Remove like" else "Like")
            .setIconResId(if (liked) R.drawable.ic_like_on else R.drawable.ic_like)
            .setSessionCommand(LIKE_COMMAND)
            .build(),
    )

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(getString(R.string.app_name))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private fun stationItem(s: Station): MediaItem = MediaItem.Builder()
        .setMediaId(STATION_PREFIX + s.token)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(s.name)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setArtworkUri(s.artUrl?.let(Uri::parse))
                .build(),
        )
        .build()

    /** Pinned stations first, then QuickMix and Thumbprint, then Pandora's order; cars cap list length. */
    private fun stationItems(): List<MediaItem> {
        val pinned = Prefs.pinned
        return Radio.stations.value
            .sortedWith(compareBy({ it.token !in pinned }, { !it.isSpecial }))
            .take(MAX_STATIONS)
            .map(::stationItem)
    }

    private companion object {
        const val ROOT_ID = "root"
        const val STATION_PREFIX = "station:"
        const val MAX_STATIONS = 100
        const val LOGIN_WAIT_MS = 20_000L
        const val ACTION_LIKE = "app.einkradio.LIKE"
        const val ACTION_DISLIKE = "app.einkradio.DISLIKE"
        val LIKE_COMMAND = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
        val DISLIKE_COMMAND = SessionCommand(ACTION_DISLIKE, Bundle.EMPTY)
    }
}
