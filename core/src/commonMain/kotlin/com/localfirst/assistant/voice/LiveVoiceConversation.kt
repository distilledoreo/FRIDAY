package com.localfirst.assistant.voice

import com.localfirst.assistant.conversation.ConversationSummary
import com.localfirst.assistant.conversation.StoredConversation

/** A single synchronized conversation for normal live voice, independent of the open text chat. */
object LiveVoiceConversation {
    const val ID = "d9b5c8e4-71b8-4fc0-a42f-e92360ce9481"
    const val TITLE = "Live voice"
    const val INSTRUCTIONS = "This is a live spoken conversation. Start with the answer or a concrete suggestion. " +
        "Skip greetings and generic acknowledgments. Use short, natural sentences. " +
        "Keep using the conversation history, memory, tools and required approvals normally."

    fun empty(now: Long) = StoredConversation(ConversationSummary(ID, TITLE, now, now), emptyList())
    fun instructions(id: String?, incognito: Boolean = false): String =
        if (id == ID && !incognito) "\n\n$INSTRUCTIONS" else ""
}
