package com.localfirst.assistant.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.localfirst.assistant.AssistantApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the app running while it answers a message from the car; with the
 * phone locked, Android would otherwise freeze it partway through the reply.
 */
class CarTurnService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Answering from the car", NotificationManager.IMPORTANCE_MIN))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Answering your message")
            .build()
        ServiceCompat.startForeground(this, 951, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        scope.coroutineContext.cancelChildren()
        scope.launch {
            delay(500)
            (application as AssistantApp).chat().state.first { !it.busy }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // Short services get about three minutes; a longer answer finishes if Android allows it.
    override fun onTimeout(startId: Int) = stopSelf()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "car_turn"

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            runCatching { context.startForegroundService(Intent(context, CarTurnService::class.java)) }
        }
    }
}
