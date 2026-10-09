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
import com.localfirst.assistant.desktop.desktopRequest
import kotlin.math.max
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.update
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
    phoneMicDevices: () -> Set<String> = { emptySet() },
): VoiceIo {
    val app = context.applicationContext
    val audio = DuplexAudio(app, bargeInEnabled, phoneMicDevices)
    return VoiceIo(
        input = ComputerSpeechInput(audio, client),
        speaker = ComputerSpeaker(audio, client, voiceName),
        bargeIn = object : BargeInListener {
            override suspend fun awaitSpeech() {
                if (!audio.echoRisk) {
                    audio.onsets.first()
                    return
                }
                // Speakers the mic can hear: the assistant's own voice can trigger an onset,
                // so check what was said before treating it as the user cutting in.
                while (audio.probes.tryReceive().isSuccess) Unit
                try {
                    while (true) {
                        val probe = audio.probes.receive()
                        val heard = if (probe.isEmpty()) "" else try {
                            client.transcribe(probe)
                        } catch (e: IOException) {
                            return // Can't check; let the user through.
                        }
                        if (!EchoFilter.isEcho(heard, audio.recentSpeech())) return
                        audio.duck(false)
                    }
                } catch (e: CancellationException) {
                    audio.duck(false)
                    throw e
                }
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

    private fun request(method: String, path: String, body: ByteArray? = null, type: String? = null, timeoutMs: Int) =
        desktopRequest(baseUrl, apiKey, method, path, body, type, timeoutMs = timeoutMs)
}

/** Bluetooth outputs, named by the device (for example, the car radio). */
internal val BLUETOOTH_OUTPUTS = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
} else {
    emptySet()
}

/** Names of the Bluetooth audio devices connected now. */
fun connectedBluetoothAudio(context: Context): List<String> =
    context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type in BLUETOOTH_OUTPUTS }
        .mapNotNull { it.productName?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()

/**
 * Full-duplex audio for voice mode. While active, the phone is in
 * communication mode (like a speakerphone call) so the hardware echo canceller
 * removes the assistant's own voice from the mic. One mic stream runs the whole
 * time and is cut into utterances by [Endpointer]; the assistant's voice plays
 * through a voice-communication AudioTrack.
 *
 * Bluetooth devices listed in [phoneMicDevices] (car radios whose mic doesn't
 * work) are used like a speaker instead: the voice plays over Bluetooth media
 * and the phone's own mic listens. There, and on wired outputs like a car's
 * aux input, the mic can hear the speakers, so [echoRisk] is set and the
 * assistant checks what it heard against what it just said.
 */
class DuplexAudio(
    private val context: Context,
    private val captureWhilePlaying: () -> Boolean,
    private val phoneMicDevices: () -> Set<String> = { emptySet() },
) : VoiceAudioFocus {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var track: AudioTrack? = null
    private val endpointer = Endpointer()
    private var previousMode = AudioManager.MODE_NORMAL
    private var usage = AudioAttributes.USAGE_VOICE_COMMUNICATION
    private var focusRequest: AudioFocusRequest? = null

    /** Speech onsets, for barge-in. */
    val onsets = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /**
     * When [echoRisk] is set: about a second of audio from each onset while the
     * assistant talks, to check whether it was the assistant's own voice. Empty
     * when the sound stopped too soon to be speech.
     */
    val probes = Channel<ShortArray>(Channel.CONFLATED)

    /** The voice plays through speakers the phone's mic can hear: a car over aux, or Bluetooth with the phone mic. */
    @Volatile var echoRisk = false
        private set

    @Volatile private var ducked = false
    @Volatile private var duckedAt = 0L

    private class Spoken(val text: String, @Volatile var endedAt: Long = 0)
    private val spoken = ArrayDeque<Spoken>()

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
        val names = phoneMicDevices()
        val phoneMic = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type in BLUETOOTH_OUTPUTS && it.productName?.toString()?.trim() in names }
        // Media-style playback goes over Bluetooth media (A2DP) and leaves the device's mic alone.
        usage = if (phoneMic) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_VOICE_COMMUNICATION
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes())
            .build()
            .also { audio.requestAudioFocus(it) }
        previousMode = audio.mode
        echoRisk = if (phoneMic) {
            true
        } else {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            routeOutput() in SPEAKERS_THE_MIC_HEARS
        }
        endpointer.playingExtraMargin = if (echoRisk) ECHO_RISK_MARGIN_DB else PLAYING_MARGIN_DB
        captureJob = scope.launch { capture(phoneMic) }
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
        focusRequest?.let(audio::abandonAudioFocusRequest)
        focusRequest = null
        endpointer.reset()
        while (probes.tryReceive().isSuccess) Unit
        while (utterances.tryReceive().isSuccess) Unit
        speechInProgress.value = false
        level.value = 0f
    }

    /** Plays 16-bit mono PCM of [text]; returns when it has finished playing or when cancelled. */
    suspend fun play(audioData: Wav.Decoded, text: String = "") = withContext(Dispatchers.IO) {
        val out = trackFor(audioData.sampleRate)
        out.setVolume(if (ducked) DUCKED_VOLUME else 1f)
        val entry = Spoken(text)
        synchronized(spoken) {
            spoken.addLast(entry)
            while (spoken.size > 12) spoken.removeFirst()
        }
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
            entry.endedAt = System.currentTimeMillis()
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
        duck(false)
    }

    /** Lowers the assistant's voice while checking whether the user is talking over it. */
    fun duck(on: Boolean) {
        ducked = on
        if (on) duckedAt = System.currentTimeMillis()
        track?.let { runCatching { it.setVolume(if (on) DUCKED_VOLUME else 1f) } }
    }

    /** What the assistant said recently enough that the mic may still be hearing it. */
    fun recentSpeech(since: Long = System.currentTimeMillis()): List<String> = synchronized(spoken) {
        spoken.filter { it.endedAt == 0L || it.endedAt + ECHO_TAIL_MS >= since }.map { it.text }
    }

    @SuppressLint("MissingPermission")
    private suspend fun capture(phoneMic: Boolean) {
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
        if (phoneMic) {
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let(recorder::setPreferredDevice)
        }
        var probeFrames = 0
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
                        if (echoRisk && playing && captureWhilePlaying()) {
                            duck(true)
                            probeFrames = PROBE_MS / 20
                        }
                    }
                    is Endpointer.Event.Utterance -> {
                        speechInProgress.value = false
                        utterances.trySend(event.pcm to System.currentTimeMillis() - event.pcm.size / 16)
                        if (probeFrames > 0) probes.trySend(event.pcm.takeLast(16 * (PROBE_MS + 200)).toShortArray())
                        probeFrames = 0
                    }
                    Endpointer.Event.Discarded -> {
                        speechInProgress.value = false
                        if (probeFrames > 0) probes.trySend(ShortArray(0))
                        probeFrames = 0
                    }
                    null -> Unit
                }
                // From just before the onset, so the probe holds little of what came before it.
                if (probeFrames > 0 && --probeFrames == 0) endpointer.recent(PROBE_MS + 200)?.let { probes.trySend(it) }
                // Nothing checked the probe (for example, while reading an approval aloud): restore the volume.
                if (ducked && probeFrames == 0 && System.currentTimeMillis() - duckedAt > MAX_DUCK_MS) duck(false)
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

    /** Headset or Bluetooth if connected, otherwise the loudspeaker (not the earpiece). Returns the device type used. */
    private fun routeOutput(): Int? {
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
            return pick?.type
        } else {
            val headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in headsets }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = headset == null
            return headset?.type ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
    }

    private fun attributes() = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private companion object {
        /** Wired outputs that are often external speakers (a car's aux input), where the phone's mic hears the voice. */
        val SPEAKERS_THE_MIC_HEARS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
        )
        const val PLAYING_MARGIN_DB = 6.0
        const val ECHO_RISK_MARGIN_DB = 10.0
        const val PROBE_MS = 1_000
        const val DUCKED_VOLUME = 0.25f
        const val MAX_DUCK_MS = 3_000L
        /** Bluetooth and room echo can arrive this long after playback ends. */
        const val ECHO_TAIL_MS = 1_500L
    }
}

/** Waits for the next utterance from [DuplexAudio] and transcribes it with Parakeet on the computer. */
class ComputerSpeechInput(
    private val audio: DuplexAudio,
    private val client: ComputerVoiceClient,
) : SpeechInput {
    override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): ListenResult = coroutineScope {
        val levels = launch { audio.level.collect(onLevel) }
        try {
            hear(onPartial)
        } catch (e: IOException) {
            ListenResult.Failed("Couldn't reach the voice service on your computer.")
        } finally {
            levels.cancel()
        }
    }

    private suspend fun hear(onPartial: (String) -> Unit): ListenResult {
        while (true) {
            val (pcm, startedAt) = nextUtterance() ?: return ListenResult.Silence
            onPartial("…")
            val text = client.transcribe(pcm)
            if (text.isBlank()) return ListenResult.Silence
            // The tail of the assistant's last sentence, heard through the speakers.
            val recent = if (audio.echoRisk) audio.recentSpeech(since = startedAt) else emptyList()
            if (recent.isEmpty() || !EchoFilter.isEcho(text, recent)) return ListenResult.Heard(text)
            onPartial("")
        }
    }

    /**
     * The next utterance, or null after 8 s with nobody talking. Utterances that
     * started more than 3 s before we asked are stray noise from between turns
     * and are dropped; a barge-in that started just before is kept.
     */
    private suspend fun nextUtterance(): Pair<ShortArray, Long>? {
        val asked = System.currentTimeMillis()
        while (true) {
            val next = withTimeoutOrNull(250) { audio.utterances.receive() }
            if (next != null) {
                if (next.second >= asked - 3_000) return next
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
    private class PlaybackQueue {
        val audio = Channel<Deferred<Pair<String, Wav.Decoded>?>>(Channel.UNLIMITED)
        val pending = MutableStateFlow(0)
    }
    private var queue = PlaybackQueue()
    private var player: Job? = null

    override suspend fun prepare(): Boolean = client.available()

    override fun enqueue(text: String) {
        if (text.isBlank()) return
        val source = queue
        source.pending.update { it + 1 }
        val audioFor = scope.async { runCatching { text to client.speak(text, voiceName) }.getOrNull() }
        if (source.audio.trySend(audioFor).isFailure) {
            audioFor.cancel()
            source.pending.update { (it - 1).coerceAtLeast(0) }
            return
        }
        if (player?.isActive != true) player = scope.launch { playLoop(source) }
    }

    private suspend fun playLoop(source: PlaybackQueue) {
        for (next in source.audio) {
            try {
                next.await()?.let { (text, wav) -> audio.play(wav, text) }
            } catch (e: CancellationException) {
                next.cancel()
                throw e
            } finally {
                source.pending.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    override suspend fun awaitIdle() {
        queue.pending.first { it == 0 }
    }

    override fun stop() {
        val previous = queue
        queue = PlaybackQueue()
        player?.cancel()
        player = null
        previous.audio.close()
        while (true) {
            val d = previous.audio.tryReceive().getOrNull() ?: break
            d.cancel()
        }
        audio.stopPlayback()
        previous.pending.value = 0
    }

    override fun shutdown() = stop()
}
