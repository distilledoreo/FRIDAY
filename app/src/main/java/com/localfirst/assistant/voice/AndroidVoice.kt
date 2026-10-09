package com.localfirst.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.localfirst.assistant.phone.PermissionBroker
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

fun androidVoiceIo(context: Context): VoiceIo {
    val app = context.applicationContext
    return VoiceIo(
        input = AndroidSpeechInput(app),
        speaker = AndroidSpeaker(app),
        bargeIn = AndroidBargeInListener(app),
        focus = AndroidVoiceAudioFocus(app),
        ensureMicrophone = { PermissionBroker.ensure(app, Manifest.permission.RECORD_AUDIO) },
    )
}

/**
 * Android's SpeechRecognizer. Prefers the on-device recognizer (audio stays on
 * the phone) and falls back to the default recognition service, asking it to
 * work offline when it can.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {
    private var onDeviceUsable = true

    override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit, completeSilenceMs: Long): ListenResult =
        withContext(Dispatchers.Main) {
            val onDevice = onDeviceUsable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            val first = attempt(onDevice, onPartial, onLevel, completeSilenceMs.coerceIn(300L, 3_000L))
            if (onDevice && first is Attempt.LanguageUnavailable) {
                onDeviceUsable = false
                attempt(false, onPartial, onLevel, completeSilenceMs.coerceIn(300L, 3_000L)).result
            } else {
                first.result
            }
        }

    private sealed interface Attempt {
        val result: ListenResult

        data class Done(override val result: ListenResult) : Attempt
        data object LanguageUnavailable : Attempt {
            override val result = ListenResult.Failed("Speech recognition isn't available for this language.")
        }
    }

    private suspend fun attempt(onDevice: Boolean, onPartial: (String) -> Unit, onLevel: (Float) -> Unit, completeSilenceMs: Long): Attempt =
        suspendCancellableCoroutine { cont ->
            if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
                cont.resume(Attempt.Done(ListenResult.Failed("This phone has no speech recognition service.")))
                return@suspendCancellableCoroutine
            }
            val recognizer = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            fun finish(attempt: Attempt) {
                if (cont.isActive) cont.resume(attempt)
                recognizer.destroy()
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = onLevel(0f)
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let(onPartial)
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    finish(Attempt.Done(if (text.isNullOrEmpty()) ListenResult.Silence else ListenResult.Heard(text)))
                }

                override fun onError(error: Int) {
                    finish(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_CLIENT,
                            -> Attempt.Done(ListenResult.Silence)
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                Attempt.LanguageUnavailable
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                Attempt.Done(ListenResult.Failed("Microphone access wasn't granted."))
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                Attempt.Done(ListenResult.Failed("Speech recognition needs a connection, and no offline voice model is installed."))
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                Attempt.Done(ListenResult.Failed("The speech recognizer is busy. Try again in a moment."))
                            else -> Attempt.Done(ListenResult.Failed("Speech recognition stopped (error $error)."))
                        },
                    )
                }
            })

            cont.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post {
                    recognizer.cancel()
                    recognizer.destroy()
                }
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            }
            recognizer.startListening(intent)
        }
}

/** Text-to-speech queue. Utterances from before a [stop] are ignored when their callbacks arrive late. */
class AndroidSpeaker(private val context: Context) : Speaker {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val generation = AtomicInteger(0)
    private val pending = AtomicInteger(0)
    private val idle = MutableStateFlow(true)
    private val ids = AtomicInteger(0)

    override suspend fun prepare(): Boolean {
        val existing = ready
        if (existing != null) return existing.await()
        val created = CompletableDeferred<Boolean>()
        ready = created
        withContext(Dispatchers.Main) {
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(context) { status ->
                val ok = status == TextToSpeech.SUCCESS
                if (ok) {
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    if (engine.isLanguageAvailable(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE) {
                        engine.language = Locale.getDefault()
                    }
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) = finished(utteranceId)
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) = finished(utteranceId)
                        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
                    })
                }
                created.complete(ok)
            }
            tts = engine
        }
        val ok = created.await()
        if (!ok) ready = null
        return ok
    }

    override fun enqueue(text: String) {
        val engine = tts ?: return
        if (text.isBlank()) return
        pending.incrementAndGet()
        idle.value = false
        val id = "${generation.get()}:${ids.incrementAndGet()}"
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finished(id)
    }

    override suspend fun awaitIdle() {
        idle.first { it }
    }

    override fun stop() {
        generation.incrementAndGet()
        pending.set(0)
        idle.value = true
        tts?.stop()
    }

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = null
    }

    private fun finished(id: String?) {
        if (id?.substringBefore(':')?.toIntOrNull() != generation.get()) return
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            idle.value = true
        }
    }
}

/**
 * Listens for the user starting to talk while the assistant is speaking.
 * Records with the voice-communication source plus the platform echo canceller
 * and noise suppressor, and uses a stricter threshold on the loudspeaker.
 */
class AndroidBargeInListener(private val context: Context) : BargeInListener {
    @SuppressLint("MissingPermission")
    override suspend fun awaitSpeech() {
        if (!PermissionBroker.isGranted(context, Manifest.permission.RECORD_AUDIO)) awaitCancellation()
        withContext(Dispatchers.IO) {
            val detector = if (usingHeadset()) SpeechOnsetDetector.forHeadset() else SpeechOnsetDetector.forSpeaker()
            val rate = 16_000
            val frame = rate * 30 / 1000
            val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, frame * 2 * 8),
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                awaitCancellation()
            }
            val echo = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recorder.audioSessionId) else null
            val noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recorder.audioSessionId) else null
            echo?.enabled = true
            noise?.enabled = true
            try {
                recorder.startRecording()
                val buffer = ShortArray(frame)
                while (true) {
                    ensureActive()
                    val read = recorder.read(buffer, 0, frame)
                    if (read <= 0) continue
                    var sum = 0.0
                    for (i in 0 until read) sum += buffer[i].toDouble() * buffer[i]
                    val rms = sqrt(sum / read)
                    val dbfs = if (rms < 1.0) -90.0 else 20 * log10(rms / 32768.0)
                    if (detector.accept(dbfs)) return@withContext
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                echo?.release()
                noise?.release()
            }
        }
    }

    private fun usingHeadset(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val headsetTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setOf(AudioDeviceInfo.TYPE_BLE_HEADSET) else emptySet()
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsetTypes }
    }
}

/** Ducks other audio (such as Spotify) while voice mode listens and speaks. */
class AndroidVoiceAudioFocus(context: Context) : VoiceAudioFocus {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()
    private var held = false

    override fun acquire() {
        if (!held) held = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun release() {
        if (held) audio.abandonAudioFocusRequest(request)
        held = false
    }
}
