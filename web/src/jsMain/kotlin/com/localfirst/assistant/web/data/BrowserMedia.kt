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
import kotlinx.coroutines.channels.Channel
import com.localfirst.assistant.voice.*
import kotlin.math.*

/** Saved videos as blob URLs for the page's <video> player: the gateway needs the sign-in token, which a plain src can't send. */
class BrowserVideos(private val gateway: FetchWorkspaceGateway) {
    private val cache = linkedMapOf<String, String>()
    fun clear() { cache.values.forEach { org.w3c.dom.url.URL.revokeObjectURL(it) }; cache.clear() }
    suspend fun url(path: String): String {
        cache[path]?.let { return it }
        val bytes = gateway.bytes(path)
        // A Kotlin/JS ByteArray is an Int8Array underneath, so the Blob takes it without copying byte by byte.
        val blob = org.w3c.files.Blob(arrayOf(bytes.unsafeCast<org.khronos.webgl.Int8Array>()), org.w3c.files.BlobPropertyBag(type = "video/mp4"))
        val url = org.w3c.dom.url.URL.createObjectURL(blob)
        // A few recent videos stay ready to replay; older ones release their memory.
        if (cache.size >= 4) cache.remove(cache.keys.first())?.let { org.w3c.dom.url.URL.revokeObjectURL(it) }
        cache[path] = url
        return url
    }
}

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

/** One microphone stream for voice mode and dictation, with echo cancellation so FRIDAY's own speech is suppressed. */
class BrowserMicrophone {
    private var media: dynamic = null
    private var audio: dynamic = null
    private var source: dynamic = null
    private var processor: dynamic = null
    private var consumer: ((FloatArray) -> Unit)? = null
    var sampleRate = 48000; private set

    /** Opens the microphone once; false when it is unavailable or permission is refused. */
    suspend fun open(): Boolean {
        if (media != null) return true
        check(window.asDynamic().isSecureContext == true) { "Microphone capture requires HTTPS or localhost." }
        val constraints = js("({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true,autoGainControl:true}})")
        val stream = try { (window.navigator.asDynamic().mediaDevices.getUserMedia(constraints) as kotlin.js.Promise<dynamic>).await() }
            catch (e: CancellationException) { throw e } catch (e: Throwable) { return false }
        media = stream
        audio = js("new (window.AudioContext || window.webkitAudioContext)()")
        (audio.resume() as kotlin.js.Promise<dynamic>).await()
        sampleRate = (audio.sampleRate as Number).toInt()
        source = audio.createMediaStreamSource(stream); processor = audio.createScriptProcessor(2048, 1, 1)
        processor.onaudioprocess = { event: dynamic ->
            val data = event.inputBuffer.getChannelData(0) as Float32Array
            consumer?.invoke(FloatArray(data.length) { data.asDynamic()[it] as Float })
        }
        // Silent gain keeps the processor scheduled without playing microphone feedback.
        val gain = audio.createGain(); gain.gain.value = 0
        source.connect(processor); processor.connect(gain); gain.connect(audio.destination)
        return true
    }
    fun consume(next: ((FloatArray) -> Unit)?) { consumer = next }
    fun mute(muted: Boolean) { media?.getAudioTracks()?.forEach { track: dynamic -> track.enabled = !muted } }
    fun close() {
        consumer = null
        if (processor != null) { processor.onaudioprocess = null; processor.disconnect() }
        if (source != null) source.disconnect()
        if (media != null) media.getTracks().forEach { track: dynamic -> track.stop() }
        if (audio != null) audio.close()
        media = null; audio = null; source = null; processor = null
    }
}

private fun rms(frame: FloatArray) = sqrt(frame.sumOf { (it * it).toDouble() } / frame.size.coerceAtLeast(1))

/** Records one utterance, ending after [silenceMs] of quiet once speech began; null when nobody spoke. */
private suspend fun BrowserMicrophone.utterance(silenceMs: Long, onLevel: (Float) -> Unit, stop: CompletableDeferred<Unit>? = null, maxQuietMs: Long = 8000): FloatArray? {
    val frames = ArrayDeque<FloatArray>()
    var kept = 0; var hadSpeech = false
    val started = window.performance.now(); var speechAt = started
    val done = CompletableDeferred<Boolean>()
    consume { frame ->
        val now = window.performance.now()
        val level = rms(frame)
        onLevel((level * 12).toFloat().coerceIn(0f, 1f))
        if (level > .018) { hadSpeech = true; speechAt = now }
        frames.addLast(frame); kept += frame.size
        // Before speech, keep only a short lead-in so the recording starts close to the first word.
        while (!hadSpeech && kept > sampleRate / 2 && frames.size > 1) kept -= frames.removeFirst().size
        when {
            hadSpeech && now - speechAt > silenceMs && now - started > 600 -> done.complete(true)
            !hadSpeech && now - started > maxQuietMs -> done.complete(false)
            now - started > 110000 -> done.complete(hadSpeech)
        }
    }
    try {
        val heard = if (stop == null) done.await() else kotlinx.coroutines.selects.select { done.onAwait { it }; stop.onAwait { hadSpeech } }
        if (!heard) return null
    } finally { consume(null); onLevel(0f) }
    return FloatArray(kept).also { out -> var at = 0; for (f in frames) { f.copyInto(out, at); at += f.size } }
}

/** Speech for the shared voice loop: browser microphone and playback, with the computer's speech services. */
class BrowserVoiceIo(private val gateway: FetchWorkspaceGateway, private val scope: CoroutineScope) {
    val mic = BrowserMicrophone()
    private suspend fun transcribe(samples: FloatArray) = gateway.binaryJson("/transcribe", wav(samples, mic.sampleRate), "audio/wav").string("text").trim()

    private val input = object : SpeechInput {
        override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit, completeSilenceMs: Long): ListenResult = try {
            if (!mic.open()) ListenResult.Failed("Voice needs microphone access. Allow it in the browser and tap to talk.")
            else {
                val samples = mic.utterance(completeSilenceMs, onLevel)
                if (samples == null) ListenResult.Silence
                else { onPartial("…"); transcribe(samples).let { if (it.isBlank()) ListenResult.Silence else ListenResult.Heard(it) } }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { ListenResult.Failed(e.message ?: "Speech recognition is unavailable.") }
    }

    /** Fetches each sentence's audio while the previous one plays, so speech starts with the first sentence. */
    private val speaker = object : Speaker {
        private var output: dynamic = null
        private var playing: dynamic = null
        private var generation = 0
        private var texts = Channel<String>(Channel.UNLIMITED)
        private var worker: Job? = null
        private val pending = MutableStateFlow(0)
        override suspend fun prepare(): Boolean {
            if (output == null) output = js("new (window.AudioContext || window.webkitAudioContext)()")
            runCatching { (output.resume() as kotlin.js.Promise<dynamic>).await() }
            return true
        }
        private fun start() {
            val identity = generation; val queue = texts
            val audio = Channel<dynamic>(1)
            worker = scope.launch {
                launch {
                    for (text in queue) {
                        val bytes = try { gateway.bytes("/speak", "POST", buildJsonObject { put("text", text.take(950)) }.toString().encodeToByteArray(), "application/json") }
                            catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                        audio.send(bytes)
                    }
                }
                for (bytes in audio) {
                    try {
                        if (bytes != null && identity == generation) play(bytes as ByteArray)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { /* A sentence that cannot play is skipped. */ }
                    finally { pending.update { (it - 1).coerceAtLeast(0) } }
                }
            }
        }
        private suspend fun play(bytes: ByteArray) {
            val array = org.khronos.webgl.Int8Array(bytes.size); for (i in bytes.indices) array.asDynamic()[i] = bytes[i]
            val buffer = (output.decodeAudioData(array.buffer) as kotlin.js.Promise<dynamic>).await()
            suspendCancellableCoroutine<Unit> { continuation ->
                val node = output.createBufferSource(); node.buffer = buffer; node.connect(output.destination)
                node.onended = { playing = null; if (continuation.isActive) continuation.resumeWith(Result.success(Unit)) }
                playing = node; node.start()
                continuation.invokeOnCancellation { runCatching { node.stop() } }
            }
        }
        override fun enqueue(text: String) {
            if (text.isBlank()) return
            if (worker?.isActive != true) start()
            pending.update { it + 1 }
            texts.trySend(text)
        }
        override suspend fun awaitIdle() { pending.first { it == 0 } }
        override fun stop() {
            generation++; worker?.cancel(); worker = null; texts.close(); texts = Channel(Channel.UNLIMITED)
            val node = playing; playing = null
            if (node != null) runCatching { node.stop() }
            pending.value = 0
        }
        override fun shutdown() { stop(); output?.close(); output = null }
    }

    /** Talking over FRIDAY interrupts her; the threshold tracks the room and any echo left after cancellation. */
    private val bargeIn = object : BargeInListener {
        override suspend fun awaitSpeech() {
            if (!mic.open()) awaitCancellation()
            val detector = SpeechOnsetDetector.forSpeaker()
            val heard = CompletableDeferred<Unit>()
            val window20ms = (mic.sampleRate / 50).coerceAtLeast(1)
            mic.consume { frame ->
                var at = 0
                while (at < frame.size) {
                    val part = frame.copyOfRange(at, minOf(frame.size, at + window20ms)); at += window20ms
                    val db = 20 * log10(rms(part).coerceAtLeast(1e-6))
                    if (detector.accept(db)) heard.complete(Unit)
                }
            }
            try { heard.await() } finally { mic.consume(null) }
        }
    }

    private val focus = object : VoiceAudioFocus {
        override fun acquire() {}
        override fun release() { mic.close() }
        override fun muteMic() = mic.mute(true)
        override fun unmuteMic() = mic.mute(false)
    }

    val io = VoiceIo(input, speaker, bargeIn, focus, ensureMicrophone = { runCatching { mic.open() }.getOrDefault(false) })

    /** This browser's own speech recognition and voice, the counterpart of the phone's built-in voice; null where unsupported. */
    val browserIo: VoiceIo? = if (js("!!((window.SpeechRecognition || window.webkitSpeechRecognition) && window.speechSynthesis)") as Boolean) VoiceIo(
        input = object : SpeechInput {
            override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit, completeSilenceMs: Long): ListenResult = suspendCancellableCoroutine { continuation ->
                val recognition = js("new (window.SpeechRecognition || window.webkitSpeechRecognition)()")
                recognition.lang = window.navigator.language; recognition.interimResults = true; recognition.continuous = false
                var heard = ""; var failure: String? = null
                recognition.onresult = { event: dynamic ->
                    var interim = ""
                    val results = event.results
                    for (i in (event.resultIndex as Int) until (results.length as Int)) {
                        val result = results[i]
                        if (result.isFinal == true) heard += result[0].transcript as String else interim += result[0].transcript as String
                    }
                    onPartial((heard + interim).trim()); onLevel(.5f)
                }
                recognition.onerror = { event: dynamic ->
                    val error = event.error as? String
                    if (error != "no-speech" && error != "aborted") failure = if (error == "not-allowed") "Voice needs microphone access. Allow it in the browser and tap to talk." else "Speech recognition stopped ($error)."
                }
                recognition.onend = { _: dynamic ->
                    onLevel(0f)
                    if (continuation.isActive) continuation.resumeWith(Result.success(failure?.let { ListenResult.Failed(it) } ?: if (heard.isBlank()) ListenResult.Silence else ListenResult.Heard(heard.trim())))
                }
                continuation.invokeOnCancellation { runCatching { recognition.abort() } }
                recognition.start()
            }
        },
        speaker = object : Speaker {
            private val pending = MutableStateFlow(0)
            override suspend fun prepare() = true
            override fun enqueue(text: String) {
                if (text.isBlank()) return
                val utterance = js("new SpeechSynthesisUtterance(text)")
                utterance.onend = { _: dynamic -> pending.update { (it - 1).coerceAtLeast(0) } }
                utterance.onerror = { _: dynamic -> pending.update { (it - 1).coerceAtLeast(0) } }
                pending.update { it + 1 }
                window.asDynamic().speechSynthesis.speak(utterance)
            }
            override suspend fun awaitIdle() { pending.first { it == 0 } }
            // Some browsers skip the end event after cancel, so the queue is cleared here too.
            override fun stop() { window.asDynamic().speechSynthesis.cancel(); pending.value = 0 }
            override fun shutdown() = stop()
        },
        bargeIn = bargeIn, focus = focus, ensureMicrophone = { runCatching { mic.open() }.getOrDefault(false) },
    ) else null

    /** One spoken message typed into the composer; tap again to finish. */
    private var dictation: CompletableDeferred<Unit>? = null
    val dictating = MutableStateFlow(false)
    fun dictate(vm: WorkspaceController, voiceActive: Boolean) {
        dictation?.let { it.complete(Unit); return }
        if (voiceActive || !vm.state.value.connected) return
        val stop = CompletableDeferred<Unit>(); dictation = stop; dictating.value = true
        scope.launch {
            try {
                if (!mic.open()) { vm.reportError("Dictation needs microphone access. Allow it in the browser."); return@launch }
                val samples = mic.utterance(2500, {}, stop, maxQuietMs = 30000) ?: return@launch
                val text = transcribe(samples)
                if (text.isNotBlank()) vm.setDraft(listOf(vm.state.value.draft.trimEnd(), text).filter(String::isNotBlank).joinToString(" "))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { vm.reportError(e.message ?: "Speech recognition is unavailable.") }
            finally { mic.close(); dictation = null; dictating.value = false }
        }
    }
    fun close() { dictation?.complete(Unit); speaker.shutdown(); mic.close() }
}

private fun wav(input: FloatArray, sampleRate: Int): ByteArray {
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

/** Saves generated bytes as a download on this device. */
fun downloadBytes(name: String, mime: String, bytes: ByteArray) {
    val array = org.khronos.webgl.Int8Array(bytes.size); for (i in bytes.indices) array.asDynamic()[i] = bytes[i]
    val blob = js("new Blob([array.buffer], {type:mime})")
    val url = org.w3c.dom.url.URL.createObjectURL(blob as org.w3c.files.Blob)
    val link = document.createElement("a") as org.w3c.dom.HTMLAnchorElement
    link.href = url; link.download = name; link.click()
    window.setTimeout({ org.w3c.dom.url.URL.revokeObjectURL(url) }, 10000)
}

/** Raw-deflate entries in backups written by Android, through the browser's own decompressor. */
val browserInflate = com.localfirst.assistant.backup.Inflate { data, size ->
    val array = org.khronos.webgl.Int8Array(data.size); for (i in data.indices) array.asDynamic()[i] = data[i]
    check(js("typeof DecompressionStream !== 'undefined'") as Boolean) { "This browser cannot open compressed backups. Use a current browser." }
    val stream = js("new Blob([array.buffer]).stream().pipeThrough(new DecompressionStream('deflate-raw'))")
    val buffer = (js("new Response(stream)").arrayBuffer() as kotlin.js.Promise<org.khronos.webgl.ArrayBuffer>).await()
    val out = org.khronos.webgl.Int8Array(buffer)
    check(out.length <= size) { "Backup entry is larger than recorded." }
    ByteArray(out.length) { out.asDynamic()[it] as Byte }
}

/** Picks one file and hands it to [use] as the browser's File object, or reports why it can't be used. */
fun pickBrowserFile(accept: String, use: (dynamic) -> Unit) {
    val picker = document.createElement("input").asDynamic(); picker.type = "file"; picker.accept = accept
    picker.style.display = "none"; document.body!!.appendChild(picker)
    picker.oncancel = { picker.remove() }
    picker.onchange = { val file = picker.files.item(0); picker.remove(); if (file != null) use(file); Unit }
    picker.click()
}

suspend fun fileBytes(file: dynamic): ByteArray {
    val buffer = (file.arrayBuffer() as kotlin.js.Promise<dynamic>).await()
    val array = org.khronos.webgl.Int8Array(buffer as org.khronos.webgl.ArrayBuffer)
    return ByteArray(array.length) { array.asDynamic()[it] as Byte }
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

/** This browser's choices, kept like the phone's local settings: model, voice engine, barge-in and notifications. */
object DeviceChoices {
    private fun read(key: String): String? = runCatching { kotlinx.browser.localStorage.getItem("friday.device.$key") }.getOrNull()
    private fun write(key: String, value: String?) { runCatching { if (value == null) kotlinx.browser.localStorage.removeItem("friday.device.$key") else kotlinx.browser.localStorage.setItem("friday.device.$key", value) } }
    var model: String? get() = read("model"); set(value) = write("model", value)
    var voiceEngine: String get() = read("voice") ?: "computer"; set(value) = write("voice", value)
    var bargeIn: Boolean get() = read("bargeIn") != "false"; set(value) = write("bargeIn", value.toString())
    var notificationMode: String
        get() = read("notificationMode")?.takeIf { it in setOf("off", "open", "closed") } ?: if (read("notifications") == "true") "open" else "off"
        set(value) = write("notificationMode", value)
    var pushId: String? get() = read("pushId"); set(value) = write("pushId", value)
    var pushOrigin: String? get() = read("pushOrigin"); set(value) = write("pushOrigin", value)
    /** What notifications already announced on this device. */
    fun seen(): MutableMap<String, String> = runCatching {
        Json.parseToJsonElement(read("notices") ?: "{}").jsonObject.mapValues { it.value.jsonPrimitive.content }.toMutableMap()
    }.getOrDefault(mutableMapOf())
    fun saveSeen(seen: Map<String, String>) { write("notices", JsonObject(seen.entries.toList().takeLast(400).associate { it.key to JsonPrimitive(it.value) }).toString()) }
}

val notificationsSupported: Boolean get() = js("typeof Notification !== 'undefined' && window.isSecureContext === true") as Boolean

suspend fun requestNotificationPermission(): Boolean =
    notificationsSupported && (js("Notification.requestPermission()") as kotlin.js.Promise<dynamic>).await() == "granted"

/** Shows a notice while the tab is in the background; clicking it brings FRIDAY forward at [open]. */
fun showNotice(title: String, body: String, tag: String, open: () -> Unit) {
    if (!notificationsSupported || js("Notification.permission") != "granted") return
    val options = js("({})"); options.body = body; options.tag = tag
    val notice = js("new Notification(title, options)")
    notice.onclick = { _: dynamic -> window.focus(); open(); notice.close() }
}

val pushSupported: Boolean get() = notificationsSupported && (js("'serviceWorker' in navigator && 'PushManager' in window") as Boolean)

suspend fun setNotificationMode(gateway: FetchWorkspaceGateway, mode: String) {
    require(mode in setOf("off", "open", "closed"))
    if (mode != "off") check(requestNotificationPermission()) { "Notifications were blocked. Allow them for this site, then try again." }
    if (mode == "closed") {
        check(pushSupported) { "This browser does not support notifications with the tab closed." }
        (kotlinx.browser.window.navigator.asDynamic().serviceWorker.register("/friday/notifications-sw.js", js("({scope:'/friday/'})")) as kotlin.js.Promise<dynamic>).await()
        val registration = (window.navigator.asDynamic().serviceWorker.ready as kotlin.js.Promise<dynamic>).await()
        val key = gateway.request("/workspace/notifications/key").jsonObject.string("key")
        val decoded = window.atob(key.replace('-', '+').replace('_', '/') + "=".repeat((4 - key.length % 4) % 4))
        val bytes = org.khronos.webgl.Uint8Array(decoded.length)
        for (i in decoded.indices) bytes.asDynamic()[i] = decoded[i].code
        var subscription = (registration.pushManager.getSubscription() as kotlin.js.Promise<dynamic>).await()
        if (subscription != null) {
            val previous = subscription.options?.applicationServerKey
            val same = if (previous == null) false else {
                val stored = js("new Uint8Array(previous)")
                stored.length == decoded.length && decoded.indices.all { i -> stored[i] == decoded[i].code }
            }
            if (!same) { (subscription.unsubscribe() as kotlin.js.Promise<dynamic>).await(); subscription = null }
        }
        if (subscription == null) {
            val options = js("({userVisibleOnly:true})"); options.applicationServerKey = bytes
            subscription = (registration.pushManager.subscribe(options) as kotlin.js.Promise<dynamic>).await()
        }
        val json = js("JSON.stringify(subscription.toJSON())") as String
        val response = gateway.request("/workspace/notifications/subscriptions", "POST", Json.parseToJsonElement(json).jsonObject).jsonObject
        DeviceChoices.pushId = response.string("id"); DeviceChoices.pushOrigin = gateway.origin()
    } else {
        DeviceChoices.pushId?.takeIf { DeviceChoices.pushOrigin == gateway.origin() }?.let {
            gateway.request("/workspace/notifications/subscriptions/$it", "DELETE")
        }
        if (pushSupported) {
            val registration = (window.navigator.asDynamic().serviceWorker.getRegistration("/friday/") as kotlin.js.Promise<dynamic>).await()
            val subscription = if (registration != null) (registration.pushManager.getSubscription() as kotlin.js.Promise<dynamic>).await() else null
            if (subscription != null) (subscription.unsubscribe() as kotlin.js.Promise<dynamic>).await()
        }
        DeviceChoices.pushId = null; DeviceChoices.pushOrigin = null
    }
    DeviceChoices.notificationMode = mode
}
