package com.localfirst.assistant.web.data

import kotlinx.browser.window
import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.files.Blob
import org.w3c.dom.url.URL
import kotlinx.coroutines.await
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Headers

/** Same-origin by default; the gateway authenticates every request. Abort on timeout or tab lock. */
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
        this.base = baseUrl.trim().trimEnd('/')
        this.token = token.trim()
        require(this.token.isNotBlank()) { "Enter your computer API token." }
    }
    override fun lock() {
        epoch++; token = ""
        controllers.toList().forEach { it.abort() }; controllers.clear()
    }
    override suspend fun artifact(id: String) {
        require(Regex("[a-f0-9]{32}").matches(id))
        val identity = epoch; val controller = js("new AbortController()")
        controllers.add(controller)
        val timeout = window.setTimeout({ controller.abort() }, 30000)
        try {
            val headers = Headers(); headers.set("Authorization", "Bearer $token")
            val init = RequestInit(method = "GET", headers = headers); init.asDynamic().signal = controller.signal
            val response = window.fetch(base + "/workspace/files/$id", init).await()
            check(response.ok) { "File download failed (${response.status})" }
            val blob = response.blob().await()
            check(identity == epoch) { "Session was locked." }
            val url = URL.createObjectURL(blob)
            val link = document.createElement("a") as HTMLAnchorElement
            link.href = url; link.download = Regex("filename=\"([^\"]+)\"").find(response.headers.get("Content-Disposition").orEmpty())?.groupValues?.get(1) ?: "assistant-file"
            link.click(); window.setTimeout({ URL.revokeObjectURL(url) }, 10000)
        } finally { window.clearTimeout(timeout); controllers.remove(controller); controller.abort() }
    }
    override suspend fun request(path: String, method: String, body: JsonObject?): JsonElement {
        require(path.startsWith("/workspace/")) { "Unknown workspace path." }
        val identity = epoch
        val controller = js("new AbortController()")
        controllers.add(controller)
        val timeout = window.setTimeout({ controller.abort() }, 15000)
        try {
            val headers = Headers(); headers.set("Authorization", "Bearer $token")
            if (body != null) headers.set("Content-Type", "application/json")
            val init = RequestInit(method = method, headers = headers, body = body?.toString())
            init.asDynamic().signal = controller.signal
            val response = window.fetch(base + path, init).await()
            val text = response.text().await()
            check(identity == epoch) { "Session was locked." }
            if (!response.ok) throw WorkspaceException(response.status.toInt(), when (response.status.toInt()) {
                409 -> "Changed on another client. Refresh and review before retrying; your draft is retained."
                401, 403 -> "The computer did not accept this token. Lock and reconnect."
                else -> "Computer request failed (${response.status}): ${text.take(250)}"
            })
            return wireJson.parseToJsonElement(text)
        } finally { window.clearTimeout(timeout); controllers.remove(controller); controller.abort() }
    }
}
