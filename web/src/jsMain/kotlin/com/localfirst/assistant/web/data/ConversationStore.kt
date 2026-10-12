package com.localfirst.assistant.web.data

import com.localfirst.assistant.conversation.Message
import kotlinx.browser.localStorage

/** A stored chat turn. Kept simple for localStorage; mapped to core's Message for the API call. */
data class ChatMessage(val role: String, val content: String) {
    fun toMessage(): Message = when (role) {
        "system" -> Message.System(content)
        "assistant" -> Message.Assistant(content)
        else -> Message.User(content)
    }
    companion object {
        fun of(m: Message): ChatMessage = ChatMessage(
            when (m) {
                is Message.System -> "system"
                is Message.Assistant -> "assistant"
                else -> "user"
            },
            when (m) {
                is Message.System -> m.content
                is Message.Assistant -> m.content
                is Message.User -> m.content
                else -> ""
            },
        )
    }
}

data class Conversation(val id: String, val title: String, val messages: List<ChatMessage>)

/** Conversations persisted in localStorage as JSON. */
class ConversationStore {
    private val key = "friday.conversations"

    fun load(): List<Conversation> = runCatching {
        val raw = localStorage.getItem(key) ?: return emptyList()
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(raw)
        (arr as? kotlinx.serialization.json.JsonArray)?.map { el ->
            val o = el as kotlinx.serialization.json.JsonObject
            val msgs = (o["messages"] as? kotlinx.serialization.json.JsonArray)?.map {
                val m = it as kotlinx.serialization.json.JsonObject
                ChatMessage(m["role"].toString().trim('"'), m["content"].toString().trim('"'))
            } ?: emptyList()
            Conversation(o["id"].toString().trim('"'), o["title"].toString().trim('"'), msgs)
        } ?: emptyList()
    }.getOrDefault(emptyList())

    fun save(list: List<Conversation>) {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(esc(c.id)).append("\",\"title\":\"").append(esc(c.title)).append("\",\"messages\":[")
            c.messages.forEachIndexed { j, m ->
                if (j > 0) sb.append(',')
                sb.append("{\"role\":\"").append(esc(m.role)).append("\",\"content\":\"").append(esc(m.content)).append("\"}")
            }
            sb.append("]}")
        }
        sb.append(']')
        runCatching { localStorage.setItem(key, sb.toString()) }
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", "\\t")
}
