package com.localfirst.assistant.client
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
interface WorkspaceActions {
    val state: StateFlow<WorkspaceState>
    fun setDraft(text: String)
    fun newChat(project: String? = null)
    fun select(id: String)
    fun scheduleTask(prompt: String, runAt: Long, intervalSeconds: Int): Job?
    fun taskAction(id: String, taskAction: String): Job?
    fun saveMemory(text: String, id: String? = null, category: String = "other", memoryScope: String = state.value.projectId.orEmpty(), pinned: Boolean = true): Job?
    fun forgetMemory(id: String): Job?
    fun memorySetting(key: String, enabled: Boolean): Job?
    fun moreSources(): Job?
    fun source(id: String): Job?
    fun closeSource()
    fun excludeSource(id: String, excluded: Boolean): Job?
    fun importOlderMemory(): Job?
    fun searchMemory(query: String): Job?
    fun moreMemories(): Job?
    fun saveProject(id: String?, name: String, instructions: String, context: String, baseline: WorkspaceState = state.value): Job?
    fun deleteProject(id: String): Job?
}
