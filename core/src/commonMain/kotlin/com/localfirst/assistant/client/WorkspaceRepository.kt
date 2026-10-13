package com.localfirst.assistant.client

import com.localfirst.assistant.conversation.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import com.localfirst.assistant.settings.AppearanceSettings

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Connections and credentials belong to the platform host, never to shared preference records. */
interface WorkspaceGateway {
    fun connect(baseUrl: String, token: String)
    fun lock()
    suspend fun artifact(id: String) { error("File downloads are unavailable on this client") }
    suspend fun upload(name: String, mime: String, bytes: ByteArray): JsonObject { error("Uploads are unavailable on this client") }
    /** A computer file's bytes, for backups that include attachment images. */
    suspend fun fileBytes(id: String): ByteArray { error("File downloads are unavailable on this client") }
    suspend fun stream(path: String, body: JsonObject?, event: suspend (JsonObject) -> Unit) { event(request(path, if (body == null) "GET" else "POST", body).jsonObject) }
    suspend fun request(path: String, method: String = "GET", body: JsonObject? = null): JsonElement
}
class WorkspaceException(val status: Int, message: String) : Exception(message)

@Serializable
data class SyncItem(val id: String, val revision: Int, val payload: JsonObject = JsonObject(emptyMap()), val deleted: Boolean = false)
data class SharedChat(val item: SyncItem, val conversation: StoredConversation)
/** One refresh: the current records, the task list when it moved (null otherwise), and whether shared memory moved. */
data class WorkspaceChanges(val snapshot: WorkspaceSnapshot, val jobs: List<JsonObject>?, val memory: Boolean)
data class WorkspaceSnapshot(val items: List<SyncItem>, val chats: List<SharedChat>, val knowledge: Knowledge, val knowledgeRevision: Int, val preferences: AppearanceSettings)

class WorkspaceRepository(val gateway: WorkspaceGateway) {
    // Records already downloaded from this gateway, and the gateway's cursor for what changed after them.
    private var feed = ChangeFeed(gateway)
    private var cache = emptyMap<String, SyncItem>()
    private var decoded = emptyMap<String, SharedChat>()
    private var cursor = ""
    /** Call when the gateway or its credentials change. */
    fun forget() { feed = ChangeFeed(gateway); cache = emptyMap(); decoded = emptyMap(); cursor = "" }
    /**
     * Fetches what changed since the last call. With [waitSeconds], the gateway holds the request until something
     * changes, so an idle client costs one small request per wait rather than a download per poll.
     */
    suspend fun changes(waitSeconds: Int = 0): WorkspaceChanges {
        val known = cache
        val reply = feed.changes(cursor, waitSeconds, known.mapValues { it.value.revision })
        val all = reply.all
        // Answers can arrive out of order; outside a complete listing a record never goes back to an older revision.
        val moved = reply.items.filter { all != null || (known[it.id]?.revision ?: -1) < it.revision }.associateBy { it.id }
        cache = if (all == null) known + moved else all.keys.mapNotNull { id -> (moved[id] ?: known[id])?.let { id to it } }.toMap()
        cursor = reply.cursor
        val jobs = reply.jobs ?: if (feed.cursors) null else gateway.request("/workspace/jobs").jsonArray.map { it.jsonObject }
        return WorkspaceChanges(current(), jobs, reply.memory)
    }
    suspend fun snapshot(): WorkspaceSnapshot = changes().snapshot
    private fun current(): WorkspaceSnapshot {
        val items = cache.values.toList()
        val chats = items.filter { !it.deleted && it.payload["chat"] != null }.map { item ->
            decoded[item.id]?.takeIf { it.item === item } ?: run {
                val chat = decodeConversation(item.payload.getValue("chat").jsonPrimitive.content)
                require(chat.summary.id == item.id) { "Saved conversation ID does not match its record." }
                SharedChat(item, chat)
            }
        }.sortedByDescending { it.conversation.summary.updatedAt }
        decoded = chats.associateBy { it.item.id }
        val knowledgeItem = items.firstOrNull { it.id == "knowledge" && !it.deleted }
        val knowledge = knowledgeItem?.payload?.get("knowledge")?.jsonPrimitive?.content?.let { wireJson.decodeFromString<Knowledge>(it) } ?: Knowledge()
        fun value(key: String) = items.firstOrNull { it.id == "preferences-$key" && !it.deleted }?.payload?.get("preferences")?.jsonPrimitive?.content
            ?.let { wireJson.parseToJsonElement(it).jsonObject["value"]?.jsonPrimitive }
        val prefs = AppearanceSettings.from(value("theme")?.content, value("accent")?.content, value("proactivity")?.content,
            value("reduced_motion")?.booleanOrNull ?: false, value("display_name")?.content, value("interactive_answers")?.booleanOrNull ?: true)
        return WorkspaceSnapshot(items, chats, knowledge, items.firstOrNull { it.id == "knowledge" }?.revision ?: 0, prefs)
    }
    private fun body(revision: Int, payload: JsonObject, deleted: Boolean = false) = buildJsonObject {
        put("revision", revision); put("payload", payload); put("deleted", deleted)
    }
    suspend fun save(chat: StoredConversation, revision: Int): SyncItem = wireJson.decodeFromJsonElement(gateway.request(
        "/workspace/items/${chat.summary.id}", "PUT", body(revision, buildJsonObject { put("chat", encodeConversation(chat)) })))
    suspend fun delete(chat: SharedChat) { gateway.request("/workspace/items/${chat.item.id}", "PUT", body(chat.item.revision, JsonObject(emptyMap()), true)) }
    /** [imageSettings] are manual image choices that override the model's on any image this turn creates. */
    suspend fun continueChat(chat: StoredConversation, revision: Int, interfacePrompt: String = "", imageSettings: JsonObject? = null, turn: TurnOptions = TurnOptions()): JsonObject = gateway.request(
        "/workspace/items/${chat.summary.id}/continue", "POST", JsonObject(body(revision, buildJsonObject { put("chat", encodeConversation(chat)) }) + ("interface_prompt" to JsonPrimitive(interfacePrompt)) +
            (imageSettings?.let { mapOf("image_settings" to it) } ?: emptyMap()) + turn.fields())).jsonObject
    suspend fun preference(key: String, value: JsonPrimitive, items: List<SyncItem>, expectedRevision: Int? = null) {
        require(key in com.localfirst.assistant.settings.portablePreferenceKeys)
        val revision = expectedRevision ?: (items.firstOrNull { it.id == "preferences-$key" }?.revision ?: 0)
        gateway.request("/workspace/items/preferences-$key", "PUT", body(revision, buildJsonObject {
            put("preferences", buildJsonObject { put("value", value) }.toString())
        }))
    }
    suspend fun saveMemory(text: String, id: String?, category: String, scope: String, pinned: Boolean) {
        val clean = text.trim()
        require(clean.length in 1..2000) { "Memory must contain 1–2,000 characters." }
        gateway.request("/workspace/memory/memories" + (id?.let { "/$it" } ?: ""), if (id == null) "POST" else "PUT", buildJsonObject {
            put("text", clean); put("category", category); put("scope", scope); put("pinned", pinned)
        })
    }
    suspend fun forgetMemory(id: String) { gateway.request("/workspace/memory/memories/$id", "DELETE") }
    suspend fun memorySetting(key: String, enabled: Boolean) {
        require(memoryControls.any { it.key == key }) { "Unknown memory control." }
        gateway.request("/workspace/memory/settings", "PATCH", buildJsonObject { put(key, enabled) })
    }
    suspend fun scheduleTask(prompt: String, runAt: Long, intervalSeconds: Int) {
        require(prompt.isNotBlank()) { "Enter a task." }
        gateway.request("/workspace/jobs", "POST", buildJsonObject {
            put("prompt", prompt); put("run_at", runAt / 1000.0); put("interval_seconds", intervalSeconds.coerceAtLeast(0))
        })
    }
    suspend fun taskAction(id: String, action: String) {
        require(action in setOf("pause", "cancel", "resume", "delete"))
        gateway.request("/workspace/jobs/$id" + if (action == "delete") "" else "/$action", if (action == "delete") "DELETE" else "POST")
    }
    suspend fun source(id: String): JsonObject = gateway.request("/workspace/memory/sources/$id").jsonObject
    suspend fun excludeSource(id: String, excluded: Boolean) {
        gateway.request("/workspace/memory/sources/$id", "PATCH", buildJsonObject { put("excluded", excluded) })
    }
    suspend fun knowledge(value: Knowledge, revision: Int) {
        gateway.request("/workspace/items/knowledge", "PUT", body(revision, buildJsonObject { put("knowledge", wireJson.encodeToString(Knowledge.serializer(), value)) }))
    }
}

/** Per-turn choices sent with a turn: the pre-search evidence note, the user's no-search request, and the chosen model. */
data class TurnOptions(val note: String = "", val blockWeb: Boolean = false, val model: String? = null) {
    fun fields(): Map<String, JsonElement> = buildMap {
        if (note.isNotEmpty()) put("turn_note", JsonPrimitive(note.take(40000)))
        if (blockWeb) put("block_web", JsonPrimitive(true))
        model?.takeIf { it.isNotBlank() }?.let { put("model", JsonPrimitive(it)) }
    }
}

/** Proper JSON decoding preserves quotes, newlines and Unicode from the earlier local-only web app. */
@Serializable data class LegacyMessage(val role: String, val content: String)
@Serializable data class LegacyConversation(val id: String, val title: String, val messages: List<LegacyMessage>)
fun decodeLegacyChats(raw: String?): List<LegacyConversation> = raw?.let { wireJson.decodeFromString<List<LegacyConversation>>(it) } ?: emptyList()
