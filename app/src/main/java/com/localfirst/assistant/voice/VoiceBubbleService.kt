package com.localfirst.assistant.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.localfirst.assistant.AssistantApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hosts the floating voice bubble while voice mode runs with the app in the
 * background. It needs the "display over other apps" permission; without it
 * this service never starts, and voice behaves as before — the notification
 * can still control the session.
 */
class VoiceBubbleService : Service() {
    private var scope: CoroutineScope? = null
    private var bubble: VoiceBubble? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (!canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val viewModel = (applicationContext as AssistantApp).chat()
        if (bubble == null) {
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            scope = newScope
            bubble = VoiceBubble(this, newScope).apply {
                onMuteMic = { viewModel.toggleMicMute() }
                onResume = { viewModel.startVoice() }
                onEnd = { viewModel.closeVoice() }
                viewModel.voiceState.value?.let(::update)
                newScope.launch { viewModel.voiceState.collect { state -> if (state == null) stopSelf() else update(state) } }
            }
        }
        bubble?.show()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        bubble?.close()
        bubble = null
        scope = null
        super.onDestroy()
    }

    companion object {
        fun canDrawOverlays(context: Context) =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                android.provider.Settings.canDrawOverlays(context) else true

        /** True when the bubble is (or stays) showing; false when the OS refuses a background start. */
        fun start(context: Context): Boolean {
            if (!canDrawOverlays(context)) return false
            // Leaving the app lands here after the foreground grace period may have
            // ended; a refusal just means the caller keeps the previous behavior.
            return runCatching {
                context.startService(Intent(context, VoiceBubbleService::class.java))
                true
            }.getOrDefault(false)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceBubbleService::class.java))
        }
    }
}
