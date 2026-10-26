package com.localfirst.assistant.client

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import com.localfirst.assistant.platform.PlatformNet

fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
fun JsonElement.rows(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

data class FeatureState(
    val page: String = "files", val busy: Boolean = false, val error: String? = null,
    val files: List<JsonObject> = emptyList(), val images: List<JsonObject> = emptyList(),
    /** Video jobs, and the computer's video options (sizes and times per duration); null until first loaded. */
    val videos: List<JsonObject> = emptyList(), val videoOptions: JsonObject? = null,
    val fileQuery: String = "", val fileKind: String = "all", val fileSort: String = "recent",
    val fileLoading: Boolean = false, val fileTotal: Int = 0, val fileNextOffset: Int? = null,
    val tasks: List<JsonObject> = emptyList(), val task: JsonObject? = null, val events: List<JsonObject> = emptyList(),
    val health: JsonObject = JsonObject(emptyMap()), val pc: JsonObject = JsonObject(emptyMap()),
    val sessions: List<JsonObject> = emptyList(), val session: JsonObject? = null,
    val accountContent: JsonObject? = null, val schedules: List<JsonObject> = emptyList(),
    val accounts: List<JsonObject> = emptyList(), val providers: List<JsonObject> = emptyList(), val oauth: JsonObject? = null,
    /** The PC's tool-server connections and whether each is confirmed. */
    val mcp: JsonObject? = null,
    /** Live views of PC sessions and FRIDAY tasks that chats started, by id, for their in-chat cards. */
    val chatPc: Map<String, JsonObject> = emptyMap(), val chatTasks: Map<String, JsonObject> = emptyMap(),
    val chatWorkBusy: Boolean = false,
)

private val TERMINAL = setOf("completed", "failed", "cancelled")

/** Portable service orchestration; optional PC services report their own availability per screen. */
class FeatureController(val gateway: WorkspaceGateway, private val scope: CoroutineScope, private val browserOAuth: Boolean = false) {
    private val mutable = MutableStateFlow(FeatureState())
    val state = mutable.asStateFlow()
    private var epoch = 0
    private var eventCursor = 0L
    private var operation: Job? = null
    private var fileOperation: Job? = null
    private var fileGeneration = 0
    /** How long an open screen waits before refreshing: quickly while something there is in progress, slowly otherwise.
     *  Each refresh of Activity is several requests, so an idle screen polling every few seconds costs battery. */
    fun refreshDelayMillis(): Long {
        val s = state.value
        val working = when (s.page) {
            "images" -> s.images.any { it.string("status") !in TERMINAL }
            "videos" -> s.videos.any { it.string("status") !in TERMINAL }
            "activity" -> s.tasks.any { it.string("status") in setOf("approved", "running") } || s.sessions.any { it.string("state") == "busy" }
            else -> false
        }
        return if (working) 3_000 else 30_000
    }
    private var filesLoadedAt = 0L
    fun reset() { epoch++; operation?.cancel(); fileGeneration++; fileOperation?.cancel(); mutable.value = FeatureState() }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun open(page: String, taskId: String? = null, sessionId: String? = null) {
        fileGeneration++; fileOperation?.cancel(); eventCursor = 0
        mutable.update { it.copy(page = page, fileLoading = false, task = taskId?.let { buildJsonObject { put("id", it) } }, session = sessionId?.let { buildJsonObject { put("id", it) } }, events = emptyList(), error = null) }
        // The image model may have been unloaded (a video borrowed its card); start loading it while the page is read.
        if (page == "images") scope.launch { runCatching { gateway.request("/workspace/images/warm", "POST", buildJsonObject {}) } }
        refresh()
    }
    fun task(id: String?) { eventCursor = 0; mutable.update { it.copy(task = id?.let { buildJsonObject { put("id", it) } }, events = emptyList()) }; refresh() }
    fun session(id: String?) { mutable.update { it.copy(session = id?.let { buildJsonObject { put("id", it) } }) }; refresh() }
    fun refresh(after: Long = eventCursor): Job? = if (state.value.page == "files") requestFiles() else run { identity -> eventCursor = after; load(identity, after) }
    fun searchFiles(query: String) {
        mutable.update { it.copy(fileQuery = query.take(500)) }
        requestFiles(debounce = true)
    }
    fun filterFiles(kind: String) {
        require(kind in setOf("all", "images", "documents", "audio", "video", "other"))
        mutable.update { it.copy(fileKind = kind) }; requestFiles()
    }
    fun sortFiles(sort: String) {
        require(sort in setOf("recent", "name", "size"))
        mutable.update { it.copy(fileSort = sort) }; requestFiles()
    }
    fun moreFiles(): Job? = if (state.value.fileLoading || state.value.fileNextOffset == null) null else requestFiles(append = true)
    /** File reads have their own generation so typing cannot cancel an upload/delete or publish stale results. */
    private fun requestFiles(append: Boolean = false, debounce: Boolean = false): Job? {
        val old = state.value
        if (old.page != "files") return null
        val offset = if (append) old.fileNextOffset ?: return null else 0
        val identity = epoch
        val generation = ++fileGeneration
        fileOperation?.cancel()
        mutable.update { it.copy(fileLoading = true, error = null, files = if (append) it.files else emptyList(), fileTotal = if (append) it.fileTotal else 0, fileNextOffset = if (append) it.fileNextOffset else null) }
        return scope.launch {
            try {
                if (debounce) delay(250)
                val response = gateway.request("/workspace/files?paged=true&limit=50&offset=$offset&q=${PlatformNet.encodeUrl(old.fileQuery)}&kind=${old.fileKind}&sort=${old.fileSort}").jsonObject
                if (identity == epoch && generation == fileGeneration && state.value.page == "files") mutable.update {
                    it.copy(files = ((if (append) old.files else emptyList()) + response["files"]!!.rows()).distinctBy { file -> file.string("id") }, fileTotal = response["total"]!!.jsonPrimitive.int, fileNextOffset = response["next_offset"]?.jsonPrimitive?.intOrNull)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (identity == epoch && generation == fileGeneration) mutable.update { it.copy(error = e.message ?: "Library unavailable") } }
            finally { if (identity == epoch && generation == fileGeneration) mutable.update { it.copy(fileLoading = false) } }
        }.also { fileOperation = it }
    }
    private fun run(block: suspend (Int) -> Unit): Job? {
        if (state.value.busy) return null
        val identity = epoch
        mutable.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try { block(identity) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (identity == epoch) mutable.update { it.copy(error = e.message ?: "Computer service unavailable") } }
            finally { if (identity == epoch) mutable.update { it.copy(busy = false) } }
        }.also { operation = it }
    }
    private suspend fun load(identity: Int, after: Long = 0) {
        val old = state.value
        var next = old
        when (old.page) {
            "files" -> { if (identity == epoch) requestFiles()?.join(); return }
            "images" -> {
                // The reference picker's file list changes rarely; reload it at most once a minute.
                val now = com.localfirst.assistant.platform.PlatformSystem.currentTimeMillis()
                val files = if (old.files.isEmpty() || now - filesLoadedAt > 60_000) gateway.request("/workspace/files").rows().also { filesLoadedAt = now } else old.files
                next = old.copy(images = gateway.request("/workspace/images").rows(), files = files)
            }
            "videos" -> {
                // The options table only changes with the computer's setup: read it once per connection (until it's available).
                val options = old.videoOptions?.takeIf { it.flag("available") } ?: try { gateway.request("/workspace/videos/capabilities").jsonObject } catch (e: CancellationException) { throw e } catch (e: Exception) { buildJsonObject { put("available", false); put("detail", e.message ?: "Video service unavailable") } }
                next = old.copy(videoOptions = options, videos = if (options.flag("available")) gateway.request("/workspace/videos").rows() else emptyList(),
                    files = if ((options["inputs"] as? JsonArray).isNullOrEmpty()) old.files else gateway.request("/workspace/files").rows())
            }
            "activity" -> {
                val tasks = gateway.request("/workspace/agent/tasks").rows()
                val health = gateway.request("/workspace/agent/health").jsonObject
                val taskId = old.task?.string("id")
                val task = taskId?.let { gateway.request("/workspace/agent/tasks/$it").jsonObject }
                val events = taskId?.let { gateway.request("/workspace/agent/tasks/$it/events?after=$after&limit=200").rows() }.orEmpty()
                next = old.copy(tasks = tasks, health = health, task = task, events = events)
                try { next = next.copy(accounts = gateway.request("/workspace/agent/accounts").jsonObject["accounts"]?.rows().orEmpty()) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* Account vault is optional. */ }
                // Research remains usable when the optional PC agent is unavailable.
                try {
                    val pc = gateway.request("/workspace/pc/status").jsonObject
                    val sessions = gateway.request("/workspace/pc/sessions").rows()
                    val session = old.session?.string("id")?.let { gateway.request("/workspace/pc/sessions/$it").jsonObject }
                    next = next.copy(pc = pc, sessions = sessions, session = session, schedules = gateway.request("/workspace/pc/schedules").rows())
                    try { next = next.copy(mcp = gateway.request("/workspace/pc/mcp").jsonObject) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* Older computers have no tool-server list. */ }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { next = next.copy(pc = buildJsonObject { put("detail", e.message ?: "PC agent unavailable") }) }
            }
            "accounts" -> next = old.copy(accounts = gateway.request("/workspace/agent/accounts").jsonObject["accounts"]?.rows().orEmpty(), providers = gateway.request("/workspace/agent/accounts/providers").rows())
        }
        if (identity == epoch && old.page == state.value.page && old.task?.string("id") == state.value.task?.string("id") && old.session?.string("id") == state.value.session?.string("id")) mutable.value = next.copy(busy = true, chatPc = state.value.chatPc, chatTasks = state.value.chatTasks, chatWorkBusy = state.value.chatWorkBusy)
    }
    fun change(path: String, method: String = "POST", body: JsonObject? = null): Job? = run { identity ->
        gateway.request(path, method, body); load(identity)
    }
    private var chatWork: Job? = null
    /** Refreshes the PC sessions and FRIDAY tasks shown as cards in a chat, without blocking the rest of FRIDAY. */
    fun refreshChatWork(pcIds: List<String>, taskIds: List<String>): Job? {
        val pcs = pcIds.filter { Regex("[A-Za-z0-9_-]{1,100}").matches(it) }.distinct().takeLast(5)
        val tasks = taskIds.filter { Regex("[a-f0-9]{32}").matches(it) }.distinct().takeLast(5)
        if (pcs.isEmpty() && tasks.isEmpty() || chatWork?.isActive == true) return chatWork
        val identity = epoch
        mutable.update { it.copy(chatWorkBusy = true) }
        return scope.launch {
            try {
                val pcViews = pcs.mapNotNull { id -> try { id to gateway.request("/workspace/pc/sessions/$id").jsonObject } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
                val taskViews = tasks.mapNotNull { id -> try { id to gateway.request("/workspace/agent/tasks/$id").jsonObject } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
                if (identity == epoch) mutable.update { it.copy(chatPc = it.chatPc + pcViews, chatTasks = it.chatTasks + taskViews) }
            } finally { if (identity == epoch) mutable.update { it.copy(chatWorkBusy = false) } }
        }.also { chatWork = it }
    }
    /** An action taken from an in-chat card, then that card's fresh view. */
    fun chatAction(path: String, body: JsonObject? = null, pcIds: List<String> = emptyList(), taskIds: List<String> = emptyList()): Job? = run { identity ->
        gateway.request(path, "POST", body)
        chatWork?.cancel()
        if (identity == epoch) refreshChatWork(pcIds, taskIds)?.join()
    }
    fun startPc(prompt: String): Job? = run { identity ->
        val value = gateway.request("/workspace/pc/sessions", "POST", buildJsonObject { put("prompt", prompt) }).jsonObject
        if (identity == epoch) mutable.update { it.copy(session = value) }
        load(identity)
    }
    fun readAccount(path: String) = run { identity ->
        val content = gateway.request(path).jsonObject
        if (identity == epoch) mutable.update { it.copy(accountContent = content) }
    }
    fun beginOAuth(provider: String, features: List<String>): Job? = run { identity ->
        val flow = gateway.request("/workspace/agent/accounts/oauth/start", "POST", buildJsonObject { put("provider", provider); put("browser", browserOAuth); put("features", JsonArray(features.map(::JsonPrimitive))) }).jsonObject
        if (identity == epoch) mutable.update { it.copy(oauth = flow) }
    }
    fun finishOAuth(code: String, returnedState: String, expectedFlow: String? = null): Job? = run { identity ->
        val flow = state.value.oauth ?: error("Start sign-in first")
        require(expectedFlow == null || flow.string("flow_id") == expectedFlow) { "Sign-in was replaced; start again" }
        gateway.request("/workspace/agent/accounts/oauth/complete", "POST", buildJsonObject { put("flow_id", flow.string("flow_id")); put("state", returnedState); put("code", code) })
        if (identity == epoch) mutable.update { it.copy(oauth = null) }
        load(identity)
    }
    fun cancelOAuth(): Job? = run { identity ->
        state.value.oauth?.let { gateway.request("/workspace/agent/accounts/oauth/${it.string("flow_id")}/cancel", "POST") }
        if (identity == epoch) mutable.update { it.copy(oauth = null) }
    }
}
