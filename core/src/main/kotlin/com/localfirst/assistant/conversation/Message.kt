package com.localfirst.assistant.conversation

import com.localfirst.assistant.tools.SourceLink

/**
 * Explicit conversation entries. Tool activity is never stored as a user message.
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL_CALL,
    TOOL_RESULT,
    SYSTEM,
}

sealed interface Message {
    val role: MessageRole

    data class System(val content: String) : Message {
        override val role: MessageRole = MessageRole.SYSTEM
    }

    data class User(val content: String) : Message {
        override val role: MessageRole = MessageRole.USER
    }

    data class Assistant(val content: String) : Message {
        override val role: MessageRole = MessageRole.ASSISTANT
    }

    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
    ) : Message {
        override val role: MessageRole = MessageRole.TOOL_CALL
    }

    data class ToolResult(
        val toolCallId: String,
        val name: String,
        val content: String,
        val success: Boolean,
        /** Display-only links (for example, search sources). Not sent to the model. */
        val sources: List<SourceLink> = emptyList(),
    ) : Message {
        override val role: MessageRole = MessageRole.TOOL_RESULT
    }
}
