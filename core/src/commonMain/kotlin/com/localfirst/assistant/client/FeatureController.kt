package com.localfirst.assistant.client

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
fun JsonElement.rows(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

data class FeatureState(
    val page: String = "files", val busy: Boolean = false, val error: String? = null,
    val files: List<JsonObject> = emptyList(), val images: List<JsonObject> = emptyList(),
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

/** Portable service orchestration; optional PC services report their own availability per screen. */
class FeatureController(val gateway: WorkspaceGateway, private val scope: CoroutineScope, private val browserOAuth: Boolean = false) {
    private val mutable = MutableStateFlow(FeatureState())
    val state = mutable.asStateFlow()
    private var epoch = 0
    private var eventCursor = 0L
    private var operation: Job? = null
    fun reset() { epoch++; operation?.cancel(); mutable.value = FeatureState() }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun open(page: String, taskId: String? = null, sessionId: String? = null) { eventCursor = 0; mutable.update { it.copy(page = page, task = taskId?.let { buildJsonObject { put("id", it) } }, session = sessionId?.let { buildJsonObject { put("id", it) } }, events = emptyList(), error = null) }; refresh() }
    fun task(id: String?) { eventCursor = 0; mutable.update { it.copy(task = id?.let { buildJsonObject { put("id", it) } }, events = emptyList()) }; refresh() }
    fun session(id: String?) { mutable.update { it.copy(session = id?.let { buildJsonObject { put("id", it) } }) }; refresh() }
    fun refresh(after: Long = eventCursor): Job? = run { identity -> eventCursor = after; load(identity, after) }
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
            "files" -> next = old.copy(files = gateway.request("/workspace/files").rows())
            "images" -> next = old.copy(images = gateway.request("/workspace/images").rows(), files = gateway.request("/workspace/files").rows())
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
