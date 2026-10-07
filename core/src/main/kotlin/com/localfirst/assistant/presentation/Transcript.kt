package com.localfirst.assistant.presentation

import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.tools.SourceLink
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What the chat screen shows, derived from conversation messages. UI-framework
 * free so it can be unit-tested. Indexes point back into the message list.
 */
sealed interface TranscriptItem {
    val key: String

    data class User(val index: Int, val text: String) : TranscriptItem {
        override val key = "u$index"
    }

    data class Assistant(
        val index: Int,
        val text: String,
        /** Still receiving text. */
        val streaming: Boolean,
        /** The latest answer in the chat, which can be regenerated. */
        val isLatest: Boolean,
    ) : TranscriptItem {
        override val key = "a$index"
    }

    /** Consecutive tool calls with their results, shown as one card. [index] is the first call's position. */
    data class ToolActivity(val index: Int, val steps: List<ToolStep>) : TranscriptItem {
        override val key = "t$index"
    }

    /** The model is working and nothing visible has arrived yet. */
    data object Thinking : TranscriptItem {
        override val key = "thinking"
    }
}

data class ToolStep(
    val callId: String,
    val name: String,
    val state: ToolStepState,
    /** Short sentence for the card, e.g. "Searched the web for “SearXNG”". */
    val label: String,
    /** Failure reason when [state] is [ToolStepState.FAILED]. */
    val detail: String? = null,
    val sources: List<SourceLink> = emptyList(),
)

enum class ToolStepState { RUNNING, DONE, FAILED }

object Transcript {
    fun items(messages: List<Message>, busy: Boolean): List<TranscriptItem> {
        val out = mutableListOf<TranscriptItem>()
        val pendingSteps = mutableListOf<ToolStep>()
        var pendingStart = -1
        fun flushSteps() {
            if (pendingSteps.isNotEmpty()) {
                out += TranscriptItem.ToolActivity(pendingStart, pendingSteps.toList())
                pendingSteps.clear()
            }
        }

        val lastAssistantIndex = messages.indexOfLast { it is Message.Assistant && it.content.isNotBlank() }
        messages.forEachIndexed { index, message ->
            when (message) {
                is Message.System -> Unit
                is Message.User -> {
                    flushSteps()
                    out += TranscriptItem.User(index, message.content)
                }
                is Message.Assistant -> {
                    if (message.content.isBlank()) return@forEachIndexed
                    flushSteps()
                    val last = index == messages.lastIndex
                    out += TranscriptItem.Assistant(
                        index = index,
                        text = message.content,
                        streaming = busy && last,
                        isLatest = index == lastAssistantIndex && messages.drop(index + 1).none { it is Message.User },
                    )
                }
                is Message.ToolCall -> {
                    if (pendingSteps.isEmpty()) pendingStart = index
                    pendingSteps += step(message, resultFor(messages, index))
                }
                is Message.ToolResult -> Unit
            }
        }
        flushSteps()

        if (busy && needsThinkingIndicator(out.lastOrNull())) out += TranscriptItem.Thinking
        return out
    }

    /**
     * The first result after the call at [callIndex] with the same id. Ids are
     * matched by position because servers that omit ids get "call_0" each round.
     */
    private fun resultFor(messages: List<Message>, callIndex: Int): Message.ToolResult? {
        val id = (messages[callIndex] as Message.ToolCall).id
        for (i in callIndex + 1 until messages.size) {
            val message = messages[i]
            if (message is Message.User) return null
            if (message is Message.ToolResult && message.toolCallId == id) return message
        }
        return null
    }

    private fun needsThinkingIndicator(last: TranscriptItem?): Boolean = when (last) {
        null, is TranscriptItem.User -> true
        is TranscriptItem.ToolActivity -> last.steps.none { it.state == ToolStepState.RUNNING }
        is TranscriptItem.Assistant -> !last.streaming
        TranscriptItem.Thinking -> false
    }

    internal fun step(call: Message.ToolCall, result: Message.ToolResult?): ToolStep {
        val args = try {
            JsonCodec.json.parseToJsonElement(call.argumentsJson) as? JsonObject
        } catch (e: Exception) {
            null
        }
        val state = when {
            result == null -> ToolStepState.RUNNING
            result.success -> ToolStepState.DONE
            else -> ToolStepState.FAILED
        }
        val label = when (call.name) {
            "web_search" -> {
                val query = (args?.get("query") as? JsonPrimitive)?.contentOrNull?.trim()
                val subject = if (query.isNullOrEmpty()) "" else " for “$query”"
                when (state) {
                    ToolStepState.RUNNING -> "Searching the web$subject…"
                    ToolStepState.DONE -> "Searched the web$subject"
                    ToolStepState.FAILED -> "Web search failed$subject"
                }
            }
            "set_media_volume" -> {
                val level = (args?.get("level") as? JsonPrimitive)?.intOrNull
                val amount = if (level == null) "" else " to $level%"
                when (state) {
                    ToolStepState.RUNNING -> "Setting media volume$amount…"
                    ToolStepState.DONE -> "Set media volume$amount"
                    ToolStepState.FAILED -> "Couldn't set media volume$amount"
                }
            }
            else -> when (state) {
                ToolStepState.RUNNING -> "Running ${call.name}…"
                ToolStepState.DONE -> "Used ${call.name}"
                ToolStepState.FAILED -> "${call.name} failed"
            }
        }
        return ToolStep(
            callId = call.id,
            name = call.name,
            state = state,
            label = label,
            detail = result?.takeIf { !it.success }?.content,
            sources = result?.sources.orEmpty(),
        )
    }
}

/** Drawer sections, newest first, like "Today" and "Previous 7 days". */
object ConversationGroups {
    const val TODAY = "Today"
    const val YESTERDAY = "Yesterday"
    const val LAST_WEEK = "Previous 7 days"
    const val LAST_MONTH = "Previous 30 days"
    const val OLDER = "Older"

    /**
     * [startOfToday] is local midnight in epoch millis; passing it in keeps
     * this independent of the device clock and time zone.
     */
    fun label(updatedAt: Long, startOfToday: Long): String {
        val day = 24L * 60 * 60 * 1000
        return when {
            updatedAt >= startOfToday -> TODAY
            updatedAt >= startOfToday - day -> YESTERDAY
            updatedAt >= startOfToday - 7 * day -> LAST_WEEK
            updatedAt >= startOfToday - 30 * day -> LAST_MONTH
            else -> OLDER
        }
    }
}
