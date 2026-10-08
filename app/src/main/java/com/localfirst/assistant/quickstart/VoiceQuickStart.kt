package com.localfirst.assistant.quickstart

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.localfirst.assistant.MainActivity
import com.localfirst.assistant.R

private fun voiceIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
    action = Intent.ACTION_ASSIST
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
}
private fun voicePendingIntent(context: Context) = PendingIntent.getActivity(
    context, 902, voiceIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

class VoiceWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val view = RemoteViews(context.packageName, R.layout.voice_widget)
            view.setOnClickPendingIntent(R.id.voice_start, voicePendingIntent(context))
            manager.updateAppWidget(id, view)
        }
    }
}

class VoiceTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { label = "FRIDAY voice"; state = Tile.STATE_INACTIVE; updateTile() }
    }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        // Permission requests and microphone startup run visibly in the activity.
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(voicePendingIntent(this))
            else startActivityAndCollapse(voiceIntent(this))
        }
    }
}
