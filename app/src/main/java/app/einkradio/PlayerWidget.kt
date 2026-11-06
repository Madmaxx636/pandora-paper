package app.einkradio

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The home-screen widget: artwork, title, and Dislike, Replay, Play/Pause, Skip and Like. */
class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = PlayerWidgets.update(context)
}

/** Handles taps on the widget's buttons. */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PlayerWidgets.ACT_PLAY ->
                if (Radio.track.value != null) Radio.togglePlay() else context.startActivity(openApp(context))
            PlayerWidgets.ACT_SKIP -> Radio.skip()
            PlayerWidgets.ACT_REPLAY -> Radio.replay()
            PlayerWidgets.ACT_LIKE -> Radio.toggleLove()
            PlayerWidgets.ACT_DISLIKE -> Radio.ban()
        }
    }

    private fun openApp(context: Context) = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(MainActivity.EXTRA_OPEN_PLAYING, true)
}

/** Keeps the widget in step with the player. Black on white in the White theme, white on dark otherwise. */
object PlayerWidgets {
    const val ACT_PLAY = "app.einkradio.W_PLAY"
    const val ACT_SKIP = "app.einkradio.W_SKIP"
    const val ACT_REPLAY = "app.einkradio.W_REPLAY"
    const val ACT_LIKE = "app.einkradio.W_LIKE"
    const val ACT_DISLIKE = "app.einkradio.W_DISLIKE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** Redraws the widget whenever the song, play state or like changes. Called once at startup. */
    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            combine(Radio.track, Radio.isPlaying, Radio.loved, Radio.station) { _, _, _, _ -> }
                .collect { update(app) }
        }
    }

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, WidgetActions::class.java).setAction(action).setData(Uri.parse("einkradio://widget/$code")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun update(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(ComponentName(app, PlayerWidget::class.java))
        if (ids.isEmpty()) return
        job?.cancel()
        job = scope.launch {
            delay(300) // a skip changes several things in a row; draw once
            val track = Radio.track.value
            val station = Radio.station.value
            val playing = Radio.isPlaying.value
            val liked = track != null && track.ratingKey in Radio.loved.value
            val light = Prefs.theme == Prefs.THEME_WHITE
            val ink = if (light) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            val cover = track?.let { t -> withContext(Dispatchers.IO) { coverFor(app, t.artUrl ?: t.artUrls.firstOrNull(), Prefs.eink) } }

            val views = RemoteViews(app.packageName, R.layout.widget_player)
            views.setInt(R.id.w_root, "setBackgroundResource", if (light) R.drawable.w_bg_light else R.drawable.w_bg_dark)
            views.setTextViewText(R.id.w_title, track?.title ?: "Nothing playing")
            views.setTextViewText(
                R.id.w_sub,
                if (track == null) "Tap play to open Pandora Paper"
                else listOfNotNull(track.artist.takeIf { it.isNotBlank() }, station?.name).joinToString(" · "),
            )
            views.setTextColor(R.id.w_title, ink)
            views.setTextColor(R.id.w_sub, ink)
            if (cover != null) {
                views.setImageViewBitmap(R.id.w_art, cover)
                views.setInt(R.id.w_art, "setColorFilter", 0)
            } else {
                views.setImageViewResource(R.id.w_art, R.drawable.ic_w_note)
                views.setInt(R.id.w_art, "setColorFilter", ink)
            }
            views.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            views.setImageViewResource(R.id.w_like, if (liked) R.drawable.ic_like_on else R.drawable.ic_like)
            for (id in intArrayOf(R.id.w_dislike, R.id.w_replay, R.id.w_play, R.id.w_next, R.id.w_like)) {
                views.setInt(id, "setColorFilter", ink)
            }
            views.setViewVisibility(R.id.w_dislike, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.w_dislike, broadcast(app, ACT_DISLIKE, 1))
            views.setOnClickPendingIntent(R.id.w_replay, broadcast(app, ACT_REPLAY, 2))
            views.setOnClickPendingIntent(R.id.w_play, broadcast(app, ACT_PLAY, 3))
            views.setOnClickPendingIntent(R.id.w_next, broadcast(app, ACT_SKIP, 4))
            views.setOnClickPendingIntent(R.id.w_like, broadcast(app, ACT_LIKE, 5))
            // Tapping the artwork or the title opens the app on the Playing tab.
            val open = PendingIntent.getActivity(
                app, 6,
                Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYING, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.w_art, open)
            views.setOnClickPendingIntent(R.id.w_title, open)
            views.setOnClickPendingIntent(R.id.w_sub, open)
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }

    /** The song's art as a rounded square, in greys on the monochrome themes. Null if it can't be loaded. */
    private fun coverFor(context: Context, url: String?, grey: Boolean): Bitmap? {
        url ?: return null
        val id = ArtCache.uriFor(context, url).lastPathSegment ?: return null
        val file = ArtCache.fileFor(context, id) ?: return null
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 } // 512 px down to 256
        val src = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val px = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), px * 0.2f, px * 0.2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        if (grey) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
