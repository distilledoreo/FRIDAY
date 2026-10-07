package com.localfirst.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.localfirst.assistant.phone.PermissionBroker
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Voice through the computer: Parakeet v3 speech-to-text and Kokoro
 * text-to-speech on the desktop assistant API, with the phone doing capture,
 * endpointing, echo cancellation, and playback.
 */
fun computerVoiceIo(
    context: Context,
    client: ComputerVoiceClient,
    voiceName: String,
    bargeInEnabled: () -> Boolean,
): VoiceIo {
    val app = context.applicationContext
    val audio = DuplexAudio(app, bargeInEnabled)
    return VoiceIo(
        input = ComputerSpeechInput(audio, client),
        speaker = ComputerSpeaker(audio, client, voiceName),
        bargeIn = object : BargeInListener {
            override suspend fun awaitSpeech() {
                audio.onsets.first()
            }
        },
        focus = audio,
        ensureMicrophone = { PermissionBroker.ensure(app, Manifest.permission.RECORD_AUDIO) },
    )
}

/** HTTP client for /voice, /transcribe and /speak on the desktop assistant API. */
class ComputerVoiceClient(private val baseUrl: String, private val apiKey: String?) {
    suspend fun available(): Boolean = withContext(Dispatchers.IO) {
        runCatching { request("GET", "/voice", timeoutMs = 8_000).let { (code, _) -> code == 200 } }.getOrDefault(false)
    }

    suspend fun transcribe(pcm16k: ShortArray): String = withContext(Dispatchers.IO) {
        val (code, body) = request("POST", "/transcribe", Wav.encode(pcm16k, 16_000), "audio/wav", timeoutMs = 30_000)
        if (code != 200) throw IOException("transcription failed (HTTP $code)")
        JSONObject(String(body)).optString("text").trim()
    }

    suspend fun speak(text: String, voice: String): Wav.Decoded = withContext(Dispatchers.IO) {
        val json = JSONObject().put("text", text).put("voice", voice).toString()
        val (code, body) = request("POST", "/speak", json.toByteArray(), "application/json", timeoutMs = 30_000)
        if (code != 200) throw IOException("speech synthesis failed (HTTP $code)")
        Wav.decode(body)
    }

    private fun request(method: String, path: String, body: ByteArray? = null, type: String? = null, timeoutMs: Int): Pair<Int, ByteArray> {
        val url = URI(baseUrl.trim().trimEnd('/') + path).toURL()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = timeoutMs
            apiKey?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", type)
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            body?.let { b -> connection.outputStream.use { it.write(b) } }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return code to (stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Full-duplex audio for voice mode. While active, the phone is in
 * communication mode (like a speakerphone call) so the hardware echo canceller
 * removes the assistant's own voice from the mic. One mic stream runs the whole
 * time and is cut into utterances by [Endpointer]; the assistant's voice plays
 * through a voice-communication AudioTrack.
 */
class DuplexAudio(
    private val context: Context,
    private val captureWhilePlaying: () -> Boolean,
) : VoiceAudioFocus {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var track: AudioTrack? = null
    private val endpointer = Endpointer()
    private var previousMode = AudioManager.MODE_NORMAL
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes())
        .build()

    /** Speech onsets, for barge-in. */
    val onsets = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Finished utterances (16 kHz PCM16) with the time they started. */
    val utterances = Channel<Pair<ShortArray, Long>>(capacity = 4)

    val level = MutableStateFlow(0f)
    val speechInProgress = MutableStateFlow(false)

    @Volatile var playing = false
        private set

    @SuppressLint("MissingPermission")
    override fun acquire() {
        if (captureJob?.isActive == true) return
        if (!PermissionBroker.isGranted(context, Manifest.permission.RECORD_AUDIO)) return
        audio.requestAudioFocus(focusRequest)
        previousMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        routeOutput()
        captureJob = scope.launch { capture() }
    }

    override fun release() {
        captureJob?.cancel()
        captureJob = null
        stopPlayback()
        track?.release()
        track = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.mode = previousMode
        audio.abandonAudioFocusRequest(focusRequest)
        endpointer.reset()
        while (utterances.tryReceive().isSuccess) Unit
        speechInProgress.value = false
        level.value = 0f
    }

    /** Plays 16-bit mono PCM; returns when it has finished playing or when cancelled. */
    suspend fun play(audioData: Wav.Decoded) = withContext(Dispatchers.IO) {
        val out = trackFor(audioData.sampleRate)
        playing = true
        endpointer.playing = true
        try {
            if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
            val startHead = out.playbackHeadPosition.toLong() and 0xffffffffL
            var offset = 0
            val chunk = audioData.sampleRate / 10
            while (offset < audioData.pcm.size) {
                ensureActive()
                val n = out.write(audioData.pcm, offset, minOf(chunk, audioData.pcm.size - offset))
                if (n <= 0) break
                offset += n
            }
            // Wait until the written audio has actually come out of the speaker.
            while (isActive) {
                val played = (out.playbackHeadPosition.toLong() and 0xffffffffL) - startHead
                if (played >= offset) break
                delay(20)
            }
        } finally {
            playing = false
            endpointer.playing = false
        }
    }

    fun stopPlayback() {
        track?.let {
            runCatching {
                it.pause()
                it.flush()
            }
        }
        playing = false
        endpointer.playing = false
    }

    @SuppressLint("MissingPermission")
    private suspend fun capture() {
        val rate = 16_000
        val frame = rate / 50 // 20 ms
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, frame * 2 * 10),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }
        val echo = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recorder.audioSessionId) else null
        val noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recorder.audioSessionId) else null
        echo?.enabled = true
        noise?.enabled = true
        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val buffer = ShortArray(frame)
                var filled = 0
                while (filled < frame) {
                    val n = recorder.read(buffer, filled, frame - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled < frame) continue
                if (playing && !captureWhilePlaying()) {
                    // Barge-in is off: ignore the mic while the assistant talks.
                    endpointer.reset()
                    level.value = 0f
                    continue
                }
                val event = endpointer.accept(buffer)
                level.value = endpointer.level
                when (event) {
                    Endpointer.Event.Onset -> {
                        speechInProgress.value = true
                        onsets.tryEmit(Unit)
                    }
                    is Endpointer.Event.Utterance -> {
                        speechInProgress.value = false
                        utterances.trySend(event.pcm to System.currentTimeMillis() - event.pcm.size / 16)
                    }
                    Endpointer.Event.Discarded -> speechInProgress.value = false
                    null -> Unit
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            echo?.release()
            noise?.release()
        }
    }

    private fun trackFor(rate: Int): AudioTrack {
        track?.takeIf { it.sampleRate == rate }?.let { return it }
        track?.release()
        val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuffer, rate / 2))
            .build()
            .also { track = it }
    }

    /** Headset or Bluetooth if connected, otherwise the loudspeaker (not the earpiece). */
    private fun routeOutput() {
        val headsets = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setOf(AudioDeviceInfo.TYPE_BLE_HEADSET) else emptySet()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audio.availableCommunicationDevices
            val pick = devices.firstOrNull { it.type in headsets }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            pick?.let { audio.setCommunicationDevice(it) }
        } else {
            val headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsets }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = !headset
        }
    }

    private fun attributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}

/** Waits for the next utterance from [DuplexAudio] and transcribes it with Parakeet on the computer. */
class ComputerSpeechInput(
    private val audio: DuplexAudio,
    private val client: ComputerVoiceClient,
) : SpeechInput {
    override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): ListenResult = coroutineScope {
        val levels = launch { audio.level.collect(onLevel) }
        try {
            val pcm = nextUtterance() ?: return@coroutineScope ListenResult.Silence
            onPartial("…")
            val text = client.transcribe(pcm)
            if (text.isBlank()) ListenResult.Silence else ListenResult.Heard(text)
        } catch (e: IOException) {
            ListenResult.Failed("Couldn't reach the voice service on your computer.")
        } finally {
            levels.cancel()
        }
    }

    /**
     * The next utterance, or null after 8 s with nobody talking. Utterances that
     * started more than 3 s before we asked are stray noise from between turns
     * and are dropped; a barge-in that started just before is kept.
     */
    private suspend fun nextUtterance(): ShortArray? {
        val asked = System.currentTimeMillis()
        while (true) {
            val next = withTimeoutOrNull(250) { audio.utterances.receive() }
            if (next != null) {
                if (next.second >= asked - 3_000) return next.first
                continue
            }
            val waited = System.currentTimeMillis() - asked
            if ((waited > 8_000 && !audio.speechInProgress.value) || waited > 45_000) return null
        }
    }
}

/**
 * Speaks with Kokoro on the computer. Each sentence is synthesized as soon as
 * it's queued, so the next one is usually ready before the current one ends.
 */
class ComputerSpeaker(
    private val audio: DuplexAudio,
    private val client: ComputerVoiceClient,
    private val voiceName: String,
) : Speaker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queue = Channel<Deferred<Wav.Decoded?>>(Channel.UNLIMITED)
    private var player: Job? = null
    private val pending = MutableStateFlow(0)

    override suspend fun prepare(): Boolean = client.available()

    override fun enqueue(text: String) {
        if (text.isBlank()) return
        pending.value++
        val audioFor = scope.async { runCatching { client.speak(text, voiceName) }.getOrNull() }
        queue.trySend(audioFor)
        if (player?.isActive != true) player = scope.launch { playLoop(queue) }
    }

    private suspend fun playLoop(source: Channel<Deferred<Wav.Decoded?>>) {
        for (next in source) {
            try {
                next.await()?.let { audio.play(it) }
            } finally {
                pending.value = (pending.value - 1).coerceAtLeast(0)
            }
        }
    }

    override suspend fun awaitIdle() {
        pending.first { it == 0 }
    }

    override fun stop() {
        player?.cancel()
        player = null
        queue.close()
        while (true) {
            val d = queue.tryReceive().getOrNull() ?: break
            d.cancel()
        }
        queue = Channel(Channel.UNLIMITED)
        audio.stopPlayback()
        pending.value = 0
    }

    override fun shutdown() = stop()
}
