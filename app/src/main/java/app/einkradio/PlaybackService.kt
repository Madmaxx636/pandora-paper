package app.einkradio

import android.content.Intent
import android.os.Process
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Exposes [Radio.player] as a media session: notification, lock screen and Bluetooth controls. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, Radio.player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
        session?.release()
        session = null
        super.onDestroy()
    }
}
