package com.localfirst.assistant.client

import com.localfirst.assistant.backup.ChatBackup
import com.localfirst.assistant.backup.Inflate
import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.voice.LiveVoiceConversation
import com.localfirst.assistant.grounding.GroundedEvidence
import com.localfirst.assistant.grounding.GroundingPrecheck
import com.localfirst.assistant.tools.SourceLink
import com.localfirst.assistant.tools.ToolExecutionResult
import com.localfirst.assistant.platform.PlatformNet
import com.localfirst.assistant.presentation.ChatExport
import com.localfirst.assistant.settings.AppearanceSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private val WORKING = setOf("running", "scheduled", "waiting")
private val WEB_TOOLS = setOf("web_search", "fetch_page")

data class WorkspaceState(
    val incognito: Boolean = false, val privateChat: StoredConversation? = null,
    val attachments: List<Attachment> = emptyList(), val streamingText: String = "",
    val connected: Boolean = false, val busy: Boolean = false, val activeJob: String? = null,
    val activeId: String? = null, val draft: String = "", val projectId: String? = null, val query: String = "",
    val tasks: List<JsonObject> = emptyList(), val detachedReply: String? = null,
    val chats: List<SharedChat> = emptyList(), val items: List<SyncItem> = emptyList(), val knowledge: Knowledge = Knowledge(), val knowledgeRevision: Int = 0,
    val preferences: AppearanceSettings = AppearanceSettings(), val memories: List<JsonObject> = emptyList(), val memoryTotal: Int = 0, val memoryHasMore: Boolean = false,
    val archiveSources: List<JsonObject> = emptyList(), val archiveTotal: Int = 0, val sourcePreview: JsonObject? = null,
    val memorySettings: JsonObject = JsonObject(emptyMap()), val memoryError: String? = null, val error: String? = null, val status: String = "Connect to your computer",
    /** Index of the user message being edited, if any. */
    val editingIndex: Int? = null,
    /** An answer open in the canvas editor, if any. */
    val canvas: CanvasDoc? = null,
    /** Steps the running job has taken in this chat so far, in the shared transcript format. */
    val liveEntries: List<Message> = emptyList(),
    /** The approval card the running job waits on: tool, title, prompt, approve label and call_id. */
    val approval: JsonObject? = null,
    /** Context recall supplied to the last reply in this session; null when no reply has used it. */
    val recall: List<JsonObject>? = null, val recallStatus: String? = null,
    val memorySummary: JsonObject = JsonObject(emptyMap()),
    val suggestions: List<JsonObject> = emptyList(), val situations: List<JsonObject> = emptyList(),
    /** A staged ChatGPT export awaiting confirmation. */
    val importPreview: JsonObject? = null, val archiveQuery: String = "",
    /** A short confirmation of the last completed action. */
    val notice: String? = null,
    /** Image mode: the draft describes an image. Settings stay on this device, as on the phone. */
    val imageMode: Boolean = false, val imageSettings: ImageSettings = ImageSettings(),
    /** As on the phone: source checking for the latest turn, its sources, and the question larger research could take on. */
    val groundingStatus: String? = null, val groundingSources: List<SourceLink> = emptyList(), val groundingResearch: String? = null,
    /** Incognito without saved memories. */
    val freshSlate: Boolean = false,
    /** The private stream a pending approval belongs to. */
    val privateSession: String? = null,
    /** The chosen model (this device's choice, as on the phone) and the computer's models; null uses its default. */
    val model: String? = null, val connection: String? = null, val models: List<String> = emptyList(),
) {
    val active get() = if (incognito) privateChat?.let { SharedChat(SyncItem(it.summary.id, 0), it) } else chats.firstOrNull { it.item.id == activeId }
    /** The open chat with the running job's steps so far. */
    val transcript get() = active?.conversation?.messages.orEmpty() + if (activeJob != null) liveEntries else emptyList()
    private val editingHasAttachments get() = (active?.conversation?.messages?.getOrNull(editingIndex ?: -1) as? Message.User)?.attachments?.isNotEmpty() == true
    val canAnswerApproval get() = approval != null && if (incognito) privateSession != null else !busy
    val canSend get() = connected && !busy && activeJob == null && (draft.isNotBlank() || attachments.isNotEmpty() || editingHasAttachments)
    /** Whether the open chat can be continued, regenerated or branched right now. */
    val idle get() = connected && !busy && activeJob == null
    val filteredChats get() = chats.filter { query.isBlank() || it.conversation.summary.title.contains(query, true) || it.conversation.messages.any { m ->
        when(m) { is Message.User -> m.content.contains(query, true); is Message.Assistant -> m.content.contains(query, true); else -> false }
    } }
}

/** Server revisions are the baseline for every write; refresh never touches an unsent draft. */
class WorkspaceController(
    private val repository: WorkspaceRepository, private val scope: CoroutineScope,
    private val newId: () -> String, private val clock: () -> Long,
    /** Saves a generated file on this device: chat exports and backups. */
    private val save: suspend (name: String, mime: String, bytes: ByteArray) -> Unit = { _, _, _ -> error("Saving files is unavailable on this client") },
    /** Expands compressed backup entries, such as those Android writes. */
    private val inflate: Inflate = Inflate { _, _ -> error("Compressed backups are unavailable on this client") },
) : WorkspaceActions {
    private val mutable = MutableStateFlow(WorkspaceState())
    override val state = mutable.asStateFlow()
    private var epoch = 0
    private var operation: Job? = null
    private var polling: Job? = null
    private var watching: Job? = null
    private var generation: Job? = null
    private var memoryStale = false
    private var memoryQuery = ""
    private var groundingAttempted = false
    private val drafts = SessionDrafts<Attachment>()
    private var beforeEdit: ComposerDraft<Attachment>? = null
    private fun composer(s: WorkspaceState) = ComposerDraft(s.draft, s.attachments, s.imageMode)
    private fun rememberDraft() {
        val s = state.value
        if (!s.incognito) drafts.put(s.activeId, s.projectId, DraftSnapshot(composer(s), s.editingIndex, beforeEdit))
    }
    private fun openDraft(id: String?, project: String?) {
        val saved = drafts.take(id, project)
        beforeEdit = saved?.beforeEdit
        mutable.update { cleared(it).copy(activeId = id, projectId = project,
            draft = saved?.composer?.text.orEmpty(), attachments = saved?.composer?.attachments.orEmpty(),
            imageMode = saved?.composer?.imageMode ?: false, editingIndex = saved?.editingIndex) }
    }
    private fun sentDraft(old: WorkspaceState) {
        if (!old.incognito) drafts.remove(old.activeId, old.projectId)
        beforeEdit = null
    }
    override fun setDraft(text: String) { mutable.update { it.copy(draft = text) } }
    fun search(text: String) { mutable.update { it.copy(query = text) } }
    fun selectProject(id: String?) { mutable.update { it.copy(projectId = id) } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun dismissNotice() { mutable.update { it.copy(notice = null) } }
    fun setModel(id: String?) { mutable.update { it.copy(model = id?.takeIf { m -> m.isNotBlank() }) } }
    fun setImageMode(enabled: Boolean) { mutable.update { it.copy(imageMode = enabled && !it.incognito) } }
    fun saveImageSettings(settings: ImageSettings) {
        settings.validate()?.let { reportError(it); return }
        mutable.update { it.copy(imageSettings = settings, notice = "Image settings saved.") }
    }
    /** Proposes public deep research on the draft for approval in FRIDAY; returns false with an error when it can't. */
    fun proposeResearch(features: FeatureController): Boolean {
        val (prompt, plan) = DeepResearch.proposal(state.value.draft).getOrElse { reportError(it.message ?: "Research unavailable"); return false }
        features.change("/workspace/agent/tasks", "POST", buildJsonObject { put("prompt", prompt); putJsonArray("plan") { plan.forEach { add(it) } } })
        return true
    }
    /** A saved chat's job keeps running on the computer; leaving it only stops following its progress here. */
    private fun canLeave() = !state.value.busy && !(state.value.incognito && state.value.activeJob != null)
    private fun detach() { generation?.cancel(); generation = null; repository.gateway.privateMode(false) }
    private fun cleared(s: WorkspaceState) = s.copy(error = null, detachedReply = null, incognito = false, privateChat = null, attachments = emptyList(), streamingText = "",
        activeJob = null, liveEntries = emptyList(), approval = null, editingIndex = null, canvas = null, imageMode = false,
        groundingStatus = null, groundingSources = emptyList(), groundingResearch = null, privateSession = null)
    override fun newChat(project: String?) { if (canLeave()) { rememberDraft(); detach(); openDraft(null, project) } }
    /** Open the same saved voice conversation on every normal session and connected client. */
    fun openLiveVoice(): Job? {
        if (!canLeave() || state.value.incognito) return null
        return action { identity ->
            refreshSnapshot(identity)
            if (identity != epoch) return@action
            if (state.value.chats.none { it.item.id == LiveVoiceConversation.ID }) {
                val revision = state.value.items.firstOrNull { it.id == LiveVoiceConversation.ID }?.revision ?: 0
                try { repository.save(LiveVoiceConversation.empty(clock()), revision) }
                catch (e: WorkspaceException) { if (e.status != 409) throw e }
                refreshSnapshot(identity)
            }
            if (identity != epoch) return@action
            check(state.value.chats.any { it.item.id == LiveVoiceConversation.ID }) { "Live voice conversation is unavailable. Try again." }
            rememberDraft(); detach(); openDraft(LiveVoiceConversation.ID, null)
            adopt(identity)
        }
    }
    /** A private chat; [freshSlate] leaves saved memories out too. */
    fun newIncognito(freshSlate: Boolean = false) {
        if (!canLeave()) return
        rememberDraft(); detach(); beforeEdit = null
        repository.gateway.privateMode(true)
        mutable.update { cleared(it).copy(activeId = null, projectId = null, incognito = true, freshSlate = freshSlate, draft = "") }
    }
    fun removeAttachment(id: String) { mutable.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) } }
    /** An attachment built from a saved file's reference: no bytes are downloaded or uploaded. */
    private fun referenceAttachment(info: JsonObject): Attachment {
        val id = info.getValue("id").jsonPrimitive.content
        val name = info["name"]?.jsonPrimitive?.contentOrNull ?: "file"
        val mime = info["mime"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
        val image = mime.startsWith("image/")
        return Attachment(id, if (image) AttachmentKind.IMAGE else AttachmentKind.DOCUMENT, name, mime,
            path = if (image) "remote:$id" else null,
            text = info["text"]?.jsonPrimitive?.contentOrNull,
            remoteFileId = id,
            pageImages = (info["pageImageIds"] as? JsonArray)?.map { "remote:" + it.jsonPrimitive.content }.orEmpty(),
            note = info["note"]?.jsonPrimitive?.contentOrNull
                ?: if (!image && info["text"] == null) "Use the file tool to inspect this document." else null)
    }
    /** Attaches a saved Library file to the draft by reference; the server reads its own stored bytes. */
    fun reuseFile(fileId: String) = action { identity ->
        require(Regex("[a-f0-9]{32}").matches(fileId)) { "Unknown file." }
        require(state.value.attachments.size < 8) { "Attach at most eight files." }
        val info = repository.gateway.request("/workspace/files/$fileId/reference").jsonObject
        if (identity == epoch) mutable.update { it.copy(attachments = it.attachments + referenceAttachment(info)) }
    }
    fun upload(name: String, mime: String, bytes: ByteArray, attachToDraft: Boolean = true) = action { identity ->
        require(bytes.size <= 25 * 1024 * 1024) { "File exceeds 25 MB." }
        require(state.value.attachments.size < 8) { "Attach at most eight files." }
        val file = repository.gateway.upload(name, mime, bytes)
        val id = file.getValue("id").jsonPrimitive.content
        val actualMime = file["mime"]?.jsonPrimitive?.contentOrNull ?: mime
        val image = actualMime.startsWith("image/")
        val text = if (!image && (mime.startsWith("text/") || name.substringAfterLast('.').lowercase() in setOf("md", "txt", "csv", "json", "kt", "py", "js", "ts", "html", "xml", "yaml"))) bytes.decodeToString().take(60000) else null
        val attachment = Attachment(id, if (image) AttachmentKind.IMAGE else AttachmentKind.DOCUMENT, name, actualMime,
            path = if (image) file["vision"]?.jsonPrimitive?.contentOrNull ?: "remote:$id" else null, text = file["text"]?.jsonPrimitive?.contentOrNull ?: text, remoteFileId = id,
            pageImages = (file["pageImages"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
            note = file["note"]?.jsonPrimitive?.contentOrNull ?: if (!image && text == null) "Use the file tool to inspect this document." else null)
        if (identity == epoch && attachToDraft) mutable.update { it.copy(attachments = it.attachments + attachment) }
    }
    fun useModelConnection(settings: ModelConnection?) = action { identity ->
        settings?.validate()?.let { error(it) }
        val connection = settings?.let { value -> repository.gateway.request("/workspace/model-connections", "POST", buildJsonObject {
            put("base_url", value.address.trim()); put("model", value.model.trim()); put("api_key", value.apiKey.trim()); put("timeout_seconds", value.timeout.toInt())
        }).jsonObject.text("id") }
        val previous = state.value.connection
        if (identity == epoch) mutable.update { it.copy(connection = connection, notice = if (connection == null) "Using the computer's default connection." else "Using your model connection through the computer. Credentials stay in memory for this session.") }
        if (previous != null) runCatching { repository.gateway.request("/workspace/model-connections/$previous", "DELETE") }
    }
    fun reportError(message: String) { mutable.update { it.copy(error = message) } }
    override fun select(id: String) {
        if (!canLeave() || (!state.value.incognito && state.value.activeId == id)) return
        val chat = state.value.chats.firstOrNull { it.item.id == id } ?: return
        rememberDraft()
        detach()
        openDraft(id, chat.conversation.summary.projectId)
        adopt(epoch)
    }
    fun lock() {
        drafts.clear(); beforeEdit = null
        state.value.connection?.let(repository.gateway::closeModelConnection)
        epoch++; memoryQuery = ""; memoryStale = false; operation?.cancel(); generation?.cancel(); watching?.cancel(); repository.gateway.lock(); repository.forget(); mutable.value = WorkspaceState()
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
        // The computer's models, for choosing one; optional.
        val models = try { (repository.gateway.request("/v1/models") as? JsonObject)?.get("data")?.rows()?.map { it.text("id") }?.filter { it.isNotBlank() }.orEmpty() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        if (identity == epoch) mutable.update { it.copy(connected = true, models = models, model = it.model?.takeIf { m -> m in models }) }
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
            val working = old.activeJob != null && (old.incognito || job?.text("status") in WORKING)
            old.copy(chats = snapshot.chats, tasks = jobs, items = snapshot.items, knowledge = snapshot.knowledge, knowledgeRevision = snapshot.knowledgeRevision,
                preferences = snapshot.preferences, activeId = if (old.incognito) old.activeId else current?.item?.id,
                projectId = if (old.activeId != null && current == null) old.projectId?.takeIf { id -> snapshot.knowledge.projects.any { it.id == id } } else old.projectId,
                activeJob = if (working) old.activeJob else null, streamingText = if (!old.incognito && !working) "" else old.streamingText,
                liveEntries = if (working) old.liveEntries else emptyList(),
                approval = if (!working) null else if (job != null) job["pending"] as? JsonObject else old.approval,
                editingIndex = old.editingIndex?.takeIf { current != null || old.incognito },
                detachedReply = if (old.activeJob != null && !working && job?.text("error")?.isNotBlank() == true) job.text("result").takeIf { it.isNotBlank() } else old.detachedReply,
                error = job?.text("error")?.takeIf { it.isNotBlank() } ?: old.error,
                status = if (working) "FRIDAY is working on the computer…" else "History and preferences synced")
        }
        adopt(identity)
        // Stays set if the memory fetch is interrupted or fails, so the next refresh tries again.
        if (changes.memory) memoryStale = true
        if (memoryStale) refreshMemory(identity)
    }
    /** Follows the open chat's job when one is running on the computer, for example after a reload or from another client. */
    private fun adopt(identity: Int) {
        val s = state.value
        if (identity != epoch || s.incognito || s.activeJob != null || s.activeId == null) return
        val job = s.tasks.firstOrNull { it.text("item_id") == s.activeId && it.text("status") in WORKING } ?: return
        mutable.update { it.copy(activeJob = job.text("id"), approval = job["pending"] as? JsonObject, status = "FRIDAY is working on the computer…") }
        follow(job.text("id"), identity)
    }
    private fun follow(jobId: String, identity: Int) {
        generation?.cancel()
        generation = scope.launch {
            try {
                repository.gateway.stream("/workspace/jobs/$jobId/events", null) { event ->
                    if (identity == epoch && state.value.activeJob == jobId) {
                        val entries = (event["entries"] as? JsonArray)?.let { decodeMessages(it.toString()) }
                        val done = (event["done"] as? JsonPrimitive)?.booleanOrNull == true
                        mutable.update { it.copy(streamingText = (event["text"] as? JsonPrimitive)?.contentOrNull ?: it.streamingText,
                            liveEntries = entries ?: it.liveEntries, approval = if (done) null else event["approval"] as? JsonObject) }
                    }
                }
                if (identity == epoch && state.value.activeJob == jobId) { refreshSnapshot(identity); mutable.update { it.copy(streamingText = "", liveEntries = emptyList()) }; finishGrounding() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { /* The change feed still retrieves the durable result after a stream disconnect. */ }
        }
    }
    private suspend fun refreshMemory(identity: Int, limit: Int = maxOf(100, state.value.memories.size)) {
        try {
            val summary = repository.gateway.request("/workspace/memory").jsonObject
            val facts = mutableListOf<JsonObject>()
            var hasMore = true
            do {
                val pageSize = minOf(200, maxOf(1, limit - facts.size))
                val page = repository.gateway.request("/workspace/memory/memories?offset=${facts.size}&limit=$pageSize&q=${PlatformNet.encodeUrl(memoryQuery)}").jsonArray.map { it.jsonObject }
                facts.addAll(page)
                if (page.size < pageSize) { hasMore = false; break }
            } while (facts.size < limit)
            val sources = mutableListOf<JsonObject>()
            val sourceLimit = maxOf(50, state.value.archiveSources.size)
            do {
                val page = repository.gateway.request(sourcesPath(sources.size)).jsonArray.map { it.jsonObject }
                sources.addAll(page)
                if (page.size < 50) break
            } while (sources.size < sourceLimit)
            // Review lists are optional on older computers; facts and sources stay usable without them.
            val suggestions = optionalRows("/workspace/memory/suggestions")
            val situations = optionalRows("/workspace/memory/situations")
            if (identity == epoch) mutable.update { it.copy(memories = facts, archiveSources = sources, archiveTotal = summary["sources"]?.jsonPrimitive?.intOrNull ?: sources.size, memoryTotal = summary["memories"]?.jsonPrimitive?.intOrNull ?: facts.size, memoryHasMore = hasMore,
                memorySettings = summary["settings"] as? JsonObject ?: JsonObject(emptyMap()), memorySummary = summary,
                suggestions = suggestions ?: it.suggestions, situations = situations ?: it.situations, memoryError = null) }
            if (identity == epoch) memoryStale = false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (identity == epoch) mutable.update { it.copy(memoryError = "Shared memory is unavailable: ${e.message}") } }
    }
    private suspend fun optionalRows(path: String): List<JsonObject>? = try { repository.gateway.request(path).rows() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    private fun sourcesPath(offset: Int) = "/workspace/memory/sources?offset=$offset" + state.value.archiveQuery.takeIf { it.isNotBlank() }?.let { "&q=" + PlatformNet.encodeUrl(it) }.orEmpty()

    // ---- conversation ------------------------------------------------------------------------------------------

    fun send(): Job? {
        val old = state.value
        if (!old.canSend) return null
        val base = old.active?.conversation?.messages.orEmpty()
        val editing = old.editingIndex
        val text = if (old.imageMode && editing == null) ImageSettings.request(old.draft.trim()) else old.draft.trim()
        val messages = if (editing != null) ChatEdits.edit(base, editing, old.draft) ?: return null
            else base + Message.User(text, old.attachments)
        return submit(messages, fromComposer = true)
    }
    /** Asks again from the latest user message, dropping the previous answer and its tool activity. */
    fun regenerate(): Job? = state.value.takeIf { it.idle }?.active?.conversation?.messages?.let(ChatEdits::regenerate)?.let { submit(it, false) }
    /** Retries after an error: continues an unanswered message, or asks again. */
    fun retry(): Job? {
        val messages = state.value.takeIf { it.idle }?.active?.conversation?.messages ?: return null
        return if (messages.lastOrNull() is Message.User) submit(messages, false) else regenerate()
    }
    fun startEditing(index: Int) {
        val message = state.value.active?.conversation?.messages?.getOrNull(index) as? Message.User ?: return
        if (!state.value.idle) return
        // Preserve unsent work while editing; the edited message keeps its own attachments.
        if (state.value.editingIndex == null) beforeEdit = composer(state.value)
        mutable.update { it.copy(editingIndex = index, draft = message.content, attachments = emptyList()) }
    }
    fun cancelEditing() {
        val previous = beforeEdit ?: ComposerDraft()
        beforeEdit = null
        mutable.update { it.copy(editingIndex = null, draft = previous.text, attachments = previous.attachments, imageMode = previous.imageMode) }
    }
    private fun submit(messages: List<Message>, fromComposer: Boolean) = action { identity ->
        val old = state.value; val now = clock(); val active = old.active
        val first = (messages.firstOrNull() as? Message.User)?.content.orEmpty()
        val conversation = active?.conversation ?: StoredConversation(ConversationSummary(newId(), first.trim().take(70), now, now, old.projectId), emptyList())
        val next = conversation.copy(summary = conversation.summary.copy(updatedAt = now, projectId = old.projectId), messages = messages)
        val previous = if (old.editingIndex != null) beforeEdit else null
        fun sent(s: WorkspaceState) = if (fromComposer) s.copy(draft = previous?.text.orEmpty(), attachments = previous?.attachments.orEmpty(), editingIndex = null, imageMode = previous?.imageMode ?: false) else s
        if (old.incognito) {
            mutable.update { sent(it).copy(privateChat = next, activeId = next.summary.id, activeJob = "private", streamingText = "", liveEntries = emptyList()) }
            if (fromComposer) sentDraft(old)
        }
        val turn = prepareTurn(messages, old)
        if (old.incognito) {
            try {
                repository.gateway.stream("/workspace/chat/ephemeral", JsonObject(buildJsonObject {
                    put("chat", encodeConversation(next)); put("interface_prompt", if (old.preferences.interactiveAnswers) clientInterfacePrompt() else "")
                    put("use_memory", !old.freshSlate); repository.gateway.privateFileSession()?.let { put("file_session", it) }
                } + turn.fields())) { event ->
                    if (identity == epoch) {
                        event["error"]?.jsonPrimitive?.contentOrNull?.let { error(it) }
                        val entries = (event["entries"] as? JsonArray)?.let { decodeMessages(it.toString()) }
                        mutable.update { s -> s.copy(
                            streamingText = (event["text"] as? JsonPrimitive)?.contentOrNull ?: s.streamingText,
                            liveEntries = entries ?: s.liveEntries,
                            privateSession = (event["session"] as? JsonPrimitive)?.contentOrNull ?: s.privateSession,
                            approval = if ("approval" in event) event["approval"] as? JsonObject else s.approval) }
                    }
                }
                if (identity == epoch) mutable.update { it.copy(privateChat = next.copy(messages = next.messages + it.liveEntries.ifEmpty { listOf(Message.Assistant(it.streamingText)) }),
                    streamingText = "", liveEntries = emptyList()) }
                if (identity == epoch) finishGrounding()
            } finally { if (identity == epoch) mutable.update { it.copy(activeJob = null, approval = null, privateSession = null) } }
            return@action
        }
        val interfacePrompt = (if (old.preferences.interactiveAnswers) clientInterfacePrompt() else "") +
            LiveVoiceConversation.instructions(next.summary.id, old.incognito)
        val job = repository.continueChat(next, active?.item?.revision ?: 0, interfacePrompt, old.imageSettings.overrides(), turn)
        if (identity != epoch) return@action
        val item = SyncItem(next.summary.id, job.getValue("revision").jsonPrimitive.int, buildJsonObject { put("chat", encodeConversation(next)) })
        val recall = (job["recall"] as? JsonArray)?.rows()
        mutable.update { sent(it).copy(activeId = item.id, chats = listOf(SharedChat(item, next)) + it.chats.filterNot { c -> c.item.id == item.id },
            streamingText = "", liveEntries = emptyList(), approval = null, activeJob = job.text("id"), status = "FRIDAY is working on the computer…",
            recall = recall ?: it.recall, recallStatus = when {
                recall == null -> "Shared memory was not available for this reply; it uses the current conversation."
                recall.isEmpty() -> "No relevant memories needed."
                else -> "${recall.size} relevant memory sources supplied."
            }) }
        if (fromComposer) sentDraft(old)
        follow(job.text("id"), identity)
    }
    /**
     * Before a turn, as on the phone: questions about current facts get public evidence first (the note tells the model how to
     * use it), a no-search request turns web tools off, and private questions say lookups ask first.
     */
    private suspend fun prepareTurn(messages: List<Message>, old: WorkspaceState): TurnOptions {
        val query = (messages.lastOrNull { it is Message.User } as? Message.User)?.content.orEmpty()
        val g = GroundingPrecheck
        groundingAttempted = false
        mutable.update { it.copy(groundingSources = emptyList(), groundingResearch = query.takeIf { g.largerResearch(it) && !old.incognito },
            groundingStatus = if (g.privateRequest(query)) g.PRIVATE_STATUS else null) }
        var note = ""
        if (g.needsSearch(query)) {
            groundingAttempted = true
            mutable.update { it.copy(groundingStatus = g.CHECKING_STATUS) }
            val result = try { GroundedEvidence.search(repository.gateway.request("/grounding", "POST", GroundedEvidence.searchRequest(query)).toString()) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { ToolExecutionResult(false, "") }
            note = g.note(result)
            mutable.update { it.copy(groundingStatus = g.retrievedStatus(result.success), groundingSources = result.sources) }
        }
        val block = g.blocksWeb(query)
        if (block) note += g.OPT_OUT_NOTE
        return TurnOptions(note, block, old.model, old.connection)
    }
    /** After a reply that used public sources, checks which of its links match pages actually read. */
    private fun finishGrounding() {
        val s = state.value
        val messages = s.active?.conversation?.messages ?: return
        val turn = messages.drop(messages.indexOfLast { it is Message.User } + 1)
        val web = turn.filterIsInstance<Message.ToolResult>().filter { it.success && it.name in WEB_TOOLS }
        if (!groundingAttempted && web.isEmpty()) return
        val sources = GroundingPrecheck.mergeSources(s.groundingSources + web.flatMap { it.sources })
        val answer = turn.filterIsInstance<Message.Assistant>().lastOrNull()?.content.orEmpty()
        mutable.update { it.copy(groundingStatus = GroundingPrecheck.audit(answer, sources).status, groundingSources = sources) }
    }
    /** Proposes larger public research on the last question for approval in FRIDAY, as on the phone. */
    fun proposeGroundedResearch(features: FeatureController): Boolean {
        val query = state.value.groundingResearch?.takeIf { !state.value.incognito } ?: return false
        if (query.length > 7900) { reportError("This research prompt exceeds the task limit. Use a focused public question."); return false }
        features.change("/workspace/agent/tasks", "POST", buildJsonObject {
            put("prompt", "Research this public question: $query")
            putJsonArray("plan") { listOf("Find primary authoritative sources", "Read relevant source passages and dates", "Check material claims, conflicting evidence and limits", "Cite supporting URLs and state uncertainty").forEach { add(it) } }
        })
        return true
    }
    /** Answers the approval card the running job waits on. Nothing it asked about has run before this. */
    fun answerApproval(approve: Boolean): Job? {
        val pending = state.value.approval ?: return null
        if (state.value.incognito) {
            // The private stream holds the action open, so its answer goes straight to that stream.
            val session = state.value.privateSession ?: return null
            val identity = epoch
            mutable.update { it.copy(approval = null) }
            return scope.launch {
                try { repository.gateway.request("/workspace/chat/ephemeral/$session/approval", "POST", buildJsonObject { put("call_id", pending.text("call_id")); put("approve", approve) }) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (identity == epoch) mutable.update { it.copy(approval = pending, error = e.message ?: "Computer unavailable") } }
            }
        }
        val job = state.value.activeJob ?: return null
        return action { identity ->
            repository.gateway.request("/workspace/jobs/$job/approval", "POST", buildJsonObject { put("call_id", pending.text("call_id")); put("approve", approve) })
            if (identity == epoch) mutable.update { if (it.approval == pending) it.copy(approval = null) else it }
        }
    }
    /** Starts a new chat with the messages through user message [index]. */
    fun branch(index: Int): Job? {
        val chat = state.value.takeIf { it.idle && !it.incognito }?.active?.conversation ?: return null
        val prefix = ChatEdits.branch(chat.messages, index) ?: return null
        return action { identity ->
            val now = clock(); val id = newId()
            repository.save(StoredConversation(ConversationSummary(id, ChatEdits.branchTitle(chat.summary.title), now, now, chat.summary.projectId), prefix), 0)
            refreshSnapshot(identity)
            if (identity == epoch) { rememberDraft(); detach(); openDraft(id, chat.summary.projectId) }
        }
    }
    fun openCanvas(text: String) {
        val doc = CanvasDoc.from(state.value.active?.conversation?.summary?.title.orEmpty(), text) ?: return
        mutable.update { it.copy(canvas = doc) }
    }
    fun updateCanvas(title: String, body: String) { mutable.update { it.copy(canvas = it.canvas?.named(title)?.edited(body)) } }
    fun closeCanvas() { mutable.update { it.copy(canvas = null) } }
    /** Saves the canvas as a Markdown file in the computer's Library. Private chats download it instead. */
    fun saveCanvas(): Job? {
        val doc = state.value.canvas?.takeIf { it.body.isNotBlank() } ?: return null
        return action { identity ->
            if (state.value.incognito) save(doc.fileName, "text/markdown", doc.body.encodeToByteArray())
            else repository.gateway.request("/workspace/create-file", "POST", buildJsonObject { put("name", doc.fileName); put("content", doc.body) })
            if (identity == epoch) mutable.update { it.copy(canvas = null, notice = if (it.incognito) "Draft downloaded as ${doc.fileName}." else "Draft saved to ${doc.fileName} in Library.") }
        }
    }
    /** Downloads the canvas as a Markdown file on this device. */
    fun downloadCanvas(): Job? {
        val doc = state.value.canvas?.takeIf { it.body.isNotBlank() } ?: return null
        return action { identity ->
            save(doc.fileName, "text/markdown", doc.body.encodeToByteArray())
            if (identity == epoch) mutable.update { it.copy(notice = "Draft downloaded as ${doc.fileName}.") }
        }
    }
    /** Remembers thumbs up or down on an answer as a preference for future chats. */
    fun feedback(answerIndex: Int, answer: String, liked: Boolean): Job? {
        val old = state.value
        if (old.incognito) return null
        val question = ChatEdits.questionBefore(old.transcript, answerIndex) ?: return null
        return action { identity ->
            val text = AnswerFeedback.memory(question, answer, liked)
            val existing = repository.gateway.request("/workspace/memory/memories?q=${PlatformNet.encodeUrl(question.trim().take(100))}&offset=0&limit=200").rows()
            val notice = if (existing.any { it.text("text") == text }) "Already noted — no duplicate saved."
            else { repository.saveMemory(text, null, "preference", old.projectId.orEmpty(), true); refreshMemory(identity); AnswerFeedback.notice(liked) }
            if (identity == epoch) mutable.update { it.copy(notice = notice) }
        }
    }

    // ---- exports and backups -----------------------------------------------------------------------------------

    /** Downloads the open chat as Markdown or the shared JSON format. */
    fun exportChat(format: String = "md"): Job? {
        val chat = state.value.active?.conversation?.takeIf { it.messages.isNotEmpty() } ?: return null
        return action { identity ->
            val title = chat.summary.title
            val (name, mime, bytes) = if (format == "json") Triple(safeFileName(title, "Chat", "json"), "application/json", encodeConversation(chat).encodeToByteArray())
                else Triple(safeFileName(title, "Chat", "md"), "text/markdown", ChatExport.markdown(title, chat.messages).encodeToByteArray())
            save(name, mime, bytes)
            if (identity == epoch) mutable.update { it.copy(notice = "Chat exported as $name.") }
        }
    }
    /** Saves the open chat as a Markdown file in the computer's Library and downloads it. */
    fun exportChatToLibrary(): Job? {
        val chat = state.value.takeIf { !it.incognito }?.active?.conversation?.takeIf { it.messages.isNotEmpty() } ?: return null
        return action { identity ->
            val name = safeFileName(chat.summary.title, "Chat", "md")
            val file = repository.gateway.request("/workspace/create-file", "POST", buildJsonObject { put("name", name); put("content", ChatExport.markdown(chat.summary.title, chat.messages)) }).jsonObject
            file.text("id").takeIf { Regex("[a-f0-9]{32}").matches(it) }?.let { repository.gateway.artifact(it) }
            if (identity == epoch) mutable.update { it.copy(notice = "Chat exported to $name in Library.") }
        }
    }
    /** A portable backup of every shared chat, projects and attachment images, in the format Android restores. */
    fun exportBackup() = action { identity ->
        val old = state.value
        val assets = linkedMapOf<String, ByteArray>()
        var total = 0L
        suspend fun asset(path: String?): String? {
            val id = path?.takeIf { it.startsWith("remote:") }?.removePrefix("remote:")?.takeIf { Regex("[a-f0-9]{32}").matches(it) } ?: return null
            val key = "assets/$id"
            if (key !in assets) {
                val bytes = try { repository.gateway.fileBytes(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
                if (total + bytes.size >= ChatBackup.MAX_TOTAL || bytes.size > ChatBackup.MAX_ENTRY) return null
                total += bytes.size; assets[key] = bytes
            }
            return key
        }
        val chats = old.chats.map { shared ->
            val chat = shared.conversation
            chat.copy(messages = chat.messages.map { m ->
                if (m !is Message.User) m else m.copy(attachments = m.attachments.map { a -> a.copy(path = asset(a.path), pageImages = a.pageImages.mapNotNull { asset(it) }) })
            })
        }
        val bytes = ChatBackup.write(chats, old.knowledge, assets)
        save("friday-backup.zip", "application/zip", bytes)
        if (identity == epoch) mutable.update { it.copy(notice = "Backup exported: ${chats.size} chats and ${assets.size} attachment images, without settings or credentials.") }
    }
    /** Restores a backup from either client as new chat copies; projects merge without overwriting. */
    fun restoreBackup(bytes: ByteArray) = action { identity ->
        val contents = ChatBackup.read(bytes, inflate)
        val mimes = contents.chats.flatMap { it.messages }.filterIsInstance<Message.User>().flatMap { it.attachments }
            .flatMap { a -> (listOfNotNull(a.path) + a.pageImages).map { it to if (a.kind == AttachmentKind.IMAGE) a.mimeType else "image/jpeg" } }.toMap()
        val uploaded = mutableMapOf<String, String>()
        for ((key, data) in contents.assets) {
            if (identity != epoch) return@action
            val file = repository.gateway.upload(key.removePrefix("assets/"), mimes[key] ?: "image/jpeg", data)
            uploaded[key] = "remote:" + file.getValue("id").jsonPrimitive.content
        }
        val old = state.value
        val (merged, renamed) = ChatBackup.merge(old.knowledge, contents.knowledge, newId)
        if (merged != old.knowledge) repository.knowledge(merged, old.knowledgeRevision)
        val chats = ChatBackup.restored(contents.chats, renamed, newId).map { chat ->
            chat.copy(messages = chat.messages.map { m ->
                if (m !is Message.User) m else m.copy(attachments = m.attachments.map { a ->
                    val path = a.path?.let(uploaded::get)
                    a.copy(path = path, pageImages = a.pageImages.mapNotNull(uploaded::get), remoteFileId = path?.removePrefix("remote:") ?: a.remoteFileId)
                })
            })
        }
        for (chat in chats) { if (identity != epoch) return@action; repository.save(chat, 0) }
        refreshSnapshot(identity)
        if (identity == epoch) mutable.update { it.copy(notice = "Restored ${chats.size} chats as new copies.") }
    }

    // ---- tasks, history and memory -----------------------------------------------------------------------------

    override fun scheduleTask(prompt: String, runAt: Long, intervalSeconds: Int) = action { identity -> repository.scheduleTask(prompt, runAt, intervalSeconds); refreshSnapshot(identity) }
    override fun taskAction(id: String, taskAction: String) = action { identity ->
        repository.taskAction(id, taskAction)
        refreshSnapshot(identity)
    }
    fun stop(): Job? {
        if (state.value.incognito) { epoch++; operation?.cancel(); mutable.update { it.copy(activeJob = null, streamingText = "", busy = false) }; return null }
        return action { identity ->
        val job = state.value.activeJob ?: return@action
        // The computer keeps the steps finished so far and the text received, as Stop does on the phone.
        repository.gateway.request("/workspace/jobs/$job/cancel", "POST")
        refreshSnapshot(identity)
        generation?.cancel(); mutable.update { it.copy(streamingText = "", liveEntries = emptyList(), approval = null) }
        }
    }
    fun rename(title: String, baseline: SharedChat? = state.value.active) = action { identity ->
        val chat = baseline ?: return@action
        require(title.trim().isNotBlank()) { "Enter a chat title." }
        repository.save(chat.conversation.copy(summary = chat.conversation.summary.copy(title = title.trim().take(200), updatedAt = clock())), chat.item.revision)
        refreshSnapshot(identity)
    }
    fun delete(id: String? = state.value.activeId) = action { identity ->
        val chat = state.value.chats.firstOrNull { it.item.id == id } ?: return@action
        repository.delete(chat); drafts.remove(id, chat.conversation.summary.projectId); refreshSnapshot(identity)
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
        val page = repository.gateway.request(sourcesPath(old.size)).jsonArray.map { it.jsonObject }
        if (identity == epoch) mutable.update { it.copy(archiveSources = (old + page).distinctBy { row -> row.text("id") }) }
    }
    override fun source(id: String) = action { identity ->
        val preview = repository.source(id)
        if (identity == epoch) mutable.update { it.copy(sourcePreview = preview) }
    }
    override fun closeSource() { mutable.update { it.copy(sourcePreview = null) } }
    override fun excludeSource(id: String, excluded: Boolean) = action { identity ->
        repository.excludeSource(id, excluded)
        if (identity == epoch) mutable.update { s -> s.copy(sourcePreview = s.sourcePreview?.takeIf { it.text("id") == id }?.let { JsonObject(it + ("excluded" to JsonPrimitive(excluded))) } ?: s.sourcePreview) }
        refreshMemory(identity)
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
        val page = repository.gateway.request("/workspace/memory/memories?offset=${old.size}&limit=100&q=${PlatformNet.encodeUrl(memoryQuery)}").jsonArray.map { it.jsonObject }
        if (identity == epoch) mutable.update { it.copy(memories = (old + page).distinctBy { row -> row.text("id") }, memoryHasMore = page.size == 100) }
    }
    override fun refreshMemoryReview() = action(quiet = true) { identity -> refreshMemory(identity) }
    override fun reviewSuggestion(id: String, accept: Boolean, text: String?) = action { identity ->
        repository.gateway.request("/workspace/memory/suggestions/$id", "POST", buildJsonObject { put("accept", accept); text?.let { put("text", it.take(2000)) } })
        refreshMemory(identity)
        if (identity == epoch) mutable.update { it.copy(notice = if (accept) "Memory approved." else "Suggestion dismissed.") }
    }
    override fun resolveSituation(id: String) = action { identity ->
        repository.gateway.request("/workspace/memory/situations/$id", "PATCH", buildJsonObject { put("status", "resolved") }); refreshMemory(identity)
        if (identity == epoch) mutable.update { it.copy(notice = "Situation marked resolved.") }
    }
    override fun forgetSituation(id: String) = action { identity ->
        repository.gateway.request("/workspace/memory/situations/$id", "DELETE"); refreshMemory(identity)
        if (identity == epoch) mutable.update { it.copy(notice = "Situation forgotten and its source excluded from recall.") }
    }
    override fun searchArchive(query: String) = action { identity ->
        mutable.update { it.copy(archiveQuery = query.take(2000), archiveSources = emptyList()) }
        val page = repository.gateway.request(sourcesPath(0)).jsonArray.map { it.jsonObject }
        if (identity == epoch) mutable.update { it.copy(archiveSources = page) }
    }
    override fun deleteSource(id: String) = action { identity ->
        repository.gateway.request("/workspace/memory/sources/$id", "DELETE")
        if (identity == epoch) mutable.update { it.copy(sourcePreview = null, notice = "Archive source deleted. Approved memories remain separately managed.") }
        refreshMemory(identity)
    }
    override fun extractSource(id: String) = action { identity ->
        repository.gateway.request("/workspace/memory/sources/$id/extract", "POST", JsonObject(emptyMap())); refreshMemory(identity)
        if (identity == epoch) mutable.update { it.copy(notice = "Source queued for memory suggestions.") }
    }
    override fun continueSource(): Job? {
        val source = state.value.takeIf { it.idle && !it.incognito }?.sourcePreview ?: return null
        return action { identity ->
            val messages = source["messages"]?.rows().orEmpty().mapNotNull { m ->
                when (m.text("role")) { "user" -> Message.User(m.text("content")); "assistant" -> Message.Assistant(m.text("content")); else -> null }
            }
            require(messages.isNotEmpty()) { "This source has no messages to continue." }
            val now = clock(); val id = newId()
            repository.save(StoredConversation(ConversationSummary(id, source.text("title").ifBlank { "Archived chat" }.take(200), now, now), messages), 0)
            refreshSnapshot(identity)
            if (identity == epoch) { detach(); mutable.update { cleared(it).copy(activeId = id, projectId = null, sourcePreview = null) } }
        }
    }
    /** Stages a ChatGPT export; [upload] streams the chosen file to `/workspace/memory/imports` and returns its preview. */
    fun stageImport(upload: suspend () -> JsonElement) = action { identity ->
        val preview = upload().jsonObject
        if (identity == epoch) mutable.update { it.copy(importPreview = preview, notice = "Export preview ready. Nothing imported yet.") }
    }
    override fun commitImport(extract: Boolean): Job? {
        val id = state.value.importPreview?.text("id")?.takeIf { it.isNotBlank() } ?: return null
        return action { identity ->
            val result = repository.gateway.request("/workspace/memory/imports/$id", "POST", buildJsonObject { put("extract", extract) }).jsonObject
            if (identity == epoch) mutable.update { it.copy(importPreview = null, notice = "Imported ${result["imported"]?.jsonPrimitive?.intOrNull ?: 0} chats on the computer; ${result["duplicates"]?.jsonPrimitive?.intOrNull ?: 0} duplicates skipped." +
                if (extract) " Memory suggestions appear for review as they are processed." else "") }
            refreshMemory(identity)
        }
    }
    override fun discardImport(): Job? {
        val id = state.value.importPreview?.text("id") ?: return null
        return action { identity ->
            if (id.isNotBlank()) repository.gateway.request("/workspace/memory/imports/$id", "DELETE")
            if (identity == epoch) mutable.update { it.copy(importPreview = null, notice = "Import preview discarded.") }
        }
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
