package com.localfirst.assistant.ui
import androidx.compose.runtime.*
import com.localfirst.assistant.client.*
import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.settings.AppearanceSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.json.JSONObject

internal class NativeWorkspaceActions(private val vm: ChatViewModel, private val scope: CoroutineScope) : WorkspaceActions {
    private val mutable = MutableStateFlow(WorkspaceState())
    override val state = mutable.asStateFlow()
    private var appearance = AppearanceSettings()
    fun update(native: ChatUiState, preferences: AppearanceSettings = appearance) {
        appearance = preferences
        fun JSONObject.wire() = wireJson.parseToJsonElement(toString()).jsonObject
        val summary = native.memorySummary
        mutable.value = WorkspaceState(connected = true, busy = native.workspaceBusy || native.busy,
            activeId = native.conversationId, projectId = native.projectId, draft = native.draft,
            knowledge = native.knowledge.copy(memories = emptyList()), preferences = preferences,
            chats = native.conversations.map { SharedChat(SyncItem(it.id, 0), StoredConversation(it, emptyList())) },
            memories = native.pcMemories.map { it.wire() }, memoryTotal = summary?.optInt("memories") ?: 0, memoryHasMore = native.pcMemoriesMore,
            archiveSources = native.archiveSources.map { it.wire() }, archiveTotal = summary?.optInt("sources") ?: 0,
            sourcePreview = native.archiveSource?.wire(),
            memorySettings = summary?.optJSONObject("settings")?.wire() ?: JsonObject(emptyMap()),
            memoryError = if (summary == null) native.workspaceStatus ?: "Loading shared memory…" else null,
            error = native.workspaceError, notice = native.workspaceStatus,
            memorySummary = summary?.wire() ?: JsonObject(emptyMap()),
            suggestions = native.memorySuggestions.map { it.wire() }, situations = native.memorySituations.map { it.wire() },
            importPreview = native.importPreview?.wire(), archiveQuery = native.archiveQuery,
            recall = native.recallSources.map { it.wire() }.takeIf { native.recallStatus != null }, recallStatus = native.recallStatus,
            tasks = native.tasks.map { task -> buildJsonObject {
                put("id", task.id); put("prompt", task.prompt); put("status", task.status)
                put("result", task.result); put("error", task.error)
            } })
    }
    private fun track(job: Job?): Job? = job?.let { scope.launch { it.join(); update(vm.state.value) } }
    override fun setDraft(text: String) { vm.onDraftChange(text) }
    override fun newChat(project: String?) { if (project == null) vm.newChat() else vm.newProjectChat(project) }
    override fun select(id: String) { vm.dismissWorkspace(); vm.openConversation(id) }
    override fun scheduleTask(prompt: String, runAt: Long, intervalSeconds: Int) = track(vm.scheduleTaskAt(prompt, runAt, intervalSeconds))
    override fun taskAction(id: String, taskAction: String) = track(if (taskAction == "delete") vm.removeTask(id) else vm.manageTask(id, taskAction))
    override fun saveMemory(text: String, id: String?, category: String, memoryScope: String, pinned: Boolean) =
        track(vm.saveMemory(text, id, category, memoryScope, pinned))
    override fun forgetMemory(id: String) = track(vm.forgetMemory(id))
    override fun memorySetting(key: String, enabled: Boolean) = track(vm.setMemorySetting(key, enabled))
    override fun moreSources() = track(vm.loadMoreArchiveSources())
    override fun source(id: String) = track(vm.openMemorySource(id))
    override fun closeSource() { vm.closeMemorySource(); update(vm.state.value) }
    override fun excludeSource(id: String, excluded: Boolean) = track(vm.excludeMemorySource(id, excluded))
    override fun importOlderMemory(): Job? = null // Native refresh already migrates older saved memories.
    override fun searchMemory(query: String) = track(vm.refreshMemory(query))
    override fun moreMemories(): Job? { vm.loadMoreMemories(); return null }
    override fun saveProject(id: String?, name: String, instructions: String, context: String, baseline: WorkspaceState) = track(vm.saveProject(id, name, instructions, context, baseline.knowledge.projects))
    override fun deleteProject(id: String) = track(vm.deleteProject(id))
    override fun reviewSuggestion(id: String, accept: Boolean, text: String?) = track(vm.reviewMemory(id, accept, text))
    override fun resolveSituation(id: String) = track(vm.resolveSituation(id))
    override fun forgetSituation(id: String) = track(vm.forgetSituation(id))
    override fun searchArchive(query: String) = track(vm.refreshArchive(query))
    override fun deleteSource(id: String) = track(vm.deleteMemorySource(id))
    override fun extractSource(id: String) = track(vm.extractMemorySource(id))
    override fun continueSource(): Job? { vm.continueMemorySource(); return null }
    override fun commitImport(extract: Boolean) = track(vm.commitChatGptImport(extract))
    override fun discardImport() = track(vm.discardChatGptImport())
    override fun refreshMemoryReview() = track(vm.refreshMemory())
}

@Composable
internal fun rememberNativeWorkspaceActions(vm: ChatViewModel, native: ChatUiState): NativeWorkspaceActions {
    val scope = rememberCoroutineScope()
    val appearance = com.localfirst.assistant.ui.theme.LocalAppearance.current
    val actions = remember(vm, scope) { NativeWorkspaceActions(vm, scope).also { it.update(native, appearance) } }
    SideEffect { actions.update(native, appearance) }
    return actions
}
