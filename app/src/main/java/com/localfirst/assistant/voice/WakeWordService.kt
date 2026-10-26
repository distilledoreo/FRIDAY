package com.localfirst.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.localfirst.assistant.MainActivity
import com.localfirst.assistant.phone.PermissionBroker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opt-in "Hey FRIDAY" listener. A foreground service so the mic stays allowed;
 * it runs a small wake-phrase model ([WakeWordDetector]) on the phone and opens
 * a fresh voice chat when it hears the phrase. Audio never leaves the phone and
 * nothing is recorded or kept. It steps off the microphone while a voice chat
 * holds it, and for a short cooldown after each wake. Enable it in Settings.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var generation = 0

    override fun onCreate() {
        super.onCreate()
        generation = ++runningGeneration
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (generation != runningGeneration) { stopSelf(); return START_NOT_STICKY }
        if (active.value) return START_STICKY
        if (!PermissionBroker.isGranted(this, Manifest.permission.RECORD_AUDIO)) { stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Hey FRIDAY", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 903, buildNotification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        active.value = true
        scope.launch { listen() }
        return START_STICKY
    }

    private fun buildNotification(): android.app.Notification {
        val stop = PendingIntent.getService(this, 5, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 6, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for “Hey FRIDAY” on this phone")
            .setContentText("Say the wake phrase to start a voice chat")
            .setOngoing(true).setContentIntent(open).addAction(0, "Stop", stop).build()
    }

    private suspend fun listen() {
        val detector = runCatching {
            WakeWordDetector(model("melspectrogram.onnx"), model("embedding_model.onnx"), model("hey_friday.onnx"))
        }.getOrElse { withContext(Dispatchers.Main) { stopSelf() }; return }
        try {
            var resumeAt = 0L
            while (scope.isActive) {
                VoiceForegroundService.active.first { !it }
                val wait = resumeAt - SystemClock.elapsedRealtime()
                if (wait > 0) { delay(wait); continue }
                if (record(detector)) {
                    trigger()
                    resumeAt = SystemClock.elapsedRealtime() + TRIGGER_COOLDOWN_MS
                } else {
                    delay(RETRY_DELAY_MS)
                }
            }
        } finally {
            detector.close()
        }
    }

    private fun model(name: String) = assets.open("wakeword/$name").use { it.readBytes() }

    /** Records until the phrase is heard (true), or a voice chat takes the mic, or the mic fails (false). */
    @SuppressLint("MissingPermission")
    private suspend fun record(detector: WakeWordDetector): Boolean = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return@withContext false
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, WakeWordDetector.FRAME * 2 * 4))
        }.getOrNull() ?: return@withContext false
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return@withContext false
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext false
            val frame = ShortArray(WakeWordDetector.FRAME)
            while (isActive && !VoiceForegroundService.active.value) {
                var filled = 0
                while (filled < frame.size) {
                    val read = recorder.read(frame, filled, frame.size - filled)
                    if (read <= 0) return@withContext false
                    filled += read
                }
                if (detector.accept(frame)) return@withContext true
            }
            false
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private suspend fun trigger() = withContext(Dispatchers.Main) {
        startActivity(Intent(this@WakeWordService, MainActivity::class.java).setAction(WAKE_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun onDestroy() {
        scope.cancel()
        if (generation == runningGeneration) active.value = false
        super.onDestroy()
    }

    companion object {
        const val WAKE_ACTION = "com.localfirst.assistant.WAKE_WORD"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "wake"
        private const val RETRY_DELAY_MS = 1_000L
        private const val TRIGGER_COOLDOWN_MS = 15_000L
        @Volatile private var runningGeneration = 0
        val active = MutableStateFlow(false)
        fun start(context: Context) { context.startForegroundService(Intent(context, WakeWordService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, WakeWordService::class.java)) }
    }
}
