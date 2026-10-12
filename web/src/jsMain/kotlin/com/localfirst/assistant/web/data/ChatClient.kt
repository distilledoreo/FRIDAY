package com.localfirst.assistant.web.data

import com.localfirst.assistant.conversation.Message
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.w3c.fetch.RequestInit

/** OpenAI-compatible server settings, persisted in localStorage. */
data class ServerSettings(val baseUrl: String = "", val apiKey: String = "", val model: String = "") {
    val configured get() = baseUrl.isNotBlank() && model.isNotBlank()
}

class ServerSettingsStore {
    private fun load() = ServerSettings(
        localStorage.getItem("friday.server.baseUrl") ?: "",
        localStorage.getItem("friday.server.apiKey") ?: "",
        localStorage.getItem("friday.server.model") ?: "",
    )
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    fun save(s: ServerSettings) {
        localStorage.setItem("friday.server.baseUrl", s.baseUrl)
        localStorage.setItem("friday.server.apiKey", s.apiKey)
        localStorage.setItem("friday.server.model", s.model)
        mutable.value = s
    }
}

/** Talks to an OpenAI-compatible chat-completions endpoint over fetch. */
class ChatClient(private val settings: ServerSettings) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun complete(messages: List<Message>): String {
        val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
        val payload = buildJsonObject {
            put("model", settings.model)
            put("stream", false)
            putJsonArray("messages") {
                for (m in messages) when (m) {
                    is Message.System -> add(buildJsonObject { put("role", "system"); put("content", m.content) })
                    is Message.User -> add(buildJsonObject { put("role", "user"); put("content", m.content) })
                    is Message.Assistant -> add(buildJsonObject { put("role", "assistant"); put("content", m.content) })
                    else -> {}
                }
            }
        }
        val init = RequestInit(
            method = "POST",
            headers = buildJsonObject {
                put("Content-Type", "application/json")
                if (settings.apiKey.isNotBlank()) put("Authorization", "Bearer ${settings.apiKey}")
            },
            body = payload.toString(),
        )
        val response = window.fetch(url, init).await()
        if (!response.ok) {
            val text = response.text().await()
            throw IllegalStateException("Model server returned ${response.status}: ${text.take(300)}")
        }
        val root = json.parseToJsonElement(response.text().await()) as? JsonObject
            ?: throw IllegalStateException("Model server returned a response this app could not read.")
        val choices = root["choices"] as? JsonArray ?: throw IllegalStateException("Model server response had no choices.")
        val first = choices.firstOrNull() as? JsonObject ?: throw IllegalStateException("Model server returned no choices.")
        val message = first["message"] as? JsonObject
        val content = (message?.get("content") as? JsonPrimitive)?.contentOrNull
        return content ?: throw IllegalStateException("Model server response had no content.")
    }
}
