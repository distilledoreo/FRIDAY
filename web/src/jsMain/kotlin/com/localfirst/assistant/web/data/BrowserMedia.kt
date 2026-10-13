package com.localfirst.assistant.web.data

import com.localfirst.assistant.client.*
import com.localfirst.assistant.ui.genui.GenUiImages
import com.localfirst.assistant.ui.genui.ImageHit
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.browser.window
import kotlinx.browser.document
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import org.khronos.webgl.Float32Array
import kotlin.math.*

class BrowserImages(private val gateway: FetchWorkspaceGateway) : GenUiImages {
    private val cache = linkedMapOf<String, ImageBitmap>()
    fun clear() { cache.clear() }
    suspend fun path(path: String): ImageBitmap? {
        cache[path]?.let { return it }
        val image = Image.makeFromEncoded(gateway.bytes(path)).toComposeImageBitmap()
        if (cache.size >= 24) cache.remove(cache.keys.first())
        cache[path] = image
        return image
    }
    override suspend fun load(url: String, maxEdge: Int) = path("/image?url=" + com.localfirst.assistant.platform.PlatformNet.encodeUrl(url))
    override suspend fun search(query: String, count: Int): List<ImageHit> {
        val result = gateway.request("/images?n=${count.coerceIn(1,12)}&q=" + com.localfirst.assistant.platform.PlatformNet.encodeUrl(query)).jsonObject
        return result["results"]?.rows().orEmpty().map { ImageHit(it.string("image"), it.string("thumbnail"), it.string("title"), it.string("source")) }
    }
}

data class BrowserVoiceState(val recording: Boolean = false, val conversation: Boolean = false, val status: String = "")

/** WAV capture works with the gateway's existing speech decoder in every Web Audio browser. */
class BrowserVoice(private val gateway: FetchWorkspaceGateway, private val vm: WorkspaceController, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(BrowserVoiceState())
    val state = mutable.asStateFlow()
    private var epoch = 0
    private var media: dynamic = null
    private var audio: dynamic = null
    private var source: dynamic = null
    private var processor: dynamic = null
    private var output: dynamic = null
    private var playback: dynamic = null
    private var timer: Int? = null
    private var operation: Job? = null
    private val samples = mutableListOf<FloatArray>()
    private var sampleCount = 0
    private var sampleRate = 48000
    private var speechAt = 0.0
    private var started = 0.0
    private var hadSpeech = false
    private var waitingReply = false
    fun dictate() { if (state.value.recording) finish() else start(false) }
    fun converse() { if (state.value.conversation) close() else start(true) }
    private fun releaseCapture() {
        timer?.let(window::clearTimeout); timer = null
        if (processor != null) { processor.onaudioprocess = null; processor.disconnect() }
        if (source != null) source.disconnect()
        if (media != null) media.getTracks().forEach { track: dynamic -> track.stop() }
        if (audio != null) audio.close()
        media = null; audio = null; source = null; processor = null
    }
    fun close() { waitingReply = false; epoch++; operation?.cancel(); releaseCapture(); playback?.pause(); playback = null; if (output != null) org.w3c.dom.url.URL.revokeObjectURL(output as String); output = null; samples.clear(); sampleCount = 0; mutable.value = BrowserVoiceState() }
    private fun start(conversation: Boolean) {
        if (!vm.state.value.connected || vm.state.value.activeJob != null || vm.state.value.busy || state.value.status.isNotBlank()) return
        val identity = epoch
        mutable.value = BrowserVoiceState(conversation = conversation, status = "Requesting microphone…")
        operation = scope.launch {
            try {
                check(window.asDynamic().isSecureContext == true) { "Microphone capture requires HTTPS or localhost." }
                val constraints = js("({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true}})")
                val stream = (window.navigator.asDynamic().mediaDevices.getUserMedia(constraints) as kotlin.js.Promise<dynamic>).await()
                if (identity != epoch) { stream.getTracks().forEach { t: dynamic -> t.stop() }; return@launch }
                media = stream
                audio = js("new (window.AudioContext || window.webkitAudioContext)()")
                (audio.resume() as kotlin.js.Promise<dynamic>).await()
                sampleRate = (audio.sampleRate as Number).toInt()
                source = audio.createMediaStreamSource(stream); processor = audio.createScriptProcessor(4096, 1, 1)
                samples.clear(); sampleCount = 0; started = window.performance.now(); speechAt = started; hadSpeech = false
                processor.onaudioprocess = { event: dynamic ->
                    if (identity == epoch && state.value.recording) {
                        val data = event.inputBuffer.getChannelData(0) as Float32Array
                        val copy = FloatArray(data.length) { data.asDynamic()[it] as Float }
                        samples.add(copy); sampleCount += copy.size
                        val loud = sqrt(copy.sumOf { (it * it).toDouble() } / copy.size) > .018
                        if (loud) { hadSpeech = true; speechAt = window.performance.now() }
                        if (conversation && hadSpeech && window.performance.now() - speechAt > 1000 && window.performance.now() - started > 1200) finish()
                    }
                }
                // Silent gain keeps the processor scheduled without playing microphone feedback.
                val gain = audio.createGain(); gain.gain.value = 0
                source.connect(processor); processor.connect(gain); gain.connect(audio.destination)
                mutable.value = BrowserVoiceState(true, conversation, if (conversation) "Listening · pause to send" else "Listening · tap microphone to finish")
                timer = window.setTimeout({ finish() }, 110000)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (identity == epoch) { releaseCapture(); mutable.value = BrowserVoiceState(); vm.reportError(e.message ?: "Microphone unavailable") } }
        }
    }
    private fun finish() {
        if (!state.value.recording) return
        val conversation = state.value.conversation; val identity = epoch
        releaseCapture(); mutable.value = BrowserVoiceState(false, conversation, "Transcribing…")
        val wav = wav(); samples.clear(); sampleCount = 0
        operation = scope.launch {
            try {
                val text = gateway.binaryJson("/transcribe", wav, "audio/wav").string("text").trim()
                if (identity != epoch) return@launch
                if (text.isBlank()) { mutable.value = BrowserVoiceState(false, conversation); if (conversation) start(true); return@launch }
                vm.setDraft((vm.state.value.draft.trim() + " " + text).trim())
                mutable.value = BrowserVoiceState(false, conversation, if (conversation) "Thinking…" else "")
                if (conversation) {
                    waitingReply = true
                    val send = vm.send()
                    if (send == null) { close(); vm.reportError("Finish the current operation and send the dictated draft.") }
                    else { send.join(); if (vm.state.value.error != null) close() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (identity == epoch) { mutable.value = BrowserVoiceState(); vm.reportError(e.message ?: "Speech unavailable") } }
        }
    }
    fun reply(text: String) {
        if (!state.value.conversation || state.value.recording || !waitingReply) return
        waitingReply = false
        val identity = epoch
        operation = scope.launch {
            try {
                mutable.value = state.value.copy(status = "Speaking…")
                for (part in com.localfirst.assistant.voice.SpeechText.fromMarkdown(com.localfirst.assistant.presentation.ReplyText.plain(text)).chunked(950)) {
                    if (identity != epoch) return@launch
                    val bytes = gateway.bytes("/speak", "POST", buildJsonObject { put("text", part) }.toString().encodeToByteArray(), "application/json")
                    val array = org.khronos.webgl.Int8Array(bytes.size); for (i in bytes.indices) array.asDynamic()[i] = bytes[i]
                    val blob = js("new Blob([array.buffer], {type:'audio/wav'})")
                    output = org.w3c.dom.url.URL.createObjectURL(blob as org.w3c.files.Blob)
                    playback = js("new Audio()")
                    playback.src = output
                    suspendCancellableCoroutine<Unit> { continuation ->
                        playback.onended = { if (continuation.isActive) continuation.resumeWith(Result.success(Unit)) }
                        playback.onerror = { if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("Audio playback failed"))) }
                        val play = playback.play() as kotlin.js.Promise<dynamic>
                        play.catch { error -> if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("Allow audio playback to continue voice chat"))); null }
                        continuation.invokeOnCancellation { playback?.pause() }
                    }
                    org.w3c.dom.url.URL.revokeObjectURL(output as String); output = null; playback = null
                }
                if (identity == epoch) { mutable.value = state.value.copy(status = ""); start(true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (identity == epoch) { close(); vm.reportError(e.message ?: "Speech playback unavailable") } }
        }
    }
    private fun wav(): ByteArray {
        val input = FloatArray(sampleCount); var offset = 0
        for (part in samples) { part.copyInto(input, offset); offset += part.size }
        val ratio = sampleRate / 16000.0; val length = (input.size / ratio).toInt()
        val bytes = ByteArray(44 + length * 2)
        fun word(at: Int, value: Int, count: Int) { for (i in 0 until count) bytes[at+i] = (value ushr (8*i)).toByte() }
        fun label(at: Int, text: String) { text.encodeToByteArray().copyInto(bytes, at) }
        label(0,"RIFF"); word(4,bytes.size-8,4); label(8,"WAVE"); label(12,"fmt "); word(16,16,4); word(20,1,2); word(22,1,2); word(24,16000,4); word(28,32000,4); word(32,2,2); word(34,16,2); label(36,"data"); word(40,length*2,4)
        for (i in 0 until length) {
            val start = (i * ratio).toInt(); val end = min(input.size, ((i+1)*ratio).toInt()).coerceAtLeast(start+1)
            var sum = 0f; for (j in start until end) sum += input[j]
            word(44+i*2, ((sum/(end-start)).coerceIn(-1f,1f)*32767).toInt(), 2)
        }
        return bytes
    }
}

fun pickBrowserFiles(scope: CoroutineScope, vm: WorkspaceController, attachToDraft: Boolean = true) {
    val picker = document.createElement("input").asDynamic(); picker.type = "file"; picker.multiple = true
    picker.style.display = "none"; document.body!!.appendChild(picker)
    picker.oncancel = { picker.remove() }
    picker.onchange = {
        val files = picker.files
        picker.remove()
        scope.launch {
            for (i in 0 until (files.length as Int)) {
                if (!vm.state.value.connected) break
                try {
                    val file = files.item(i)
                    require((file.size as Number).toDouble() <= 25 * 1024 * 1024) { "File exceeds 25 MB." }
                    val buffer = (file.arrayBuffer() as kotlin.js.Promise<dynamic>).await()
                    val array = org.khronos.webgl.Int8Array(buffer as org.khronos.webgl.ArrayBuffer)
                    val bytes = ByteArray(array.length) { array.asDynamic()[it] as Byte }
                    vm.upload(file.name as String, (file.type as String).ifBlank { "application/octet-stream" }, bytes, attachToDraft)?.join()
                    if (vm.state.value.error != null) break
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { vm.reportError(e.message ?: "File upload failed"); break }
            }
        }; Unit
    }
    picker.click()
}
