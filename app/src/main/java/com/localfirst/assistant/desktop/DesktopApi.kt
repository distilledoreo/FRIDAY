package com.localfirst.assistant.desktop

import java.net.HttpURLConnection
import java.net.URI

/**
 * One request to the desktop assistant API (the computer at the search service
 * address): voice, document extraction. Returns the status code and body.
 */
internal fun desktopRequest(
    baseUrl: String,
    apiKey: String?,
    method: String,
    path: String,
    body: ByteArray? = null,
    contentType: String? = null,
    headers: Map<String, String> = emptyMap(),
    timeoutMs: Int,
): Pair<Int, ByteArray> {
    val url = URI(baseUrl.trim().trimEnd('/') + path).toURL()
    val connection = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        requestMethod = method
        connectTimeout = 8_000
        readTimeout = timeoutMs
        apiKey?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            setFixedLengthStreamingMode(body.size)
        }
    }
    try {
        body?.let { b -> connection.outputStream.use { it.write(b) } }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return code to (stream?.use { val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                if (output.size() + n > 30 * 1024 * 1024) throw java.io.IOException("Computer response exceeds 30 MB.")
                output.write(buffer, 0, n)
            }
            output.toByteArray() } ?: ByteArray(0))
    } finally {
        connection.disconnect()
    }
}
