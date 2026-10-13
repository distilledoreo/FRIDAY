package com.localfirst.assistant.conversation

import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.tools.SourceLink
import kotlinx.serialization.Serializable

data class ConversationSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val projectId: String? = null,
)

data class StoredConversation(
    val summary: ConversationSummary,
    val messages: List<Message>,
)

fun encodeConversation(value: StoredConversation): String =
    JsonCodec.json.encodeToString(ConversationDto.serializer(), ConversationDto.from(value))

fun decodeConversation(value: String): StoredConversation =
    JsonCodec.json.decodeFromString(ConversationDto.serializer(), value).toModel()

/** Transcript entries in the same format, such as the steps a PC job has taken so far; unknown roles are skipped. */
fun decodeMessages(value: String): List<Message> =
    JsonCodec.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(MessageDto.serializer()), value).mapNotNull { it.toModel() }

/** Saved chats on the device. System messages are not stored; the app adds its prompt per request. */
interface ConversationStore {
    /** Newest first. */
    fun list(): List<ConversationSummary>
    fun load(id: String): StoredConversation?
    fun save(conversation: StoredConversation)
    fun rename(id: String, title: String)
    fun delete(id: String)
}

@Serializable
internal data class SummaryDto(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val projectId: String? = null,
) {
    fun toModel() = ConversationSummary(id, title, createdAt, updatedAt, projectId)

    companion object {
        fun from(s: ConversationSummary) = SummaryDto(s.id, s.title, s.createdAt, s.updatedAt, s.projectId)
    }
}

@Serializable
internal data class ConversationDto(
    val version: Int = 1,
    val summary: SummaryDto,
    val messages: List<MessageDto>,
) {
    fun toModel() = StoredConversation(summary.toModel(), messages.mapNotNull { it.toModel() })

    companion object {
        fun from(c: StoredConversation) = ConversationDto(
            summary = SummaryDto.from(c.summary),
            messages = c.messages.mapNotNull(MessageDto::from),
        )
    }
}

@Serializable
internal data class SourceDto(val title: String, val url: String, val contentRead: Boolean = false)

@Serializable
internal data class AttachmentDto(
    val id: String,
    val kind: String,
    val name: String,
    val mimeType: String,
    val path: String? = null,
    val text: String? = null,
    val pageImages: List<String> = emptyList(),
    val note: String? = null,
    val remoteFileId: String? = null,
) {
    fun toModel() = Attachment(
        id = id,
        kind = if (kind == "document") AttachmentKind.DOCUMENT else AttachmentKind.IMAGE,
        name = name,
        mimeType = mimeType,
        path = path,
        text = text,
        pageImages = pageImages,
        note = note,
        remoteFileId = remoteFileId,
    )

    companion object {
        fun from(a: Attachment) = AttachmentDto(
            id = a.id,
            kind = if (a.kind == AttachmentKind.DOCUMENT) "document" else "image",
            name = a.name,
            mimeType = a.mimeType,
            path = a.path,
            text = a.text,
            pageImages = a.pageImages,
            note = a.note,
            remoteFileId = a.remoteFileId,
        )
    }
}

@Serializable
internal data class MessageDto(
    val role: String,
    val content: String = "",
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val success: Boolean? = null,
    val sources: List<SourceDto> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
    val image: String? = null,
) {
    fun toModel(): Message? = when (role) {
        "user" -> Message.User(content, attachments.map { it.toModel() })
        "assistant" -> Message.Assistant(content)
        "tool_call" -> Message.ToolCall(id = id.orEmpty(), name = name.orEmpty(), argumentsJson = arguments ?: "{}")
        "tool_result" -> Message.ToolResult(
            toolCallId = id.orEmpty(),
            name = name.orEmpty(),
            content = content,
            success = success ?: false,
            sources = sources.map { SourceLink(it.title, it.url, it.contentRead) },
            image = image,
        )
        else -> null
    }

    companion object {
        fun from(message: Message): MessageDto? = when (message) {
            is Message.System -> null
            is Message.User -> MessageDto(
                role = "user",
                content = message.content,
                attachments = message.attachments.map(AttachmentDto::from),
            )
            is Message.Assistant -> MessageDto(role = "assistant", content = message.content)
            is Message.ToolCall -> MessageDto(
                role = "tool_call",
                id = message.id,
                name = message.name,
                arguments = message.argumentsJson,
            )
            is Message.ToolResult -> MessageDto(
                role = "tool_result",
                content = message.content,
                id = message.toolCallId,
                name = message.name,
                success = message.success,
                sources = message.sources.map { SourceDto(it.title, it.url, it.contentRead) },
                image = message.image,
            )
        }
    }
}
