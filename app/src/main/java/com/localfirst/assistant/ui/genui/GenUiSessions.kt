package com.localfirst.assistant.ui.genui

import com.localfirst.assistant.genui.RenderResult
import com.localfirst.assistant.genui.Severity
import com.localfirst.assistant.genui.UiAction
import com.localfirst.assistant.genui.UiCallback
import com.localfirst.assistant.genui.UiCompiler
import com.localfirst.assistant.genui.UiRuntime
import com.localfirst.assistant.json.JsonCodec
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Interface replies of the open conversations, each with its own [UiRuntime]. Like ChatGPT's worker,
 * all model code runs off the main thread, here on one engine thread with a large stack; the UI only
 * receives rendered trees. Streamed updates are coalesced (the latest text wins), and each reply's
 * state is saved per conversation so it survives restarts, which ChatGPT's interfaces don't.
 */
class GenUiSessions(private val stateDir: File?) {
    private val engine = Executors.newSingleThreadExecutor { r -> Thread(null, r, "friday-genui", 16L shl 20).apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + engine)
    private val sessions = object : LinkedHashMap<String, Session>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>) = (size > MAX_SESSIONS).also { if (it) eldest.value.close() }
    }
    private val saved = HashMap<String, MutableMap<String, Any?>>()

    inner class Session internal constructor(val conversation: String, val index: Int, bindings: Map<String, Any?>) {
        private val runtime = UiRuntime(bindings = bindings, savedState = savedState(conversation, index))
        private val _result = MutableStateFlow<RenderResult?>(null)
        val result: StateFlow<RenderResult?> = _result
        private val updates = Channel<Pair<String, Boolean>>(Channel.CONFLATED)
        private var loaded: Pair<String, Boolean>? = null

        init {
            // The latest text wins: while one compile runs, newer streamed text replaces older.
            scope.launch {
                for (next in updates) {
                    if (loaded == next) continue
                    loaded = next
                    _result.value = runtime.load(UiCompiler.compile(next.first, next.second))
                    if (!next.second) delay(120)
                }
            }
        }

        /** The reply's latest text; recompiled on the engine thread, at most every 120 ms while streaming. */
        fun update(text: String, complete: Boolean) { updates.trySend(text to complete) }

        internal fun close() { updates.close() }

        fun dispatch(callback: UiCallback, args: List<Any?>, onActions: (List<UiAction>) -> Unit) {
            scope.launch {
                val result = runtime.dispatch(callback, args)
                _result.value = result
                save(this@Session, runtime.snapshotState())
                if (result.actions.isNotEmpty()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onActions(result.actions) }
            }
        }

        /** What the model should know about this reply next turn: values the user changed and repairs it should avoid. */
        internal fun note(): String? {
            val changed = runtime.changedState().entries.joinToString(", ") { (k, v) -> "$k = ${JsonCodec.json.encodeToString(JsonElement.serializer(), toJson(v))}" }
            val issues = runtime.diagnostics().filter { it.severity != Severity.INFO && it.code != "action_during_render" }.take(4).joinToString("; ") { it.message }
            if (changed.isEmpty() && issues.isEmpty()) return null
            return listOfNotNull(
                changed.takeIf { it.isNotEmpty() }?.let { "The user changed your last interface: $it." },
                issues.takeIf { it.isNotEmpty() }?.let { "Your last interface had problems, avoid them: $it." },
            ).joinToString(" ")
        }

        internal val appBlocks = object : AppBlockStore {
            override fun get(id: String) = savedState(conversation, -1)["app:$id"] as? String
            override fun set(id: String, json: String) { scope.launch { saveApp(conversation, id, json) } }
        }
    }

    /** The session for message [index] of [conversation] ("draft" before the chat is saved). */
    @Synchronized
    fun session(conversation: String, index: Int, bindings: Map<String, Any?>): Session =
        sessions.getOrPut("$conversation#$index") { Session(conversation, index, bindings) }

    /** The note for the model about the reply at [index] (the newest one), if it is an interface. */
    @Synchronized
    fun noteFor(conversation: String, index: Int): String? = sessions["$conversation#$index"]?.note()

    /** A new chat got its id: keep its replies' state under that id. */
    @Synchronized
    fun rename(from: String, to: String) {
        val moved = sessions.filterKeys { it.startsWith("$from#") }
        moved.forEach { (key, session) -> sessions.remove(key); session.close() }
        saved.remove(from)?.let { state -> saved[to] = state; scope.launch { write(to) } }
    }

    @Synchronized
    fun forget(conversation: String) {
        sessions.keys.filter { it.startsWith("$conversation#") }.forEach { sessions.remove(it)?.close() }
        saved.remove(conversation)
        file(conversation)?.delete()
    }

    // ---- persistence ------------------------------------------------------------------------------------

    private fun file(conversation: String): File? = stateDir?.takeIf { conversation != "draft" && !conversation.startsWith("incognito") }
        ?.let { File(it, conversation.filter { c -> c.isLetterOrDigit() || c == '-' } + ".json") }

    @Synchronized
    private fun loadAll(conversation: String): MutableMap<String, Any?> = saved.getOrPut(conversation) {
        val text = file(conversation)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        (text?.let { runCatching { fromJson(JsonCodec.json.parseToJsonElement(it)) }.getOrNull() } as? Map<*, *>)
            ?.entries?.associate { (k, v) -> k.toString() to v }?.toMutableMap() ?: mutableMapOf()
    }

    @Suppress("UNCHECKED_CAST")
    private fun savedState(conversation: String, index: Int): Map<String, Any?> =
        if (index < 0) loadAll(conversation) else (loadAll(conversation)[index.toString()] as? Map<String, Any?>).orEmpty()

    private fun save(session: Session, state: Map<String, Any?>) {
        synchronized(this) { loadAll(session.conversation)[session.index.toString()] = state }
        write(session.conversation)
    }

    private fun saveApp(conversation: String, id: String, json: String) {
        synchronized(this) { loadAll(conversation)["app:$id"] = json }
        write(conversation)
    }

    private fun write(conversation: String) {
        val target = file(conversation) ?: return
        val data = synchronized(this) { saved[conversation]?.toMap() } ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(toJson(data).toString())
            temp.renameTo(target)
        }
    }

    companion object {
        private const val MAX_SESSIONS = 40

        fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
            is List<*> -> JsonArray(value.map(::toJson))
            else -> JsonPrimitive(value.toString())
        }

        fun fromJson(element: JsonElement): Any? = when (element) {
            is JsonNull -> null
            is JsonPrimitive -> if (element.isString) element.content else element.content.toBooleanStrictOrNull() ?: element.content.toLongOrNull() ?: element.content.toDoubleOrNull()
            is JsonArray -> element.map(::fromJson)
            is JsonObject -> element.mapValues { fromJson(it.value) }
        }
    }
}
