package com.localfirst.assistant.client

import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.presentation.ReplyText

/** Message actions shared by Android's conversation session and the browser's PC jobs. */
object ChatEdits {
    /** Messages before [index] and the edited user message, which keeps its attachments; null when it can't be edited. */
    fun edit(messages: List<Message>, index: Int, text: String): List<Message>? {
        val original = messages.getOrNull(index) as? Message.User ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty() && original.attachments.isEmpty()) return null
        return messages.take(index) + original.copy(content = trimmed)
    }

    /** Everything through the latest user message, dropping the answer and its tool activity, to ask again. */
    fun regenerate(messages: List<Message>): List<Message>? {
        val lastUser = messages.indexOfLast { it is Message.User }
        return if (lastUser < 0) null else messages.take(lastUser + 1)
    }

    /** A new chat's messages: everything through user message [index]. */
    fun branch(messages: List<Message>, index: Int): List<Message>? {
        val prefix = messages.take(index + 1)
        return prefix.takeIf { it.isNotEmpty() && it.last() is Message.User }
    }

    fun branchTitle(title: String) = (title.ifBlank { "Chat" } + " (branch)").take(200)

    /** The question an answer replied to, for feedback. */
    fun questionBefore(messages: List<Message>, answerIndex: Int): String? =
        messages.take(answerIndex + 1).lastOrNull { it is Message.User }?.let { (it as Message.User).content }
}

/** A document drafted from an answer: edited beside the chat and saved to the computer's files. */
data class CanvasDoc(val title: String, val body: String) {
    fun named(title: String) = copy(title = title.take(80))
    fun edited(body: String) = copy(body = body.take(50000))
    val fileName get() = safeFileName(title, "Draft", "md")

    companion object {
        /** Opens an answer as plain text; null when there is nothing to edit. */
        fun from(chatTitle: String, answer: String): CanvasDoc? =
            ReplyText.plain(answer).trim().take(20000).takeIf { it.isNotBlank() }?.let { CanvasDoc(chatTitle.take(80), it) }
    }
}

/** A file name the computer and browsers accept, from a chat or document title. */
fun safeFileName(title: String, fallback: String, extension: String): String =
    title.ifBlank { fallback }.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { fallback }.take(80) + "." + extension

/** Answer feedback becomes a reviewed preference memory; tapping thumbs up or down is the explicit request. */
object AnswerFeedback {
    fun memory(question: String, answer: String, liked: Boolean): String =
        ((if (liked) "User liked this answer; reuse the style. " else "User disliked this answer; avoid this style. ") +
            "Q: ${question.trim().take(300)} A: ${ReplyText.plain(answer).trim().take(500)}").take(900)

    fun notice(liked: Boolean) = if (liked) "Noted — I'll reuse what worked." else "Noted — I'll avoid that style."
}
