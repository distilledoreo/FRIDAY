package com.localfirst.assistant.voice

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.localfirst.assistant.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class VoiceForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { onStopVoice?.invoke(); stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("voice", "Voice session", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, VoiceForegroundService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "voice").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Assistant voice is active").setContentText("Listening and speaking while you use other apps")
            .setContentIntent(open).setOngoing(true).addAction(0, "Stop voice", stop).build()
        ServiceCompat.startForeground(this, 901, notification,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        active.value = true
        return START_NOT_STICKY
    }
    override fun onDestroy() { active.value = false; onStopVoice?.invoke(); onStopVoice = null; super.onDestroy() }
    companion object {
        val active = MutableStateFlow(false)
        var onStopVoice: (() -> Unit)? = null
        suspend fun start(context: Context, stop: () -> Unit) {
            onStopVoice = stop
            context.startForegroundService(Intent(context, VoiceForegroundService::class.java))
            withTimeout(5000) { active.first { it } }
        }
        fun stop(context: Context) { onStopVoice = null; context.stopService(Intent(context, VoiceForegroundService::class.java)) }
    }
}
