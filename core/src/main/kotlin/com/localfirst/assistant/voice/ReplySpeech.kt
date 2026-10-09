package com.localfirst.assistant.voice

import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.presentation.UiBlocks

/** Each assistant bubble has its own speech cursor. Tool boundaries close a bubble. */
class ReplySpeech {
    private val chunks = mutableMapOf<Int, SpeechChunker>()

    fun update(messages: List<Message>, busy: Boolean): List<String> {
        val turn = messages.drop(messages.indexOfLast { it is Message.User } + 1)
        return turn.flatMapIndexed { index, message ->
            if (message !is Message.Assistant) emptyList()
            else chunks.getOrPut(index) { SpeechChunker() }.update(
                UiBlocks.plainText(message.content),
                final = !busy || index < turn.lastIndex,
            )
        }
    }
}

/** Voice-only acknowledgements, capped per turn and separated by genuine quiet time. */
class VoiceWaitFeedback(
    nowMillis: Long,
    private val quietMillis: Long = 6_000,
    private val intervalMillis: Long = 30_000,
) {
    private var silentSince = nowMillis
    private var lastFeedback: Long? = null
    private var count = 0

    fun audioFinished(nowMillis: Long) { silentSince = nowMillis }

    fun next(nowMillis: Long): String? {
        if (count >= 2 || nowMillis - silentSince < quietMillis ||
            lastFeedback?.let { nowMillis - it < intervalMillis } == true) return null
        lastFeedback = nowMillis
        return if (count++ == 0) "Give me a moment." else "I’m still working on that."
    }
}
