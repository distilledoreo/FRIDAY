package com.localfirst.assistant.conversation

import com.localfirst.assistant.json.JsonCodec
import java.io.File
import kotlinx.serialization.builtins.ListSerializer

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
