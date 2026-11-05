package com.localfirst.assistant.artifacts

import com.localfirst.assistant.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.random.Random

fun artifactKey(): String = (1..32).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
fun editableArtifactId(uri: String): String? = Regex("^assistant://document/([a-f0-9]{32})$").matchEntire(uri)?.groupValues?.get(1)
fun blankContent(kind: String): JsonObject = wireJson.parseToJsonElement(when (kind) {
    "app" -> """{"version":1,"format":"html","source":"<h1>Hello</h1><p>Edit this app's source, then preview it.</p>"}"""
    "slides" -> """{"version":1,"theme":"light","slides":[{"title":"New presentation","body":"Add your first slide."}]}"""
    "sheet" -> """{"version":1,"cells":[["Item","Value"],["Example",1],["Total","=SUM(B2:B2)"]]}"""
    else -> """{"version":1,"blocks":[{"id":"${artifactKey()}","type":"paragraph","text":""}]}"""
}).jsonObject

interface ArtifactDrafts {
    fun read(id: String): JsonObject?
    fun write(id: String, draft: JsonObject?)
}

data class ArtifactState(
    val items: List<JsonObject> = emptyList(), val projects: List<JsonObject> = emptyList(), val selected: JsonObject? = null,
    val content: JsonObject? = null, val title: String = "", val revision: Int = 0,
    val dirty: Boolean = false, val appDirty: Boolean = false, val transitioning: Boolean = false, val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    val deleted: Boolean = false, val history: List<JsonObject> = emptyList(), val proposals: List<JsonObject> = emptyList(),
    val comments: List<JsonObject> = emptyList(), val fileComments: List<JsonObject> = emptyList(), val selection: JsonObject? = null,
    val preview: JsonObject? = null, val exported: JsonObject? = null, val calculation: JsonObject? = null,
    val appData: JsonObject = buildJsonObject {}, val appHistory: List<JsonObject> = emptyList(), val appRevision: Int = 0, val editorKey: String = artifactKey(),
)

/** One controller for both hosts. Every acknowledgment is bound to its captured draft and artifact. */
class ArtifactController(private val gateway: WorkspaceGateway, private val scope: CoroutineScope, private val drafts: ArtifactDrafts? = null) {
    private val mutable = MutableStateFlow(ArtifactState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var autosave: Job? = null
    private var generation = 0
    private var operationSerial = 0
    private var pendingSave: Pair<String, JsonObject>? = null
    private var pendingAppSave: Pair<String, JsonObject>? = null
    private val retries = linkedMapOf<String, JsonObject>()
    private var appSave: Job? = null
    fun reset() { generation++; operation?.cancel(); autosave?.cancel(); appSave?.cancel(); pendingSave = null; pendingAppSave = null; retries.clear(); mutable.value = ArtifactState() }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    private fun run(transition: Boolean = false, block: suspend () -> Unit): Job? {
        if (state.value.busy) return null
        val epoch = generation
        val serial = ++operationSerial
        mutable.update { it.copy(busy = true, transitioning = transition, error = null) }
        return scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation && serial == operationSerial) mutable.update { it.copy(error = e.message ?: "Artifact operation failed. Your draft is preserved.") } }
            finally { if (epoch == generation && serial == operationSerial) mutable.update { it.copy(busy = false, transitioning = false) } }
        }.also { operation = it }
    }
    private suspend fun request(path: String, body: JsonObject? = null): JsonElement {
        if (body == null) return gateway.request(path)
        val identity = path + "|" + JsonObject(body - "request").toString()
        val payload = retries.getOrPut(identity) { body }
        while (retries.size > 16) retries.remove(retries.keys.first())
        return gateway.request(path, "POST", payload).also { retries.remove(identity) }
    }
    private fun body(block: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("request", artifactKey()); block() }
    private fun path() = "/workspace/artifacts/${state.value.selected?.string("id") ?: error("Open an artifact first") }"
    fun refresh(deleted: Boolean = state.value.deleted) = run {
        val rows = request("/workspace/artifacts?deleted=$deleted").rows()
        val projects = request("/workspace/artifact-projects").rows()
        mutable.update { it.copy(items = rows, projects = projects, deleted = deleted) }
    }
    private suspend fun load(id: String) {
        val epoch = generation
        val item = request("/workspace/artifacts/$id").jsonObject
        val draft = drafts?.read(id)
        if (epoch != generation) return
        pendingSave = null; pendingAppSave = null
        mutable.update { it.copy(selected = item, content = draft?.get("content")?.jsonObject ?: item["content"]!!.jsonObject,
            title = draft?.string("title") ?: item.string("title"), revision = draft?.get("base")?.jsonPrimitive?.int ?: item["revision"]!!.jsonPrimitive.int,
            dirty = draft != null, selection = null, editorKey = artifactKey(), preview = null, exported = null, calculation = null,
            notice = if (draft != null) "Recovered an unsaved draft. Save it, or save a copy if another client has edited this artifact." else null) }
        details()
        if (item.string("kind") == "app") {
            val data = request("/workspace/artifacts/$id/state").jsonObject
            mutable.update { it.copy(appRevision = draft?.get("appBase")?.jsonPrimitive?.int ?: data["revision"]!!.jsonPrimitive.int, appData = draft?.get("appData") as? JsonObject ?: data["content"] as? JsonObject ?: buildJsonObject {}, appDirty = draft?.get("appData") != null) }
        }
    }
    private suspend fun details() {
        val p = path()
        val history = request("$p/history").rows(); val proposals = request("$p/proposals").rows(); val comments = request("$p/comments").rows()
        mutable.update { it.copy(history = history, proposals = proposals, comments = comments) }
    }
    fun open(id: String) = run(transition = true) { if (state.value.dirty || state.value.appDirty) saveNow(); load(id) }
    fun back() { if (!state.value.dirty && !state.value.appDirty && !state.value.busy) { mutable.update { it.copy(selected = null, preview = null) }; refresh() } }
    fun create(kind: String, template: JsonObject? = null) = run(transition = true) {
        if (state.value.dirty || state.value.appDirty) saveNow()
        val original = template?.let { request("/workspace/artifacts/${it.string("id")}").jsonObject }
        val made = request("/workspace/artifacts", body { put("kind", original?.string("kind") ?: kind); put("title", original?.string("title")?.plus(" copy") ?: "Untitled $kind"); put("content", original?.get("content") ?: blankContent(kind)) }).jsonObject
        load(made.string("id"))
    }
    fun edit(content: JsonObject? = state.value.content, title: String = state.value.title) {
        if (state.value.transitioning || content == null || state.value.content == content && state.value.title == title) return
        mutable.update { it.copy(content = content, title = title, dirty = true, calculation = null) }
        persistDraft()
        autosave?.cancel()
        autosave = scope.launch {
            delay(1500)
            while (state.value.busy) delay(100)
            if (state.value.error == null && state.value.dirty) save()
        }
    }
    private fun persistDraft() {
        val s = state.value
        s.selected?.string("id")?.let { id -> writeDraft(id, buildJsonObject { put("title", s.title); put("base", s.revision); put("content", s.content!!); if(s.appDirty) { put("appData", s.appData); put("appBase", s.appRevision) } }) }
    }
    private fun writeDraft(id: String, draft: JsonObject?) {
        try { drafts?.write(id, draft) }
        catch (_: Exception) { mutable.update { it.copy(notice = "Device draft recovery is unavailable. Keep this workspace open until your changes are saved to the server.") } }
    }
    fun selection(value: JsonObject?) { mutable.update { it.copy(selection = value) } }
    suspend fun saveNow() {
        if (state.value.appDirty) saveAppNow()
        val s = state.value
        if (!s.dirty) return
        val id = s.selected?.string("id") ?: return
        val payload = pendingSave?.takeIf { it.first == id }?.second ?: body { put("base", s.revision); put("title", s.title); put("content", s.content!!); put("summary", "Edited in workspace") }.also { pendingSave = id to it }
        val response = request("/workspace/artifacts/$id", payload).jsonObject
        pendingSave = null
        if (state.value.selected?.string("id") != id) return
        val savedContent = payload["content"]!!.jsonObject
        val savedTitle = payload.string("title")
        mutable.update { it.copy(revision = response["revision"]!!.jsonPrimitive.int, dirty = it.content != savedContent || it.title != savedTitle, notice = "Saved revision ${response["revision"]}") }
        if (state.value.dirty) { persistDraft(); saveNow() } else writeDraft(id, null)
    }
    fun save() = run { saveNow() }
    fun saveCopy() = run(transition = true) {
        val s = state.value
        val made = request("/workspace/artifacts", body { put("kind", s.selected!!.string("kind")); put("title", s.title + " copy"); put("content", s.content!!); if(s.appDirty) { put("appData", s.appData); put("appBase", s.appRevision) } }).jsonObject
        if (s.selected!!.string("kind") == "app") request("/workspace/artifacts/${made.string("id")}/state", body { put("base", 0); put("content", s.appData) })
        writeDraft(s.selected!!.string("id"), null)
        load(made.string("id"))
    }
    fun reload() = run(transition = true) { val id = state.value.selected!!.string("id"); drafts?.write(id, null); load(id) }
    fun restore(revision: Int) = run(transition = true) { saveNow(); request(path(), body { put("base", state.value.revision); put("restore", revision); put("summary", "Restored revision $revision") }); load(state.value.selected!!.string("id")) }
    fun trash(deleted: Boolean) = run(transition = true) {
        saveNow(); request(path()+"/trash", body { put("base", state.value.revision); put("deleted", deleted) })
        val items = request("/workspace/artifacts?deleted=${state.value.deleted}").rows()
        mutable.update { it.copy(selected = null, items = items) }
    }
    fun project(id: String) = run(transition = true) { saveNow(); request(path(), body { put("base", state.value.revision); put("project", id); put("summary", "Changed project") }); load(state.value.selected!!.string("id")) }
    fun purge(title: String) = run(transition = true) {
        request(path()+"/purge", body { put("base", state.value.revision); put("confirmTitle", title) })
        val items = request("/workspace/artifacts?deleted=true").rows()
        mutable.update { it.copy(selected = null, items = items, deleted = true) }
    }
    fun template() = run(transition = true) { saveNow(); request(path(), body { put("base", state.value.revision); put("template", true); put("summary", "Saved as reusable template") }); load(state.value.selected!!.string("id")) }
    fun suggest(instruction: String) = run {
        val selection = state.value.selection ?: error("Select text in the rich editor first")
        saveNow()
        request(path()+"/suggest", body { put("base", state.value.revision); put("instruction", instruction); put("block", selection["block"]!!); put("from", selection["from"]!!); put("to", selection["to"]!!) })
        details(); mutable.update { it.copy(notice = "Suggestion ready for review. Your document is unchanged.") }
    }
    fun decide(pid: String, accept: Boolean) = run(transition = true) {
        saveNow(); request(path()+"/proposals/$pid", body { put("accept", accept) }); load(state.value.selected!!.string("id"))
    }
    fun comment(text: String) = run {
        saveNow(); request(path()+"/comments", body { put("text", text); state.value.selection?.let { put("block", it["block"]!!); put("quote", it.string("text").take(2000)) } }); details()
    }
    fun resolve(id: String, resolved: Boolean) = run { request(path()+"/comments", body { put("id", id); put("resolved", resolved) }); details() }
    fun export(format: String) = run {
        saveNow()
        val result = request(path()+"/export", body { put("revision", state.value.revision); put("format", format) }).jsonObject
        mutable.update { it.copy(exported = result, notice = result["warnings"]?.jsonArray?.joinToString("\n") { it.jsonPrimitive.content }.orEmpty()) }
    }
    fun preview(id: String, page: Int = 0) = run {
        val result = request("/workspace/artifact-preview/$id?page=$page").jsonObject
        val comments = request("/workspace/artifact-annotations/$id").rows()
        mutable.update { it.copy(preview = result, fileComments = comments) }
    }
    fun annotate(id: String, page: Int, text: String) = run {
        request("/workspace/artifact-annotations/$id", body { put("page", page); put("text", text) })
        val comments = request("/workspace/artifact-annotations/$id").rows()
        mutable.update { it.copy(fileComments = comments) }
    }
    fun cancel() { operationSerial++; operation?.cancel(); mutable.update { it.copy(busy = false, transitioning = false, notice = "Stopped waiting. Retry to reconcile any pending save.") } }
    fun import(file: String) = run(transition = true) {
        if (state.value.dirty || state.value.appDirty) saveNow()
        val made = request("/workspace/artifact-import", body { put("file", file) }).jsonObject
        load(made.string("id")); mutable.update { it.copy(notice = made["warnings"]?.jsonArray?.joinToString("\n") { it.jsonPrimitive.content }) }
    }
    fun backup(open: (String) -> Unit) = run { open(request("/workspace/artifact-backup", body {}).jsonObject.string("id")) }
    fun restoreBackup(file: String) = run {
        val result = request("/workspace/artifact-restore", body { put("file", file) }).jsonObject
        val items = request("/workspace/artifacts").rows()
        mutable.update { it.copy(items = items, notice = "Restored ${result["restored"]} artifact copies, including revision history.") }
    }
    fun calculate() = run { saveNow(); val result = request(path()+"/calculate", body {}).jsonObject; mutable.update { it.copy(calculation = result) } }
    fun appState(id: String, json: String, artifact: String) {
        if (state.value.transitioning || state.value.selected?.string("id") != artifact) return
        val value = wireJson.parseToJsonElement(json)
        require(json.length <= 200000) { "App data exceeds 200 KB" }
        mutable.update { it.copy(appData = buildJsonObject { it.appData.forEach { (k,v) -> put(k,v) }; put(id, value) }, appDirty = true) }
        persistDraft()
        appSave?.cancel(); appSave = scope.launch {
            delay(600)
            while (state.value.busy) delay(100)
            run { saveAppNow() }
        }
    }
    private suspend fun saveAppNow() {
        val s = state.value
        if(!s.appDirty) return
        val id = s.selected!!.string("id")
        val payload = pendingAppSave?.takeIf { it.first == id }?.second ?: body { put("base", s.appRevision); put("content", s.appData) }.also { pendingAppSave = id to it }
        val result = request("/workspace/artifacts/$id/state", payload).jsonObject
        pendingAppSave = null
        if(state.value.selected?.string("id") != id) return
        mutable.update { it.copy(appRevision = result["revision"]!!.jsonPrimitive.int, appDirty = it.appData != payload["content"]) }
        if(state.value.dirty || state.value.appDirty) persistDraft() else writeDraft(id, null)
        if(state.value.appDirty) saveAppNow()
    }
    fun appHistory() = run { val rows = request(path()+"/state/history").rows(); mutable.update { it.copy(appHistory = rows) } }
    fun restoreAppData(revision: Int) = run(transition = true) {
        saveNow(); request(path()+"/state", body { put("base", state.value.appRevision); put("restore", revision) }); load(state.value.selected!!.string("id"))
    }
    fun resetAppData() = run(transition = true) {
        saveNow()
        val result = request(path()+"/state", body { put("base", state.value.appRevision); put("content", buildJsonObject {}) }).jsonObject
        mutable.update { it.copy(appData = buildJsonObject {}, appDirty = false, appRevision = result["revision"]!!.jsonPrimitive.int, editorKey = artifactKey()) }
        writeDraft(state.value.selected!!.string("id"), null)
    }
}
