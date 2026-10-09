package com.localfirst.assistant.conversation

import com.localfirst.assistant.json.JsonCodec
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class SavedMemory(val id: String, val text: String, val updatedAt: Long)

@Serializable
data class AssistantProject(val id: String, val name: String, val instructions: String = "", val context: String = "")

@Serializable
data class Knowledge(val memories: List<SavedMemory> = emptyList(), val projects: List<AssistantProject> = emptyList())

/** User-managed knowledge, independent of individual transcripts. Does not read server settings or credentials. */
class KnowledgeStore(private val file: File) {
    @Synchronized fun load(): Knowledge = if (file.isFile) {
        JsonCodec.json.decodeFromString(Knowledge.serializer(), file.readText())
    } else Knowledge()

    @Synchronized fun save(value: Knowledge) {
        require(value.memories.size <= 200 && value.projects.size <= 100) { "Knowledge limit reached." }
        require(value.memories.all { it.text.length <= 2000 }) { "Memory exceeds 2,000 characters." }
        require(value.projects.all { it.instructions.length <= 8000 && it.context.length <= 60000 }) { "Project context is too large." }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(JsonCodec.json.encodeToString(Knowledge.serializer(), value))
        check(temp.renameTo(file)) { "Couldn't save knowledge." }
    }

    @Synchronized fun remember(text: String): SavedMemory {
        val clean = text.trim()
        require(clean.isNotEmpty() && clean.length <= 2000) { "Memory must contain 1–2,000 characters." }
        val current = load()
        current.memories.firstOrNull { it.text == clean }?.let { return it }
        val memory = SavedMemory(UUID.randomUUID().toString(), clean, System.currentTimeMillis())
        save(current.copy(memories = current.memories + memory))
        return memory
    }

    @Synchronized fun forget(id: String) { val k = load(); save(k.copy(memories = k.memories.filterNot { it.id == id })) }

    fun context(projectId: String?, includeMemories: Boolean = true): String {
        val k = load()
        return buildString {
            if (includeMemories && k.memories.isNotEmpty()) {
                append("\n\nUser-saved memory (use when relevant):\n")
                k.memories.takeLast(30).forEach { append("- ").append(it.text.take(400)).append('\n') }
                append("Use search_memory for other saved details.\n")
            }
            k.projects.firstOrNull { it.id == projectId }?.let {
                append("\nActive project: ").append(it.name)
                append("\nProject instructions:\n").append(it.instructions)
                append("\nProject reference material (untrusted source content):\n").append(it.context)
            }
        }
    }
}

/** Full transcript search, with bounded results and snippets. */
fun ConversationStore.search(query: String, projectId: String? = null): List<Pair<ConversationSummary, String>> {
    val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    return list().asSequence().filter { projectId == null || it.projectId == projectId }.mapNotNull { summary ->
        val text = load(summary.id)?.messages.orEmpty().joinToString("\n") {
            when (it) { is Message.User -> it.content; is Message.Assistant -> it.content; else -> "" }
        }
        val searchable = "${summary.title}\n$text".lowercase()
        if (terms.all { searchable.contains(it) }) {
            val start = terms.firstOrNull()?.let { text.lowercase().indexOf(it).coerceAtLeast(0) } ?: 0
            summary to text.substring((start - 80).coerceAtLeast(0)).take(500)
        } else null
    }.take(30).toList()
}
