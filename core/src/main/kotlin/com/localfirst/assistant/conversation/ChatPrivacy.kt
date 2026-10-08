package com.localfirst.assistant.conversation

/** Privacy applies to checkpoints, tools and retrieval, independently of model behavior. */
data class ChatPrivacy(val incognito: Boolean = false, val freshSlate: Boolean = false) {
    val persist: Boolean get() = !incognito
    val recall: Boolean get() = !freshSlate
    suspend fun checkpoint(action: suspend () -> Unit) { if (persist) action() }
    fun allowsTool(name: String): Boolean {
        if (!incognito) return true
        if (name in setOf("remember", "forget_memory", "search_history", "schedule_task", "list_tasks", "manage_task", "execute_python", "propose_agent_task", "list_agent_activity")) return false
        return name != "search_memory" || recall
    }
}
