package com.localfirst.assistant.web.data

import com.localfirst.assistant.client.*
import kotlinx.browser.window
import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import kotlinx.coroutines.await
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Headers
import org.w3c.fetch.Response
import org.khronos.webgl.Int8Array

/** Authenticated browser transport. Lock and coroutine cancellation abort every in-flight operation. */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
class FetchWorkspaceGateway : WorkspaceGateway {
    private var base = ""
    private var token = ""
    private var epoch = 0
    private val controllers = mutableListOf<dynamic>()
    override fun connect(baseUrl: String, token: String) {
        lock()
        val parsed = js("new URL(baseUrl || window.location.origin, window.location.origin)")
        require(parsed.protocol == "https:" || parsed.protocol == "http:") { "Use an HTTP or HTTPS gateway address." }
        require(parsed.username == "" && parsed.password == "" && parsed.search == "" && parsed.hash == "") { "Use a gateway address without credentials, query or fragment." }
        base = baseUrl.trim().trimEnd('/'); this.token = token.trim()
        require(this.token.isNotBlank()) { "Enter your computer API token." }
    }
    fun origin(): String { val address = base; return js("new URL(address || window.location.origin, window.location.origin).origin") as String }
    override fun lock() { epoch++; token = ""; controllers.toList().forEach { it.abort() }; controllers.clear() }
    private fun valid(path: String) = path.startsWith("/workspace/") || path in setOf("/transcribe", "/speak", "/extract", "/v1/models", "/grounding", "/grounding/page") || path.startsWith("/images?") || path.startsWith("/image?")
    private suspend fun <T> fetch(path: String, method: String, body: dynamic = null, mime: String? = null, extra: Map<String, String> = emptyMap(), timeoutMs: Int = 120000, read: suspend (Response) -> T): T {
        require(valid(path) && !path.substringBefore('?').contains("..")) { "Unknown computer path." }
        check(token.isNotBlank()) { "Connect to the computer first." }
        val identity = epoch; val controller = js("new AbortController()")
        controllers.add(controller)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) controller.abort() }
        val timeout = window.setTimeout({ controller.abort() }, timeoutMs)
        try {
            val headers = Headers(); headers.set("Authorization", "Bearer $token")
            if (mime != null) headers.set("Content-Type", mime)
            extra.forEach { (k, v) -> headers.set(k, v) }
            val init = RequestInit(method = method, headers = headers); init.asDynamic().body = body; init.asDynamic().signal = controller.signal
            val response = window.fetch(base + path, init).await()
            if (!response.ok) {
                val detail = response.text().await()
                throw WorkspaceException(response.status.toInt(), when(response.status.toInt()) {
                    409 -> "Changed on another client. Refresh and review before retrying; your draft is retained."
                    401, 403 -> "The computer did not accept this request. Check your token and access settings."
                    else -> "Computer request failed (${response.status}): ${detail.take(250)}"
                })
            }
            check(identity == epoch) { "Session was locked." }
            val result = read(response)
            check(identity == epoch) { "Session was locked." }
            return result
        } finally { cancellation?.dispose(); window.clearTimeout(timeout); controllers.remove(controller); controller.abort() }
    }
    override suspend fun request(path: String, method: String, body: JsonObject?): JsonElement = fetch(path, method, body?.toString(), if (body != null) "application/json" else null, timeoutMs = if (path.contains("/changes")) 15000 else 120000) { wireJson.parseToJsonElement(it.text().await()) }
    suspend fun bytes(path: String, method: String = "GET", data: ByteArray? = null, mime: String? = null): ByteArray = fetch(path, method, data?.buffer(), mime) { response ->
        val array = Int8Array(response.arrayBuffer().await()); ByteArray(array.length) { array.asDynamic()[it] as Byte }
    }
    suspend fun binaryJson(path: String, data: ByteArray, mime: String, headers: Map<String, String> = emptyMap()): JsonObject = fetch(path, "POST", data.buffer(), mime, headers) { wireJson.parseToJsonElement(it.text().await()).jsonObject }
    override suspend fun upload(name: String, mime: String, bytes: ByteArray): JsonObject {
        val encoded = com.localfirst.assistant.platform.PlatformNet.encodeUrl(name)
        val file = binaryJson("/workspace/files", bytes, mime, mapOf("X-File-Name" to encoded))
        val actualMime = file.string("mime").ifBlank { mime }
        if (actualMime.startsWith("image/")) {
            if (bytes.size <= 3 * 1024 * 1024 && actualMime in setOf("image/jpeg", "image/png")) return file
            val preview = imagePreview(bytes, actualMime)
            val vision = binaryJson("/workspace/files", preview, "image/jpeg", mapOf("X-File-Name" to com.localfirst.assistant.platform.PlatformNet.encodeUrl("$name-preview.jpg")))
            return JsonObject(file + ("vision" to JsonPrimitive("remote:${vision.string("id")}")))
        }
        try {
            val extracted = binaryJson("/extract", bytes, actualMime, mapOf("X-Filename" to encoded))
            val pages = mutableListOf<JsonElement>()
            for ((index, page) in (extracted["images"] as? JsonArray).orEmpty().withIndex()) {
                val raw = window.atob(page.jsonPrimitive.content)
                val info = binaryJson("/workspace/files", ByteArray(raw.length) { raw[it].code.toByte() }, "image/jpeg", mapOf("X-File-Name" to com.localfirst.assistant.platform.PlatformNet.encodeUrl("$name-page-${index + 1}.jpg")))
                pages.add(JsonPrimitive("remote:${info.string("id")}"))
            }
            return JsonObject(file + mapOf("text" to (extracted["text"] ?: JsonPrimitive("")), "pageImages" to JsonArray(pages), "note" to (extracted["note"] ?: JsonPrimitive(if (extracted.flag("truncated")) "Text cut at 60,000 characters." else ""))))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: WorkspaceException) { if (e.status !in setOf(415, 422)) throw e; return file }
    }
    private suspend fun imagePreview(bytes: ByteArray, mime: String): ByteArray {
        val array = bytes.buffer()
        val blob = js("new Blob([array], {type:mime})")
        val bitmap = (window.asDynamic().createImageBitmap(blob) as kotlin.js.Promise<dynamic>).await()
        try {
            val width = (bitmap.width as Number).toDouble(); val height = (bitmap.height as Number).toDouble()
            val scale = minOf(1.0, 1280.0 / maxOf(width, height))
            val canvas = document.createElement("canvas").asDynamic()
            canvas.width = (width * scale).toInt().coerceAtLeast(1); canvas.height = (height * scale).toInt().coerceAtLeast(1)
            val context = canvas.getContext("2d"); context.fillStyle = "white"; context.fillRect(0, 0, canvas.width, canvas.height); context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
            val encoded = kotlinx.coroutines.suspendCancellableCoroutine<org.w3c.files.Blob> { continuation ->
                canvas.toBlob({ image: dynamic -> if (continuation.isActive) { if (image == null) continuation.resumeWith(Result.failure(IllegalStateException("Image preview could not be created"))) else continuation.resumeWith(Result.success(image as org.w3c.files.Blob)) } }, "image/jpeg", .85)
            }
            val result = Int8Array((encoded.asDynamic().arrayBuffer() as kotlin.js.Promise<org.khronos.webgl.ArrayBuffer>).await())
            return ByteArray(result.length) { result.asDynamic()[it] as Byte }
        } finally { bitmap.close() }
    }
    override suspend fun fileBytes(id: String): ByteArray { require(Regex("[a-f0-9]{32}").matches(id)); return bytes("/workspace/files/$id") }
    /** Streams a picked file straight from disk, without copying it into page memory. */
    suspend fun sendFile(path: String, file: dynamic, mime: String = "application/octet-stream"): JsonElement =
        fetch(path, "POST", file, mime, timeoutMs = 600000) { wireJson.parseToJsonElement(it.text().await()) }
    override suspend fun artifact(id: String) {
        require(Regex("[a-f0-9]{32}").matches(id))
        fetch("/workspace/files/$id", "GET") { response ->
            val url = URL.createObjectURL(response.blob().await())
            val link = document.createElement("a") as HTMLAnchorElement
            link.href = url; link.download = Regex("filename=\"([^\"]+)\"").find(response.headers.get("Content-Disposition").orEmpty())?.groupValues?.get(1) ?: "assistant-file"
            link.click(); window.setTimeout({ URL.revokeObjectURL(url) }, 10000)
        }
    }
    override suspend fun stream(path: String, body: JsonObject?, event: suspend (JsonObject) -> Unit) {
        fetch(path, if (body == null) "GET" else "POST", body?.toString(), if (body == null) null else "application/json", timeoutMs = 1200000) { response ->
            val reader = response.asDynamic().body.getReader()
            val decoder = js("new TextDecoder()")
            var pending = ""
            try {
                while (true) {
                    val chunk = (reader.read() as kotlin.js.Promise<dynamic>).await()
                    if (chunk.done == true) break
                    val options = js("({stream:true})")
                    pending += decoder.decode(chunk.value, options) as String
                    // One event can carry a job's tool steps so far (each result is capped at 32,000 characters).
                    check(pending.length < 16_000_000) { "Stream response is too large." }
                    while (pending.contains("\n\n")) {
                        val frame = pending.substringBefore("\n\n"); pending = pending.substringAfter("\n\n")
                        val data = frame.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }
                        if (data.isNotBlank()) event(wireJson.parseToJsonElement(data).jsonObject)
                    }
                }
            } finally {
                // Lock aborts the fetch before this cleanup; cancelling an aborted reader rejects too.
                (reader.cancel() as kotlin.js.Promise<dynamic>).catch { null }
                reader.releaseLock()
            }
        }
    }
}
private fun ByteArray.buffer(): dynamic { val data = Int8Array(size); for (i in indices) data.asDynamic()[i] = this[i]; return data.buffer }
