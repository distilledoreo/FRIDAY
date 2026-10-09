package com.localfirst.assistant.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.localfirst.assistant.MainActivity
import com.localfirst.assistant.phone.PermissionBroker
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Opt-in "Hey FRIDAY" listener. A foreground service so the mic stays allowed;
 * it runs Android's own speech recognizer in a loop and watches transcripts
 * for the wake phrase, then opens a fresh voice chat. Nothing is recorded or
 * sent anywhere: only the two-word match leaves the recognizer. Enable it in
 * Settings; it uses noticeably more battery while it runs.
 */
class WakeWordService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var stopped = false
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (active.value) return START_STICKY
        if (!PermissionBroker.isGranted(this, Manifest.permission.RECORD_AUDIO) ||
            !SpeechRecognizer.isRecognitionAvailable(this)
        ) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Hey FRIDAY", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 5, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 6, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for “Hey FRIDAY”").setContentText("Say the wake phrase to start a voice chat")
            .setOngoing(true).setContentIntent(open).addAction(0, "Stop", stop).build()
        ServiceCompat.startForeground(this, 903, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        active.value = true
        listen()
        return START_STICKY
    }

    private fun listen() {
        if (stopped || recognizer != null) return
        val current = if (SpeechRecognizer.isRecognitionAvailable(this)) SpeechRecognizer.createSpeechRecognizer(this) else null
        if (current == null) { retry(); return }
        recognizer = current
        current.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::heard)
            }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(::heard)
                restart()
            }
            override fun onError(error: Int) = restart()
        })
        runCatching {
            current.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            })
        }.onFailure { restart() }
    }

    private fun heard(text: String) {
        if (stopped || !com.localfirst.assistant.voice.WakeWord.isWakeWord(text)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTrigger < TRIGGER_COOLDOWN_MS) return
        lastTrigger = now
        startActivity(Intent(this, MainActivity::class.java).setAction(WAKE_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun restart() {
        runCatching { recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
        retry()
    }

    private fun retry() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ listen() }, RETRY_DELAY_MS)
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
        active.value = false
        super.onDestroy()
    }

    companion object {
        const val WAKE_ACTION = "com.localfirst.assistant.WAKE_WORD"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "wake"
        private const val RETRY_DELAY_MS = 1_000L
        private const val TRIGGER_COOLDOWN_MS = 15_000L
        val active = MutableStateFlow(false)
        fun start(context: Context) { context.startForegroundService(Intent(context, WakeWordService::class.java)) }
        fun stop(context: Context) { active.value = false; context.stopService(Intent(context, WakeWordService::class.java)) }
    }
}
