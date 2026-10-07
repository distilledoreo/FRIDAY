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
import kotlinx.coroutines.Dispatchers
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
    override suspend fun sendConversation(
        messages: List<Message>,
        tools: List<ToolDefinition>,
    ): ModelResponse = withContext(Dispatchers.IO) {
        val endpoint = resolveChatCompletionsUrl(config.baseUrl)
        val body = buildChatCompletionRequest(
            model = config.model,
            messages = messages,
            tools = tools,
        )
        val payload = JsonCodec.json.encodeToString(JsonElement.serializer(), body)
        post(endpoint, payload)
    }

    private fun post(endpoint: String, payload: String): ModelResponse {
        val connection = openConnection(endpoint)
        try {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { stream -> stream.write(bytes) }
            val code = connection.responseCode
            val responseStream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = snippet(text)
                val suffix = if (detail.isEmpty()) "" else " $detail"
                throw ModelProviderException("The model server returned HTTP $code.$suffix")
            }
            return try {
                parseChatCompletion(text)
            } catch (e: ModelProviderException) {
                throw e
            } catch (e: Exception) {
                throw ModelProviderException(
                    "The model server returned a response this app could not read.",
                    e,
                )
            }
        } catch (e: ModelProviderException) {
            throw e
        } catch (e: UnknownHostException) {
            throw ModelProviderException(
                "Can't reach the model server. Check the server address.",
                e,
            )
        } catch (e: ConnectException) {
            throw ModelProviderException(
                "Can't reach the model server. Check that it is running and reachable from this device.",
                e,
            )
        } catch (e: SocketTimeoutException) {
            throw ModelProviderException(
                "The model server timed out before answering.",
                e,
            )
        } catch (e: IOException) {
            throw ModelProviderException(
                "Network error talking to the model server: ${e.message ?: "unknown I/O error"}",
                e,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(endpoint: String): HttpURLConnection {
        val url = try {
            URI(endpoint).toURL()
        } catch (e: Exception) {
            throw ModelProviderException("The server address is not a valid URL.", e)
        }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.connectTimeoutMillis
            readTimeout = config.readTimeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            val key = config.apiKey?.trim().orEmpty()
            if (key.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $key")
            }
        }
    }
}

data class OpenAiCompatibleConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String? = null,
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 90_000,
)
