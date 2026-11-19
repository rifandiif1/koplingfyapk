package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.playback.KoplingfyPlaybackService

class KoplingfyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val title = prefs.getString(KEY_TITLE, "").orEmpty()
        val artist = prefs.getString(KEY_ARTIST, "").orEmpty()
        val isPlaying = prefs.getBoolean(KEY_PLAYING, false)

        for (widgetId in appWidgetIds) {
            val views = buildRemoteViews(context, title, artist, isPlaying)
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val serviceAction = when (intent.action) {
            ACTION_WIDGET_PLAY_PAUSE -> KoplingfyPlaybackService.ACTION_TOGGLE_PLAY_PAUSE
            ACTION_WIDGET_NEXT -> KoplingfyPlaybackService.ACTION_NEXT
            ACTION_WIDGET_PREV -> KoplingfyPlaybackService.ACTION_PREV
            else -> null
        }
        if (serviceAction != null) {
            val svcIntent = Intent(context, KoplingfyPlaybackService::class.java).apply {
                action = serviceAction
            }
            runCatching { context.startService(svcIntent) }
        }
    }

    companion object {
        private const val PREFS_NAME = "koplingfy_widget_state"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_PLAYING = "is_playing"

        const val ACTION_WIDGET_PLAY_PAUSE = "com.developer.koplingfy.WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT = "com.developer.koplingfy.WIDGET_NEXT"
        const val ACTION_WIDGET_PREV = "com.developer.koplingfy.WIDGET_PREV"

        fun updateAllWidgets(
            context: Context,
            title: String,
            artist: String,
            artworkUrl: String?,
            isPlaying: Boolean
        ) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_TITLE, title)
                .putString(KEY_ARTIST, artist)
                .putBoolean(KEY_PLAYING, isPlaying)
                .apply()

            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, KoplingfyWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return

            val views = buildRemoteViews(context, title, artist, isPlaying)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildRemoteViews(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_koplingfy_player)
            views.setTextViewText(
                R.id.widget_title,
                title.ifBlank { context.getString(R.string.no_track_playing) }
            )
            views.setTextViewText(
                R.id.widget_artist,
                artist.ifBlank { context.getString(R.string.tap_to_explore) }
            )
            views.setImageViewResource(
                R.id.widget_btn_play_pause,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )

            // Open app on widget click
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_now_playing", true)
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 2001, openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            views.setOnClickPendingIntent(R.id.widget_btn_play_pause, broadcastIntent(context, ACTION_WIDGET_PLAY_PAUSE, 2002))
            views.setOnClickPendingIntent(R.id.widget_btn_next, broadcastIntent(context, ACTION_WIDGET_NEXT, 2003))
            views.setOnClickPendingIntent(R.id.widget_btn_prev, broadcastIntent(context, ACTION_WIDGET_PREV, 2004))
            return views
        }

        private fun broadcastIntent(context: Context, action: String, reqCode: Int): PendingIntent {
            val intent = Intent(context, KoplingfyWidgetProvider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
