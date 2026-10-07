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

    data class User(
        val content: String,
        /** Images and documents sent with this message. */
        val attachments: List<Attachment> = emptyList(),
    ) : Message {
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

enum class AttachmentKind { IMAGE, DOCUMENT }

/**
 * A file sent with a user message. Images are stored on the device as JPEG at
 * [path]. Documents carry the [text] the computer extracted from them, or, for
 * scans without a text layer, [pageImages] (JPEG paths) for the vision model.
 */
data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val name: String,
    val mimeType: String,
    val path: String? = null,
    val text: String? = null,
    val pageImages: List<String> = emptyList(),
    /** Shown with the attachment and passed to the model, e.g. "Text cut at 60,000 characters." */
    val note: String? = null,
) {
    /** Every image the model should see for this attachment. */
    val imagePaths: List<String> get() = if (kind == AttachmentKind.IMAGE) listOfNotNull(path) else pageImages
}
