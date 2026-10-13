package com.localfirst.assistant.workspace

import android.content.Context
import com.localfirst.assistant.client.ChangeFeed
import com.localfirst.assistant.client.ItemChanges
import com.localfirst.assistant.client.SyncItem
import com.localfirst.assistant.client.WorkspaceException
import com.localfirst.assistant.client.WorkspaceGateway
import com.localfirst.assistant.client.wireJson
import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.json.JsonCodec
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncConflict(val id: String, val label: String, val remote: JSONObject, val phonePreview: String = "Preview unavailable", val computerPreview: String = "Preview unavailable", val phoneHash: String? = null)

/** Three-way sync: remote changes never silently overwrite local edits. */
@android.annotation.SuppressLint("ApplySharedPref") // Revision checkpoints must reach disk before the next mutation; this runs on IO.
class ChatSync(private val context: Context, private val client: WorkspaceClient, private val chats: ConversationStore,
               private val knowledge: KnowledgeStore,
               private val exchange: suspend (String, String, JSONObject?, Int) -> String = { path, method, body, timeout -> client.request(path, method, body, timeoutMs = timeout) }) {
    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, timeoutMs: Int = 120000) = exchange(path, method, body, timeoutMs)
    private val lock = Mutex()
    // Only portable preferences are allowed onto the shared server. Never credentials or phone access.
    private val appearance get() = context.getSharedPreferences("friday_appearance", Context.MODE_PRIVATE)
    private val preferenceKeys = com.localfirst.assistant.settings.portablePreferenceKeys.toList()
    private fun preferenceKey(id: String) = id.removePrefix("preferences-").takeIf { id.startsWith("preferences-") && it in preferenceKeys }
    private val prefs get() = context.getSharedPreferences("workspace-sync-${digest(client.serverIdentity)}", Context.MODE_PRIVATE)
    private val attachments = File(context.filesDir, "attachments")

    private fun digest(text: String?) = text?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } } ?: "deleted"
    private fun local(id: String): String? = if (preferenceKey(id) != null) {
        val key = preferenceKey(id)!!
        appearance.all[key]?.let { JSONObject().put("value", it).toString() }
    } else if (id == "knowledge") {
        val k = knowledge.load()
        if (k.memories.isEmpty() && k.projects.isEmpty() && !prefs.contains("hash:knowledge")) null
        else JsonCodec.json.encodeToString(Knowledge.serializer(), k)
    } else chats.load(id)?.let(::encodeConversation)

    private suspend fun portable(id: String, text: String): JSONObject {
        if (preferenceKey(id) != null) return JSONObject().put("preferences", text)
        if (id == "knowledge") return JSONObject().put("knowledge", text)
        val json = JSONObject(text)
        val messages = json.getJSONArray("messages")
        for (i in 0 until messages.length()) {
            val files = messages.getJSONObject(i).optJSONArray("attachments") ?: continue
            for (j in 0 until files.length()) {
                val a = files.getJSONObject(j)
                suspend fun remote(path: String, knownId: String? = null): String {
                    if (path.startsWith("remote:")) return path
                    val file = File(path)
                    if (!file.isFile) return ""
                    require(file.canonicalFile.parentFile == attachments.canonicalFile) { "Attachment outside private storage." }
                    val md = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
                    }
                    val key = "asset:" + md.digest().joinToString("") { b -> "%02x".format(b) }
                    val asset = prefs.getString(key, null) ?: knownId ?: client.upload(file).also { prefs.edit().putString(key, it).commit() }
                    return "remote:$asset"
                }
                a.optString("path").takeIf { it.isNotBlank() && it != "null" }?.let { a.put("path", remote(it, a.optString("remoteFileId").takeIf { id -> id.matches(Regex("[a-f0-9]{32}")) })) }
                val pages = a.optJSONArray("pageImages")
                if (pages != null) for (p in 0 until pages.length()) pages.put(p, remote(pages.getString(p)))
            }
        }
        return JSONObject().put("chat", json.toString())
    }

    private suspend fun apply(id: String, remote: JSONObject): String {
        val before = digest(local(id))
        preferenceKey(id)?.let { key ->
            val edit = appearance.edit()
            if (remote.optBoolean("deleted")) edit.remove(key)
            else {
                val value = JSONObject(remote.getJSONObject("payload").getString("preferences")).get("value")
                when (key) {
                    "reduced_motion", "interactive_answers" -> { require(value is Boolean); edit.putBoolean(key, value) }
                    else -> { require(value is String); edit.putString(key, value) }
                }
            }
            require(digest(local(id)) == before) { "Preferences changed during sync. Sync again." }
            check(edit.commit()) { "Could not save synced preferences." }
            return digest(if (remote.optBoolean("deleted")) null else remote.getJSONObject("payload").getString("preferences"))
        }
        if (remote.optBoolean("deleted")) {
            if (id == "knowledge") knowledge.save(Knowledge()) else chats.delete(id)
            return if (id == "knowledge") digest(JsonCodec.json.encodeToString(Knowledge.serializer(), Knowledge())) else digest(null)
        }
        val payload = remote.getJSONObject("payload")
        if (id == "knowledge") {
            require(digest(local(id)) == before) { "Projects changed during sync. Sync again." }
            val value = JsonCodec.json.decodeFromString(Knowledge.serializer(), payload.getString("knowledge"))
            knowledge.save(value)
            return digest(JsonCodec.json.encodeToString(Knowledge.serializer(), value))
        } else {
            val raw = JSONObject(payload.getString("chat"))
            val messages = raw.getJSONArray("messages")
            for (i in 0 until messages.length()) {
                val files = messages.getJSONObject(i).optJSONArray("attachments") ?: continue
                for (j in 0 until files.length()) {
                    val a = files.getJSONObject(j)
                    suspend fun localPath(path: String): String? {
                        if (!path.startsWith("remote:")) return null
                        val fileId = path.removePrefix("remote:")
                        require(fileId.matches(Regex("[a-f0-9]{32}")))
                        val file = File(attachments, "$fileId.bin")
                        if (!file.isFile) client.download(fileId, file)
                        return file.absolutePath
                    }
                    a.optString("path").takeIf { it.isNotBlank() }?.let { a.put("path", localPath(it) ?: JSONObject.NULL) }
                    val pages = a.optJSONArray("pageImages")
                    if (pages != null) for (p in 0 until pages.length()) pages.put(p, localPath(pages.getString(p)) ?: "")
                }
            }
            val chat = decodeConversation(raw.toString())
            require(chat.summary.id == id) { "Invalid synced chat." }
            require(digest(local(id)) == before) { "Chat changed during sync. Sync again to resolve it." }
            chats.save(chat)
            return digest(encodeConversation(chat))
        }
    }

    // The shared feed asks the computer what changed; this class decides what the phone does about it.
    private val feed = ChangeFeed(object : WorkspaceGateway {
        override fun connect(baseUrl: String, token: String) { error("Use native connection settings") }
        override fun lock() {}
        override suspend fun request(path: String, method: String, body: JsonObject?): JsonElement = try {
            // A held request needs longer than its wait. An unheld one keeps the short limit, so an unreachable computer fails fast.
            val wait = path.substringAfter("wait=", "0").substringBefore('&').toIntOrNull() ?: 0
            val listing = path.startsWith("/workspace/changes") || path.endsWith("?revisions=1")
            wireJson.parseToJsonElement(exchange(path, method, body?.let { JSONObject(it.toString()) }, if (!listing) 120000 else if (wait > 0) (wait + 10) * 1000 else 3000))
        } catch (e: java.io.IOException) {
            if (e.message?.startsWith("Computer returned HTTP 404") == true) throw WorkspaceException(404, e.message!!) else throw e
        }
    }, timerSeconds = 15)
    private var early: Pair<String, ItemChanges>? = null
    /** Saved revisions by record id: "revision:" is what this phone last reconciled, "remote:" what the computer last reported. */
    private fun revisions(prefix: String) = prefs.all.filterKeys { it.startsWith(prefix) }.map { it.key.removePrefix(prefix) to it.value as Int }.toMap()

    /** Returns when the computer reports a change, or after [seconds]. The answer is kept for the next [sync]. */
    suspend fun awaitRemoteChange(seconds: Int) {
        val cursor = prefs.getString("cursor", "")!!
        // An answer the phone has not been able to sync yet (a chat is streaming) is not asked for again.
        if (early?.takeIf { it.first == cursor }?.second?.items?.isNotEmpty() == true) return kotlinx.coroutines.delay(seconds * 1000L)
        early = cursor to feed.changes(cursor, seconds, revisions("revision:"))
    }

    /** Records what the computer now holds and returns the complete records this phone still has to reconcile. */
    private suspend fun pull(): Map<String, JSONObject> {
        val cursor = prefs.getString("cursor", "")!!
        val changes = early?.takeIf { it.first == cursor }?.second ?: feed.changes(cursor, 0, revisions("revision:"))
        early = null
        val edit = prefs.edit()
        changes.all?.let { all ->
            revisions("remote:").keys.filter { it !in all }.forEach { edit.remove("remote:$it") }
            all.forEach { (id, revision) -> edit.putInt("remote:$id", revision) }
        }
        changes.items.forEach { edit.putInt("remote:${it.id}", it.revision) }
        edit.putString("cursor", changes.cursor).commit()
        val delivered = changes.items.associateBy { it.id }
        // Changes reported in an earlier sync and not reconciled then: an open conflict, or a sync that was interrupted.
        val owed = revisions("remote:").filter { (id, revision) -> revision != prefs.getInt("revision:$id", 0) && id !in delivered }.keys
        val fetched = feed.items(owed).associateBy { it.id }
        val gone = owed - fetched.keys
        if (gone.isNotEmpty()) prefs.edit().apply { gone.forEach { remove("remote:$it") } }.commit()
        return (delivered + fetched).mapValues { JSONObject(wireJson.encodeToString(SyncItem.serializer(), it.value)) }
    }

    suspend fun sync(): List<SyncConflict> = lock.withLock {
        val remote = pull()
        val heard = revisions("remote:")
        val known = prefs.all.keys.filter { it.startsWith("hash:") }.map { it.removePrefix("hash:") }
        val supported = remote.values.filter { it.getString("id") == "knowledge" || preferenceKey(it.getString("id")) != null || it.getJSONObject("payload").has("chat") || it.optBoolean("deleted") }.map { it.getString("id") }
        val ids = (chats.list().map { it.id } + "knowledge" + preferenceKeys.map { "preferences-$it" } + supported + known).distinct()
        val conflicts = mutableListOf<SyncConflict>()
        for (id in ids) {
            val text = local(id)
            val hash = digest(text)
            val last = prefs.getString("hash:$id", null)
            val expected = prefs.getInt("revision:$id", 0)
            val server = remote[id]
            val actual = heard[id] ?: 0
            if (text == null && last == null && actual == 0) continue
            if (preferenceKey(id) != null && text != null && server != null && !server.optBoolean("deleted") &&
                JSONObject(text).get("value") == JSONObject(server.getJSONObject("payload").getString("preferences")).get("value")) {
                prefs.edit().putInt("revision:$id", actual).putString("hash:$id", hash).commit()
                continue
            }
            if (actual != expected && (last == null && text != null || last != null && hash != last)) {
                val remoteText = if (server!!.optBoolean("deleted")) null else server.getJSONObject("payload").optString(if (id == "knowledge") "knowledge" else if (preferenceKey(id) != null) "preferences" else "chat", "").takeIf { it.isNotEmpty() }
                conflicts += SyncConflict(id, chats.load(id)?.summary?.title ?: if (id == "knowledge") "Memories and projects" else id, server,
                    if (preferenceKey(id) != null) text ?: "Default" else com.localfirst.assistant.presentation.SyncPreview.describe(text, id == "knowledge"),
                    if (preferenceKey(id) != null) remoteText ?: "Default" else com.localfirst.assistant.presentation.SyncPreview.describe(remoteText, id == "knowledge"), hash)
                continue
            }
            val syncedHash = if (actual != expected && server != null) {
                apply(id, server)
            } else if (hash != last) {
                val body = JSONObject().put("revision", actual).put("deleted", text == null)
                    .put("payload", if (text == null) JSONObject() else portable(id, text))
                val saved = JSONObject(request("/workspace/items/$id", "PUT", body))
                // An edit during upload remains dirty and is sent next time.
                prefs.edit().putInt("revision:$id", saved.getInt("revision")).putInt("remote:$id", saved.getInt("revision")).putString("hash:$id", hash).commit()
                continue
            } else hash
            prefs.edit().putInt("revision:$id", actual).putString("hash:$id", syncedHash).commit()
        }
        // Records this app does not handle are noted as seen, so they are not fetched again.
        val other = remote.keys.filter { it !in ids }
        if (other.isNotEmpty()) prefs.edit().apply { other.forEach { putInt("revision:$it", heard[it] ?: 0) } }.commit()
        conflicts
    }

    suspend fun resolve(conflict: SyncConflict, keepPhone: Boolean) = lock.withLock {
        require(conflict.phoneHash == null || digest(local(conflict.id)) == conflict.phoneHash) { "This phone’s copy changed. Sync again to compare the latest versions." }
        if (keepPhone) {
            val text = local(conflict.id)
            val saved = JSONObject(request("/workspace/items/${conflict.id}", "PUT", JSONObject()
                .put("revision", conflict.remote.getInt("revision")).put("deleted", text == null)
                .put("payload", if (text == null) JSONObject() else portable(conflict.id, text))))
            prefs.edit().putInt("revision:${conflict.id}", saved.getInt("revision")).putInt("remote:${conflict.id}", saved.getInt("revision"))
                .putString("hash:${conflict.id}", digest(text)).commit()
        } else {
            val hash = apply(conflict.id, conflict.remote)
            prefs.edit().putInt("revision:${conflict.id}", conflict.remote.getInt("revision"))
                .putString("hash:${conflict.id}", hash).commit()
        }
    }
}
