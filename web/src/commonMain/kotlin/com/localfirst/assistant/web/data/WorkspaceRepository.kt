package com.localfirst.assistant.web.data

import com.localfirst.assistant.conversation.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import com.localfirst.assistant.web.settings.AppearanceSettings

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Credentials belong to a connected tab, never to the shared preference records. */
interface WorkspaceGateway {
    fun connect(baseUrl: String, token: String)
    fun lock()
    suspend fun artifact(id: String) { error("File downloads are unavailable on this client") }
    suspend fun request(path: String, method: String = "GET", body: JsonObject? = null): JsonElement
}
class WorkspaceException(val status: Int, message: String) : Exception(message)

@Serializable
data class SyncItem(val id: String, val revision: Int, val payload: JsonObject = JsonObject(emptyMap()), val deleted: Boolean = false)
data class SharedChat(val item: SyncItem, val conversation: StoredConversation)
data class WorkspaceSnapshot(val items: List<SyncItem>, val chats: List<SharedChat>, val knowledge: Knowledge, val knowledgeRevision: Int, val preferences: AppearanceSettings)

class WorkspaceRepository(val gateway: WorkspaceGateway) {
    suspend fun snapshot(): WorkspaceSnapshot {
        val items = wireJson.decodeFromJsonElement<List<SyncItem>>(gateway.request("/workspace/items"))
        val chats = items.filter { !it.deleted && it.payload["chat"] != null }.map { item ->
            val chat = decodeConversation(item.payload.getValue("chat").jsonPrimitive.content)
            require(chat.summary.id == item.id) { "Saved conversation ID does not match its record." }
            SharedChat(item, chat)
        }.sortedByDescending { it.conversation.summary.updatedAt }
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
    suspend fun continueChat(chat: StoredConversation, revision: Int, interfacePrompt: String = ""): JsonObject = gateway.request(
        "/workspace/items/${chat.summary.id}/continue", "POST", JsonObject(body(revision, buildJsonObject { put("chat", encodeConversation(chat)) }) + ("interface_prompt" to JsonPrimitive(interfacePrompt)))).jsonObject
    suspend fun preference(key: String, value: JsonPrimitive, items: List<SyncItem>, expectedRevision: Int? = null) {
        require(key in setOf("theme", "accent", "proactivity", "reduced_motion", "display_name", "interactive_answers"))
        val revision = expectedRevision ?: (items.firstOrNull { it.id == "preferences-$key" }?.revision ?: 0)
        gateway.request("/workspace/items/preferences-$key", "PUT", body(revision, buildJsonObject {
            put("preferences", buildJsonObject { put("value", value) }.toString())
        }))
    }
    suspend fun knowledge(value: Knowledge, revision: Int) {
        gateway.request("/workspace/items/knowledge", "PUT", body(revision, buildJsonObject { put("knowledge", wireJson.encodeToString(Knowledge.serializer(), value)) }))
    }
}

/** Proper JSON decoding preserves quotes, newlines and Unicode from the earlier local-only web app. */
@Serializable data class LegacyMessage(val role: String, val content: String)
@Serializable data class LegacyConversation(val id: String, val title: String, val messages: List<LegacyMessage>)
fun decodeLegacyChats(raw: String?): List<LegacyConversation> = raw?.let { wireJson.decodeFromString<List<LegacyConversation>>(it) } ?: emptyList()
