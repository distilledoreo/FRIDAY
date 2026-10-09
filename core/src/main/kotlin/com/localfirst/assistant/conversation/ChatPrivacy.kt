package com.localfirst.assistant.conversation

/** Privacy applies to checkpoints, tools and retrieval, independently of model behavior. */
data class ChatPrivacy(val incognito: Boolean = false, val freshSlate: Boolean = false) {
    val persist: Boolean get() = !incognito
    val recall: Boolean get() = !freshSlate
    suspend fun checkpoint(action: suspend () -> Unit) { if (persist) action() }
    fun allowsTool(name: String): Boolean {
        if (!incognito) return true
        if (name in setOf("remember", "forget_memory", "search_history", "schedule_task", "list_tasks", "manage_task", "execute_python", "run_on_pc", "get_pc_task", "schedule_pc_task", "list_pc_schedules", "propose_agent_task", "list_agent_activity", "get_agent_report", "list_connected_accounts", "read_account_inbox", "read_account_message", "read_account_calendar", "read_account_calendar_event", "propose_outgoing_action", "read_daily_brief", "list_followups", "save_followup")) return false
        return name != "search_memory" || recall
    }
}
