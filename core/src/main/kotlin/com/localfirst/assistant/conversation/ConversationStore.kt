package com.localfirst.assistant.conversation

import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.tools.SourceLink
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

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

/** Saved chats on the device. System messages are not stored; the app adds its prompt per request. */
interface ConversationStore {
    /** Newest first. */
    fun list(): List<ConversationSummary>
    fun load(id: String): StoredConversation?
    fun save(conversation: StoredConversation)
    fun rename(id: String, title: String)
    fun delete(id: String)
}

/**
 * One JSON file per conversation plus `index.json` with the summaries, so the
 * chat list loads without reading every transcript. Writes go to a temp file
 * first and are renamed into place.
 */
class FileConversationStore(
    private val directory: File,
) : ConversationStore {
    private val lock = Any()

    override fun list(): List<ConversationSummary> = synchronized(lock) {
        readIndex().sortedByDescending { it.updatedAt }
    }

    override fun load(id: String): StoredConversation? = synchronized(lock) {
        val file = conversationFile(id)
        if (!file.isFile) return null
        val dto = try {
            JsonCodec.json.decodeFromString(ConversationDto.serializer(), file.readText())
        } catch (e: Exception) {
            return null
        }
        dto.toModel()
    }

    override fun save(conversation: StoredConversation) = synchronized(lock) {
        val dto = ConversationDto.from(conversation)
        writeAtomically(
            conversationFile(conversation.summary.id),
            JsonCodec.json.encodeToString(ConversationDto.serializer(), dto),
        )
        writeIndex(readIndex().filterNot { it.id == conversation.summary.id } + conversation.summary)
    }

    override fun rename(id: String, title: String) = synchronized(lock) {
        val stored = load(id) ?: return
        save(stored.copy(summary = stored.summary.copy(title = title)))
    }

    override fun delete(id: String) = synchronized(lock) {
        conversationFile(id).delete()
        writeIndex(readIndex().filterNot { it.id == id })
    }

    private fun readIndex(): List<ConversationSummary> {
        val file = File(directory, INDEX_FILE)
        if (file.isFile) {
            try {
                return JsonCodec.json.decodeFromString(ListSerializer(SummaryDto.serializer()), file.readText())
                    .map { it.toModel() }
            } catch (e: Exception) {
                // Fall through and rebuild from the conversation files.
            }
        }
        val rebuilt = directory.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { f -> load(f.name.removePrefix(PREFIX).removeSuffix(".json"))?.summary }
        if (rebuilt.isNotEmpty()) writeIndex(rebuilt)
        return rebuilt
    }

    private fun writeIndex(summaries: List<ConversationSummary>) {
        writeAtomically(
            File(directory, INDEX_FILE),
            JsonCodec.json.encodeToString(ListSerializer(SummaryDto.serializer()), summaries.map(SummaryDto::from)),
        )
    }

    private fun conversationFile(id: String): File {
        require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it == '-' }) { "Invalid conversation id." }
        return File(directory, "$PREFIX$id.json")
    }

    private fun writeAtomically(target: File, text: String) {
        directory.mkdirs()
        val temp = File(directory, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Could not save ${target.name}." }
        }
    }

    private companion object {
        const val INDEX_FILE = "index.json"
        const val PREFIX = "chat-"
    }
}

@Serializable
private data class SummaryDto(
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
private data class ConversationDto(
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
private data class SourceDto(val title: String, val url: String, val contentRead: Boolean = false)

@Serializable
private data class AttachmentDto(
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
private data class MessageDto(
    val role: String,
    val content: String = "",
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val success: Boolean? = null,
    val sources: List<SourceDto> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
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
            )
        }
    }
}
