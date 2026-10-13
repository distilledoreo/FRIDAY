package com.localfirst.assistant.client

import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.client.*
import com.localfirst.assistant.settings.AppearanceSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

data class WorkspaceState(
    val connected: Boolean = false, val busy: Boolean = false, val activeJob: String? = null,
    val activeId: String? = null, val draft: String = "", val projectId: String? = null, val query: String = "",
    val tasks: List<JsonObject> = emptyList(), val detachedReply: String? = null,
    val chats: List<SharedChat> = emptyList(), val items: List<SyncItem> = emptyList(), val knowledge: Knowledge = Knowledge(), val knowledgeRevision: Int = 0,
    val preferences: AppearanceSettings = AppearanceSettings(), val memories: List<JsonObject> = emptyList(), val memoryTotal: Int = 0, val memoryHasMore: Boolean = false,
    val archiveSources: List<JsonObject> = emptyList(), val archiveTotal: Int = 0, val sourcePreview: JsonObject? = null,
    val memorySettings: JsonObject = JsonObject(emptyMap()), val memoryError: String? = null, val error: String? = null, val status: String = "Connect to your computer",
) {
    val active get() = chats.firstOrNull { it.item.id == activeId }
    val canSend get() = connected && !busy && activeJob == null && draft.isNotBlank()
    val filteredChats get() = chats.filter { query.isBlank() || it.conversation.summary.title.contains(query, true) || it.conversation.messages.any { m ->
        when(m) { is Message.User -> m.content.contains(query, true); is Message.Assistant -> m.content.contains(query, true); else -> false }
    } }
}

/** Server revisions are the baseline for every write; refresh never touches an unsent draft. */
class WorkspaceController(
    private val repository: WorkspaceRepository, private val scope: CoroutineScope,
    private val newId: () -> String, private val clock: () -> Long,
) : WorkspaceActions {
    private val mutable = MutableStateFlow(WorkspaceState())
    override val state = mutable.asStateFlow()
    private var epoch = 0
    private var operation: Job? = null
    private var polling: Job? = null
    private var watching: Job? = null
    private var memoryStale = false
    private var memoryQuery = ""
    override fun setDraft(text: String) { mutable.update { it.copy(draft = text) } }
    fun search(text: String) { mutable.update { it.copy(query = text) } }
    fun selectProject(id: String?) { mutable.update { it.copy(projectId = id) } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    override fun newChat(project: String?) { if (!state.value.busy && state.value.activeJob == null) mutable.update { it.copy(activeId = null, draft = "", projectId = project, error = null, detachedReply = null) } }
    override fun select(id: String) {
        if (state.value.busy || state.value.activeJob != null) return
        val chat = state.value.chats.firstOrNull { it.item.id == id } ?: return
        mutable.update { it.copy(activeId = id, projectId = chat.conversation.summary.projectId, error = null, detachedReply = null) }
    }
    fun lock() {
        epoch++; memoryQuery = ""; memoryStale = false; operation?.cancel(); watching?.cancel(); repository.gateway.lock(); repository.forget(); mutable.value = WorkspaceState()
    }
    fun close() { lock(); polling?.cancel() }
    private fun action(quiet: Boolean = false, body: suspend (Int) -> Unit): Job? {
        if (state.value.busy) return null
        // The held background refresh gives way, so it never blocks or interleaves with something the user asked for.
        watching?.cancel()
        val identity = epoch
        mutable.update { it.copy(busy = true, error = if (quiet) it.error else null) }
        return scope.launch {
            try { body(identity) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (identity == epoch) mutable.update { it.copy(error = if (quiet) it.error else e.message ?: "Computer unavailable", status = "Sync paused; retry when the computer is available") } }
            finally { if (identity == epoch) mutable.update { it.copy(busy = false) } }
        }.also { operation = it }
    }
    fun connect(baseUrl: String, token: String) = action { identity ->
        repository.gateway.connect(baseUrl, token); repository.forget()
        refreshSnapshot(identity)
        if (identity == epoch) mutable.update { it.copy(connected = true) }
    }
    /** Keeps one held request open while visible and idle; the gateway answers it when another client changes something. */
    fun startPolling(visible: () -> Boolean) {
        polling?.cancel()
        polling = scope.launch {
            while (isActive) {
                if (!state.value.connected || state.value.busy || !visible()) { delay(250); continue }
                val identity = epoch; val started = clock(); var failed = false
                val call = launch {
                    try { refreshSnapshot(identity, 10) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failed = true; if (identity == epoch) mutable.update { it.copy(status = "Sync paused; retry when the computer is available") } }
                }
                watching = call; call.join(); watching = null
                // An answer that comes straight back (an error, or a gateway that cannot hold requests) must not become a tight loop.
                if (failed) delay(5000) else if (clock() - started < 1000) delay(1000)
            }
        }
    }
    fun refresh(quiet: Boolean = false) = action(quiet) { refreshSnapshot(it) }
    private suspend fun refreshSnapshot(identity: Int, waitSeconds: Int = 0) {
        val changes = repository.changes(waitSeconds)
        if (identity != epoch) return
        val snapshot = changes.snapshot
        mutable.update { old ->
            val jobs = changes.jobs ?: old.tasks
            val current = snapshot.chats.firstOrNull { it.item.id == old.activeId }
            val job = jobs.firstOrNull { it.text("id") == old.activeJob }
            val working = old.activeJob != null && job?.text("status") in listOf("running", "scheduled")
            old.copy(chats = snapshot.chats, tasks = jobs, items = snapshot.items, knowledge = snapshot.knowledge, knowledgeRevision = snapshot.knowledgeRevision,
                preferences = snapshot.preferences, activeId = current?.item?.id,
                projectId = if (old.activeId != null && current == null) old.projectId?.takeIf { id -> snapshot.knowledge.projects.any { it.id == id } } else old.projectId,
                activeJob = if (working) old.activeJob else null,
                detachedReply = if (old.activeJob != null && !working && job?.text("error")?.isNotBlank() == true) job.text("result").takeIf { it.isNotBlank() } else old.detachedReply,
                error = job?.text("error")?.takeIf { it.isNotBlank() } ?: old.error,
                status = if (working) "FRIDAY is working on the computer…" else "History and preferences synced")
        }
        // Stays set if the memory fetch is interrupted or fails, so the next refresh tries again.
        if (changes.memory) memoryStale = true
        if (memoryStale) refreshMemory(identity)
    }
    private suspend fun refreshMemory(identity: Int, limit: Int = maxOf(100, state.value.memories.size)) {
        try {
            val summary = repository.gateway.request("/workspace/memory").jsonObject
            val facts = mutableListOf<JsonObject>()
            var hasMore = true
            do {
                val pageSize = minOf(200, maxOf(1, limit - facts.size))
                val page = repository.gateway.request("/workspace/memory/memories?offset=${facts.size}&limit=$pageSize&q=${com.localfirst.assistant.platform.PlatformNet.encodeUrl(memoryQuery)}").jsonArray.map { it.jsonObject }
                facts.addAll(page)
                if (page.size < pageSize) { hasMore = false; break }
            } while (facts.size < limit)
            val sources = mutableListOf<JsonObject>()
            val sourceLimit = maxOf(50, state.value.archiveSources.size)
            do {
                val page = repository.gateway.request("/workspace/memory/sources?offset=${sources.size}").jsonArray.map { it.jsonObject }
                sources.addAll(page)
                if (page.size < 50) break
            } while (sources.size < sourceLimit)
            if (identity == epoch) mutable.update { it.copy(memories = facts, archiveSources = sources, archiveTotal = summary["sources"]?.jsonPrimitive?.intOrNull ?: sources.size, memoryTotal = summary["memories"]?.jsonPrimitive?.intOrNull ?: facts.size, memoryHasMore = hasMore,
                memorySettings = summary["settings"]?.jsonObject ?: JsonObject(emptyMap()), memoryError = null) }
            if (identity == epoch) memoryStale = false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (identity == epoch) mutable.update { it.copy(memoryError = "Shared memory is unavailable: ${e.message}") } }
    }
    fun send() = if (!state.value.canSend) null else action { identity ->
        val old = state.value; val now = clock(); val active = old.active
        val conversation = active?.conversation ?: StoredConversation(ConversationSummary(newId(), old.draft.trim().take(70), now, now, old.projectId), emptyList())
        val next = conversation.copy(summary = conversation.summary.copy(updatedAt = now, projectId = old.projectId), messages = conversation.messages + Message.User(old.draft.trim()))
        val job = repository.continueChat(next, active?.item?.revision ?: 0, if (old.preferences.interactiveAnswers) clientInterfacePrompt() else "")
        if (identity != epoch) return@action
        val item = SyncItem(next.summary.id, job.getValue("revision").jsonPrimitive.int, buildJsonObject { put("chat", encodeConversation(next)) })
        mutable.update { it.copy(activeId = item.id, chats = listOf(SharedChat(item, next)) + it.chats.filterNot { c -> c.item.id == item.id },
            draft = "", activeJob = job.text("id"), status = "FRIDAY is working on the computer…") }
    }
    override fun scheduleTask(prompt: String, runAt: Long, intervalSeconds: Int) = action { identity -> repository.scheduleTask(prompt, runAt, intervalSeconds); refreshSnapshot(identity) }
    override fun taskAction(id: String, taskAction: String) = action { identity ->
        repository.taskAction(id, taskAction)
        refreshSnapshot(identity)
    }
    fun stop() = action { identity ->
        val job = state.value.activeJob ?: return@action
        repository.gateway.request("/workspace/jobs/$job/cancel", "POST")
        refreshSnapshot(identity)
    }
    fun rename(title: String, baseline: SharedChat? = state.value.active) = action { identity ->
        val chat = baseline ?: return@action
        require(title.trim().isNotBlank()) { "Enter a chat title." }
        repository.save(chat.conversation.copy(summary = chat.conversation.summary.copy(title = title.trim().take(200), updatedAt = clock())), chat.item.revision)
        refreshSnapshot(identity)
    }
    fun delete() = action { identity ->
        val chat = state.value.active ?: return@action
        repository.delete(chat); refreshSnapshot(identity)
    }
    fun preference(key: String, value: JsonPrimitive, expectedRevision: Int? = null) = action { identity -> repository.preference(key, value, state.value.items, expectedRevision); refreshSnapshot(identity) }
    override fun saveMemory(text: String, id: String?, category: String, memoryScope: String, pinned: Boolean) = action { identity ->
        repository.saveMemory(text, id, category, memoryScope, pinned); refreshMemory(identity)
    }
    override fun forgetMemory(id: String) = action { identity -> repository.forgetMemory(id); refreshMemory(identity) }
    override fun memorySetting(key: String, enabled: Boolean) = action { identity ->
        repository.memorySetting(key, enabled); refreshMemory(identity)
    }
    fun artifact(id: String) = action {
        require(Regex("[a-f0-9]{32}").matches(id)) { "Invalid artifact link." }
        repository.gateway.artifact(id)
    }
    override fun moreSources() = action { identity ->
        val old = state.value.archiveSources
        val page = repository.gateway.request("/workspace/memory/sources?offset=${old.size}").jsonArray.map { it.jsonObject }
        if (identity == epoch) mutable.update { it.copy(archiveSources = (old + page).distinctBy { row -> row.text("id") }) }
    }
    override fun source(id: String) = action { identity ->
        val preview = repository.source(id)
        if (identity == epoch) mutable.update { it.copy(sourcePreview = preview) }
    }
    override fun closeSource() { mutable.update { it.copy(sourcePreview = null) } }
    override fun excludeSource(id: String, excluded: Boolean) = action { identity ->
        repository.excludeSource(id, excluded); refreshMemory(identity)
    }
    override fun importOlderMemory() = action { identity ->
        val old = state.value
        val rows = buildJsonArray { old.knowledge.memories.forEach { add(buildJsonObject { put("text", it.text) }) } }
        val result = repository.gateway.request("/workspace/memory/legacy", "POST", buildJsonObject { put("memories", rows) }).jsonObject
        check(result["skipped"]?.jsonPrimitive?.intOrNull == 0) { "Some memories could not be imported; originals were retained." }
        repository.knowledge(old.knowledge.copy(memories = emptyList()), old.knowledgeRevision)
        refreshSnapshot(identity)
    }
    override fun searchMemory(query: String) = action { identity -> memoryQuery = query.take(2000); refreshMemory(identity, 100) }
    override fun moreMemories() = action { identity ->
        val old = state.value.memories
        val page = repository.gateway.request("/workspace/memory/memories?offset=${old.size}&limit=100&q=${com.localfirst.assistant.platform.PlatformNet.encodeUrl(memoryQuery)}").jsonArray.map { it.jsonObject }
        if (identity == epoch) mutable.update { it.copy(memories = (old + page).distinctBy { row -> row.text("id") }, memoryHasMore = page.size == 100) }
    }
    override fun saveProject(id: String?, name: String, instructions: String, context: String, baseline: WorkspaceState) = action { identity ->
        repository.knowledge(baseline.knowledge.withProject(id ?: newId(), name, instructions, context), baseline.knowledgeRevision)
        refreshSnapshot(identity)
    }
    override fun deleteProject(id: String) = action { identity ->
        val old = state.value
        repository.knowledge(old.knowledge.copy(projects = old.knowledge.projects.filterNot { it.id == id }), old.knowledgeRevision)
        refreshSnapshot(identity)
    }
    fun importLegacy(chats: List<LegacyConversation>, idFor: (String) -> String) = action { identity ->
        var imported = 0
        for (chat in chats) {
            if (identity != epoch) return@action
            val id = idFor(chat.id)
            if (repository.snapshot().items.any { it.id == id }) continue
            val now = clock()
            val messages = chat.messages.mapNotNull { m -> when(m.role) { "user" -> Message.User(m.content); "assistant" -> Message.Assistant(m.content); else -> null } }
            repository.save(StoredConversation(ConversationSummary(id, chat.title, now, now), messages), 0); imported++
        }
        refreshSnapshot(identity)
        if (identity == epoch) mutable.update { it.copy(status = "Imported $imported older browser chats") }
    }
}
