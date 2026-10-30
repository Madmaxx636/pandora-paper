package app.einkradio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
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
        setMediaNotificationProvider(RadioNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_stat_radio) })
        val librarySession = MediaLibrarySession.Builder(this, RadioPlayer(Radio.player), LibraryCallback()).build()
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

    /**
     * What the notification, lock screen, car and launchers see of the player. A radio can't go back to
     * an earlier song, rewind or seek, so those controls are removed; skip is always offered while a
     * station is loaded, and goes through [Radio.skip] so a short queue gets refilled.
     */
    private class RadioPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands {
            val builder = super.getAvailableCommands().buildUpon().removeAll(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
            )
            if (Radio.station.value != null) {
                builder.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            return builder.build()
        }

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun seekToNext() = Radio.skip()
        override fun seekToNextMediaItem() = Radio.skip()
        override fun seekToPrevious() = Unit
        override fun seekToPreviousMediaItem() = Unit
        override fun seekBack() = Unit
        override fun seekForward() = Unit
    }

    /**
     * Notification buttons in a radio order: Dislike, Play/Pause, Skip, Like. The default puts the custom
     * buttons after the transport buttons, which leaves Dislike out of the collapsed view.
     */
    private class RadioNotificationProvider(context: Context) : DefaultMediaNotificationProvider(context) {
        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            customLayout: ImmutableList<CommandButton>,
            showPauseButton: Boolean,
        ): ImmutableList<CommandButton> {
            val all = super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
            val dislike = all.firstOrNull { it.sessionCommand?.customAction == ACTION_DISLIKE }
            val like = all.firstOrNull { it.sessionCommand?.customAction == ACTION_LIKE }
            val replay = all.firstOrNull { it.sessionCommand?.customAction == ACTION_REPLAY }
            val transport = all.filter { it.sessionCommand == null }
            return ImmutableList.builder<CommandButton>().apply {
                dislike?.let(::add)
                replay?.let(::add)
                addAll(transport)
                like?.let(::add)
            }.build()
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(LIKE_COMMAND)
                .add(DISLIKE_COMMAND)
                .add(REPLAY_COMMAND)
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
                ACTION_REPLAY -> Radio.replay()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            // When the car asks for the "recent" root, it is asking what to play right now. Answering
            // with the last station makes it open straight into the player instead of the station list.
            Futures.immediateFuture(
                if (params?.isRecent == true) LibraryResult.ofItem(rootItem(RECENT_ID), params)
                else LibraryResult.ofItem(rootItem(ROOT_ID), rootParams()),
            )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = if (mediaId == ROOT_ID || mediaId == RECENT_ID) {
                rootItem(mediaId)
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
                val items = when (parentId) {
                    ROOT_ID -> stationItems()
                    RECENT_ID -> recentItems()
                    else -> emptyList()
                }
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
            .setDisplayName("Replay")
            .setIconResId(R.drawable.ic_replay)
            .setSessionCommand(REPLAY_COMMAND)
            .build(),
        CommandButton.Builder()
            .setDisplayName(if (liked) "Remove like" else "Like")
            .setIconResId(if (liked) R.drawable.ic_like_on else R.drawable.ic_like)
            .setSessionCommand(LIKE_COMMAND)
            .build(),
    )

    /** The station that is playing, or was last played: the single item the car should start. */
    private fun recentItems(): List<MediaItem> {
        val token = Radio.station.value?.token ?: Prefs.lastStation ?: return emptyList()
        return Radio.stations.value.firstOrNull { it.token == token }?.let { listOf(stationItem(it)) }
            ?: emptyList()
    }

    private fun rootItem(id: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(getString(R.string.app_name))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    /** Ask the car to show a plain list, which reads best for station names. */
    private fun rootParams(): LibraryParams = LibraryParams.Builder()
        .setExtras(
            Bundle().apply {
                putBoolean(STYLE_SUPPORTED, true)
                putInt(STYLE_PLAYABLE, STYLE_LIST)
                putInt(STYLE_BROWSABLE, STYLE_LIST)
            },
        )
        .build()

    /** [group] becomes a heading above the first station of that group in the car's list. */
    private fun stationItem(s: Station, group: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(STATION_PREFIX + s.token)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(s.name)
                .setSubtitle(
                    when {
                        s.isQuickMix -> "A mix of your stations"
                        s.isThumbprint -> "Built from your thumbs"
                        s.isShared -> "Shared station"
                        else -> null
                    },
                )
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setArtworkUri(s.artUrl?.let(Uri::parse))
                .setExtras(Bundle().apply { if (group != null) putString(STYLE_GROUP_TITLE, group) })
                .build(),
        )
        .build()

    /**
     * One plain list: pinned stations under a "Pinned" heading (only when there are any), then the rest
     * under "Stations", with QuickMix and Thumbprint first. Cars cap list length.
     */
    private fun stationItems(): List<MediaItem> {
        val pinned = Prefs.pinned
        val ordered = Radio.stations.value.sortedWith(compareBy({ !it.isSpecial }))
        val (pins, rest) = ordered.partition { it.token in pinned }
        val grouped = pins.isNotEmpty()
        return (pins.map { stationItem(it, "Pinned") } +
            rest.map { stationItem(it, if (grouped) "Stations" else null) })
            .take(MAX_STATIONS)
    }

    private companion object {
        const val ROOT_ID = "root"
        const val RECENT_ID = "recent"
        const val STATION_PREFIX = "station:"
        const val MAX_STATIONS = 100

        // Android Auto's content-style hints for browse lists (values: 1 = list, 2 = grid).
        const val STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val STYLE_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        const val STYLE_LIST = 1
        const val LOGIN_WAIT_MS = 20_000L
        const val ACTION_LIKE = "app.einkradio.LIKE"
        const val ACTION_DISLIKE = "app.einkradio.DISLIKE"
        const val ACTION_REPLAY = "app.einkradio.REPLAY"
        val LIKE_COMMAND = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
        val DISLIKE_COMMAND = SessionCommand(ACTION_DISLIKE, Bundle.EMPTY)
        val REPLAY_COMMAND = SessionCommand(ACTION_REPLAY, Bundle.EMPTY)
    }
}
