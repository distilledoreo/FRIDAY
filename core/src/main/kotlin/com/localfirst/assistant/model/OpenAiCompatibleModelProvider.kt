package com.localfirst.assistant.model

import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.tools.ToolDefinition
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Talks to any server that implements the OpenAI chat-completions HTTP API,
 * including local servers. Callers pass the API root (typically ending in
 * `/v1`); this client posts to `{baseUrl}/chat/completions`.
 */
class OpenAiCompatibleModelProvider(
    private val config: OpenAiCompatibleConfig,
) : ModelProvider {
    @Volatile var incognito: Boolean = false
    override suspend fun sendConversation(
        messages: List<Message>,
        tools: List<ToolDefinition>,
    ): ModelResponse = withContext(Dispatchers.IO) {
        val connection = open(payload(messages, tools, stream = false), stream = false)
        try {
            readJsonResponse(connection)
        } catch (e: Exception) {
            throw translate(e)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun streamConversation(
        messages: List<Message>,
        tools: List<ToolDefinition>,
        onTextDelta: (String) -> Unit,
    ): ModelResponse {
        val payload = payload(messages, tools, stream = true)
        // The stream is read on its own thread. A blocked socket read can't see
        // coroutine cancellation, and on some JVMs disconnect() waits for that
        // read, so Stop returns at once and the connection is closed behind it.
        return suspendCancellableCoroutine { continuation ->
            val cancelled = AtomicBoolean(false)
            val connection = AtomicReference<HttpURLConnection?>()
            continuation.invokeOnCancellation {
                cancelled.set(true)
                connection.get()?.let { open -> thread(isDaemon = true, name = "model-stream-close") { open.disconnect() } }
            }
            thread(isDaemon = true, name = "model-stream") {
                val result = try {
                    val opened = open(payload, stream = true, cancelled = cancelled)
                    connection.set(opened)
                    try {
                        if (cancelled.get()) throw CancellationException("Stopped.")
                        Result.success(readStream(opened, cancelled) { if (!cancelled.get()) onTextDelta(it) })
                    } finally {
                        opened.disconnect()
                    }
                } catch (e: Exception) {
                    Result.failure(translate(e))
                }
                // Ignored when the coroutine was already cancelled.
                continuation.resumeWith(result)
            }
        }
    }

    private fun readStream(
        connection: HttpURLConnection,
        cancelled: AtomicBoolean,
        onTextDelta: (String) -> Unit,
    ): ModelResponse {
        val code = connection.responseCode
        if (code !in 200..299) throw httpError(code, connection)
        val type = connection.contentType.orEmpty()
        if (!type.contains("text/event-stream")) {
            // Some servers ignore stream=true and answer with a single JSON body.
            val response = parseBody(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val text = when (response) {
                is ModelResponse.TextResponse -> response.text
                is ModelResponse.ToolCallResponse -> response.text.orEmpty()
            }
            if (text.isNotEmpty()) onTextDelta(text)
            return response
        }
        val accumulator = ChatCompletionStreamAccumulator()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (!accumulator.done && !cancelled.get()) {
                val line = reader.readLine() ?: break
                accumulator.acceptLine(line)?.let(onTextDelta)
            }
        }
        return accumulator.result()
    }

    private fun readJsonResponse(connection: HttpURLConnection): ModelResponse {
        val code = connection.responseCode
        if (code !in 200..299) throw httpError(code, connection)
        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parseBody(text)
    }

    private fun parseBody(text: String): ModelResponse = try {
        parseChatCompletion(text)
    } catch (e: ModelProviderException) {
        throw e
    } catch (e: Exception) {
        throw ModelProviderException("The model server returned a response this app could not read.", e)
    }

    private fun httpError(code: Int, connection: HttpURLConnection): ModelProviderException {
        val text = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val detail = snippet(text)
        val suffix = if (detail.isEmpty()) "" else " $detail"
        return ModelProviderException("The model server returned HTTP $code.$suffix")
    }

    private fun translate(e: Exception): Exception = when (e) {
        is ModelProviderException, is CancellationException -> e
        is UnknownHostException -> ModelProviderException(
            "Can't reach the model server. Check the server address.",
            e,
        )
        is ConnectException -> ModelProviderException(
            "Can't reach the model server. Check that it is running and reachable from this device.",
            e,
        )
        is SocketTimeoutException -> ModelProviderException(
            "The model server timed out before answering.",
            e,
        )
        is IOException -> ModelProviderException(
            "Network error talking to the model server: ${e.message ?: "unknown I/O error"}",
            e,
        )
        else -> ModelProviderException(e.message ?: "Model request failed.", e)
    }

    /** Sends the opening of a new chat, built exactly like a real request, marked as a prime; failures are ignored. */
    override suspend fun prime(messages: List<Message>, tools: List<ToolDefinition>) {
        if (incognito) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val connection = (URI(resolveChatCompletionsUrl(config.baseUrl)).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = config.connectTimeoutMillis; readTimeout = 15_000; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("X-Assistant-Prime", "1")
                    config.apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
                connection.outputStream.use { it.write(payload(messages, tools, stream = true).toByteArray()) }
                connection.responseCode
                connection.disconnect()
            }
        }
    }

    private fun payload(messages: List<Message>, tools: List<ToolDefinition>, stream: Boolean): String {
        val body = buildChatCompletionRequest(
            model = config.model,
            messages = messages,
            tools = tools,
            stream = stream,
        )
        return JsonCodec.json.encodeToString(JsonElement.serializer(), body)
    }

    /** Opens the connection and sends [payload]. Connection failures are translated here. */
    private fun open(payload: String, stream: Boolean, cancelled: AtomicBoolean = AtomicBoolean(false)): HttpURLConnection {
        val deadline = System.nanoTime() + 600_000_000_000L
        while (!cancelled.get()) {
            val connection = openOnce(payload, stream)
            if (connection.responseCode != 503 || connection.getHeaderField("X-Assistant-GPU-Busy") != "1") return connection
            connection.disconnect()
            if (System.nanoTime() > deadline) throw ModelProviderException("GPU handoff is taking too long. Your chat is saved; retry when the computer is ready.")
            repeat(12) { if (cancelled.get()) throw CancellationException("Stopped while waiting for GPU"); Thread.sleep(250) }
        }
        throw CancellationException("Stopped while waiting for GPU")
    }

    private fun openOnce(payload: String, stream: Boolean): HttpURLConnection {
        val endpoint = resolveChatCompletionsUrl(config.baseUrl)
        val url = try {
            URI(endpoint).toURL()
        } catch (e: Exception) {
            throw ModelProviderException("The server address is not a valid URL.", e)
        }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.connectTimeoutMillis
            readTimeout = config.readTimeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (incognito) setRequestProperty("X-Assistant-Incognito", "1")
            setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
            val key = config.apiKey?.trim().orEmpty()
            if (key.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $key")
            }
        }
        try {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        } catch (e: Exception) {
            connection.disconnect()
            throw translate(e)
        }
        return connection
    }
}

data class OpenAiCompatibleConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String? = null,
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 90_000,
)
