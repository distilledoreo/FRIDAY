package com.localfirst.assistant.client
import com.localfirst.assistant.conversation.*
data class MemoryControl(val key: String, val label: String, val default: Boolean = true)
val memoryControls = listOf(
    MemoryControl("use_memories", "Use approved memories"),
    MemoryControl("use_history", "Recall past conversations"),
    MemoryControl("archive_chats", "Archive chats for recall"),
    MemoryControl("suggest_from_chats", "Suggest memories from chats", false),
    MemoryControl("track_situations", "Track recent situations"),
    MemoryControl("allow_checkins", "Allow check-ins"),
)
fun Knowledge.withProject(id: String, name: String, instructions: String, context: String): Knowledge {
    require(name.trim().isNotEmpty() && instructions.length <= 8000 && context.length <= 60000) { "Check the project name and text limits." }
    val project = AssistantProject(id, name.trim().take(200), instructions, context)
    return copy(projects = projects.filterNot { it.id == id } + project)
}
