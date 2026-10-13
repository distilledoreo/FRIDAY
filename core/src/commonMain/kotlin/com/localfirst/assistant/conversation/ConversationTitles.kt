package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.ModelResponse
import kotlinx.coroutines.CancellationException

/** Chat titles for the conversation list. */
object ConversationTitles {
    const val MAX_LENGTH = 48
    const val NEW_CHAT = "New chat"

    /** Placeholder from the first user message; falls back to the first attachment's name. */
    fun fromFirstMessage(message: Message.User): String {
        if (message.content.isNotBlank() || message.attachments.isEmpty()) return fromFirstMessage(message.content)
        val first = message.attachments.first()
        return clip(if (first.kind == AttachmentKind.IMAGE) "Image" else first.name)
    }

    /** Placeholder from the first user message, used until (or instead of) a model title. */
    fun fromFirstMessage(text: String): String {
        val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return clip(line).ifEmpty { NEW_CHAT }
    }

    /**
     * Asks [provider] for a short title. Returns null on any failure so callers
     * keep their placeholder; titles are never worth an error message.
     */
    suspend fun generate(provider: ModelProvider, firstUser: String, firstAssistant: String): String? {
        val prompt = buildString {
            append("Write a title of 2 to 6 words for this chat. Reply with the title only: ")
            append("no quotes, no trailing punctuation.\n\nUser: ")
            append(firstUser.take(600))
            if (firstAssistant.isNotBlank()) {
                append("\n\nAssistant: ")
                append(firstAssistant.take(600))
            }
        }
        val response = try {
            provider.sendConversation(listOf(Message.User(prompt)), tools = emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val text = (response as? ModelResponse.TextResponse)?.text ?: return null
        return sanitize(text)
    }

    internal fun sanitize(raw: String): String? {
        val line = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val cleaned = line
            .removePrefix("Title:").removePrefix("title:")
            .trim()
            .trim('"', '\'', '“', '”', '*', '#', '`')
            .trimEnd('.', '!', ':', ';')
            .trim()
        return clip(cleaned).ifEmpty { null }
    }

    private fun clip(text: String): String {
        if (text.length <= MAX_LENGTH) return text
        val cut = text.take(MAX_LENGTH)
        val space = cut.lastIndexOf(' ')
        return (if (space > MAX_LENGTH / 2) cut.take(space) else cut).trimEnd() + "…"
    }
}
