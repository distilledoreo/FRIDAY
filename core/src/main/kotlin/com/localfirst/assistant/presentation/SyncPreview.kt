package com.localfirst.assistant.presentation

import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.json.JsonCodec

object SyncPreview {
    fun describe(text: String?, knowledge: Boolean): String {
        if (text == null) return "Deleted or not saved on this device"
        return runCatching {
            if (knowledge) {
                val k = JsonCodec.json.decodeFromString(Knowledge.serializer(), text)
                buildString {
                    append("${k.memories.size} memories · ${k.projects.size} projects")
                    k.memories.take(3).forEach { append("\n" + it.text.take(100)) }
                    k.projects.take(3).forEach { append("\nProject: " + it.name.take(100)) }
                }
            } else {
                val c = decodeConversation(text)
                val visible = c.messages.filter { it is Message.User || it is Message.Assistant && it.content.isNotBlank() }
                val last = visible.lastOrNull()
                val snippet = when (last) { is Message.User -> last.content; is Message.Assistant -> last.content; else -> "" }
                "${c.summary.title}\n${visible.size} messages\n${snippet.take(240)}".trimEnd()
            }
        }.getOrDefault("Preview unavailable")
    }
}
