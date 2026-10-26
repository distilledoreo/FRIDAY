package com.localfirst.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opt-in "Hey FRIDAY" listener. A foreground service so the mic stays allowed;
 * it runs a small wake-phrase model ([WakeWordDetector]) on the phone and opens
 * a fresh voice chat when it hears the phrase. Audio never leaves the phone and
 * nothing is recorded or kept. Enable it in Settings.
 *
 * An open microphone keeps the phone from sleeping, so it is only open when the
 * phrase is the way to reach FRIDAY: while the phone is locked or its screen is
 * off (in use, a button is quicker), outside the Dashboard's quiet hours, and
 * while the proximity sensor is uncovered (not in a pocket or face down). It
 * also steps off while a voice chat holds the mic, and for a cooldown after
 * each wake.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var generation = 0
    /** The phone is on and unlocked: someone is using it and can summon FRIDAY with a button. */
    private val inUse = MutableStateFlow(false)
    private val pocket = Pocket()
    /** One read of six 80 ms frames. */
    private val batch = ShortArray(WakeWordDetector.FRAME * 6)
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { inUse.value = phoneInUse() }
    }

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
        inUse.value = phoneInUse()
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { listen() }
        return START_STICKY
    }

    private fun buildNotification(): android.app.Notification {
        val stop = PendingIntent.getService(this, 5, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 6, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("“Hey FRIDAY” is on")
            .setContentText("Listens on this phone while it's locked, outside quiet hours and out of your pocket")
            .setOngoing(true).setContentIntent(open).addAction(0, "Stop", stop).build()
    }

    private suspend fun listen() {
        val detector = runCatching {
            WakeWordDetector(model("melspectrogram.onnx"), model("embedding_model.onnx"), model("hey_friday.onnx"))
        }.getOrElse { withContext(Dispatchers.Main) { stopSelf() }; return }
        val blocked = combine(inUse, VoiceForegroundService.active, pocket.covered) { using, voice, covered -> using || voice || covered }
        try {
            var resumeAt = 0L
            while (scope.isActive) {
                // Quiet hours: nothing runs, not even the proximity sensor; checked again at least every few minutes.
                val quiet = WakeQuietHours.remaining(this)
                if (quiet > 0) { pocket.stop(); delay(minOf(quiet, RECHECK_MS)); continue }
                if (inUse.value) pocket.stop() else pocket.start()
                if (blocked.first()) { withTimeoutOrNull(RECHECK_MS) { blocked.first { !it } }; continue }
                val wait = resumeAt - SystemClock.elapsedRealtime()
                if (wait > 0) { delay(minOf(wait, RECHECK_MS)); continue }
                if (record(detector)) {
                    trigger()
                    resumeAt = SystemClock.elapsedRealtime() + TRIGGER_COOLDOWN_MS
                } else if (!paused()) {
                    delay(RETRY_DELAY_MS)
                }
            }
        } finally {
            pocket.stop()
            detector.close()
        }
    }

    private fun model(name: String) = assets.open("wakeword/$name").use { it.readBytes() }

    private fun phoneInUse(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Something that should close the mic: the phone in use, a voice chat, a covered sensor or quiet hours. */
    private fun paused() = inUse.value || VoiceForegroundService.active.value || pocket.covered.value || WakeQuietHours.remaining(this) > 0

    /**
     * The proximity sensor, covered in a pocket or face down on a table. Android's default proximity sensor wakes the
     * phone when it changes, so listening resumes as soon as the phone is uncovered, without the mic open meanwhile.
     */
    private inner class Pocket : SensorEventListener {
        val covered = MutableStateFlow(false)
        private var sensor: Sensor? = null

        fun start() {
            if (sensor != null) return
            val manager = getSystemService(SensorManager::class.java) ?: return
            val proximity = manager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: manager.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
            sensor = proximity
            manager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL)
        }

        fun stop() {
            sensor ?: return
            getSystemService(SensorManager::class.java)?.unregisterListener(this)
            sensor = null
            covered.value = false
        }

        override fun onSensorChanged(event: SensorEvent) { covered.value = event.values[0] < event.sensor.maximumRange }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Records until the phrase is heard (true), or something pauses listening, or the mic fails (false). */
    @SuppressLint("MissingPermission")
    private suspend fun record(detector: WakeWordDetector): Boolean = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return@withContext false
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, batch.size * 2 * 2))
        }.getOrNull() ?: return@withContext false
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return@withContext false
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext false
            detector.reset()
            val frame = ShortArray(WakeWordDetector.FRAME)
            // Half a second per read rather than 80 ms: the thread wakes about twice a second, not 12 times.
            while (isActive && !paused()) {
                var filled = 0
                while (filled < batch.size) {
                    val read = recorder.read(batch, filled, batch.size - filled)
                    if (read <= 0) return@withContext false
                    filled += read
                }
                for (start in batch.indices step WakeWordDetector.FRAME) {
                    batch.copyInto(frame, 0, start, start + WakeWordDetector.FRAME)
                    if (detector.accept(frame)) return@withContext true
                }
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
        runCatching { unregisterReceiver(screen) }
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
        /** How often a paused listener looks again, in case quiet hours or the phone's state changed unnoticed. */
        private const val RECHECK_MS = 5 * 60_000L
        @Volatile private var runningGeneration = 0
        val active = MutableStateFlow(false)
        fun start(context: Context) { context.startForegroundService(Intent(context, WakeWordService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, WakeWordService::class.java)) }
    }
}
