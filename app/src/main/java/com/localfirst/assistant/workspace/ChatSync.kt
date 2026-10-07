package com.localfirst.assistant.workspace

import android.content.Context
import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.json.JsonCodec
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject

data class SyncConflict(val id: String, val label: String, val remote: JSONObject, val phonePreview: String = "Preview unavailable", val computerPreview: String = "Preview unavailable", val phoneHash: String? = null)

/** Three-way sync: remote changes never silently overwrite local edits. */
@android.annotation.SuppressLint("ApplySharedPref") // Revision checkpoints must reach disk before the next mutation; this runs on IO.
class ChatSync(private val context: Context, private val client: WorkspaceClient, private val chats: ConversationStore,
               private val knowledge: KnowledgeStore) {
    private val prefs get() = context.getSharedPreferences("workspace-sync-${digest(client.serverIdentity)}", Context.MODE_PRIVATE)
    private val attachments = File(context.filesDir, "attachments")

    private fun digest(text: String?) = text?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } } ?: "deleted"
    private fun local(id: String): String? = if (id == "knowledge") {
        val k = knowledge.load()
        if (k.memories.isEmpty() && k.projects.isEmpty() && !prefs.contains("hash:knowledge")) null
        else JsonCodec.json.encodeToString(Knowledge.serializer(), k)
    } else chats.load(id)?.let(::encodeConversation)

    private suspend fun portable(id: String, text: String): JSONObject {
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

    private suspend fun apply(id: String, remote: JSONObject) {
        val before = digest(local(id))
        if (remote.optBoolean("deleted")) {
            if (id == "knowledge") knowledge.save(Knowledge()) else chats.delete(id)
            return
        }
        val payload = remote.getJSONObject("payload")
        if (id == "knowledge") {
            knowledge.save(JsonCodec.json.decodeFromString(Knowledge.serializer(), payload.getString("knowledge")))
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
        }
    }

    suspend fun sync(): List<SyncConflict> {
        val list = JSONArray(client.request("/workspace/items"))
        val remote = (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("id") }
        val known = prefs.all.keys.filter { it.startsWith("hash:") }.map { it.removePrefix("hash:") }
        val ids = (chats.list().map { it.id } + "knowledge" + remote.keys + known).distinct()
        val conflicts = mutableListOf<SyncConflict>()
        for (id in ids) {
            val text = local(id)
            val hash = digest(text)
            val last = prefs.getString("hash:$id", null)
            val expected = prefs.getInt("revision:$id", 0)
            val server = remote[id]
            val actual = server?.getInt("revision") ?: 0
            if (actual != expected && (last == null && text != null || last != null && hash != last)) {
                val remoteText = if (server!!.optBoolean("deleted")) null else server.getJSONObject("payload").optString(if (id == "knowledge") "knowledge" else "chat", "").takeIf { it.isNotEmpty() }
                conflicts += SyncConflict(id, chats.load(id)?.summary?.title ?: if (id == "knowledge") "Memories and projects" else id, server,
                    com.localfirst.assistant.presentation.SyncPreview.describe(text, id == "knowledge"),
                    com.localfirst.assistant.presentation.SyncPreview.describe(remoteText, id == "knowledge"), hash)
                continue
            }
            if (actual != expected && server != null) {
                apply(id, server)
            } else if (hash != last) {
                val body = JSONObject().put("revision", actual).put("deleted", text == null)
                    .put("payload", if (text == null) JSONObject() else portable(id, text))
                val saved = JSONObject(client.request("/workspace/items/$id", "PUT", body))
                // An edit during upload remains dirty and is sent next time.
                prefs.edit().putInt("revision:$id", saved.getInt("revision")).putString("hash:$id", hash).commit()
                continue
            }
            prefs.edit().putInt("revision:$id", actual).putString("hash:$id", digest(local(id))).commit()
        }
        return conflicts
    }

    suspend fun resolve(conflict: SyncConflict, keepPhone: Boolean) {
        require(conflict.phoneHash == null || digest(local(conflict.id)) == conflict.phoneHash) { "This phone’s copy changed. Sync again to compare the latest versions." }
        if (keepPhone) {
            val text = local(conflict.id)
            val saved = JSONObject(client.request("/workspace/items/${conflict.id}", "PUT", JSONObject()
                .put("revision", conflict.remote.getInt("revision")).put("deleted", text == null)
                .put("payload", if (text == null) JSONObject() else portable(conflict.id, text))))
            prefs.edit().putInt("revision:${conflict.id}", saved.getInt("revision"))
                .putString("hash:${conflict.id}", digest(text)).commit()
        } else {
            apply(conflict.id, conflict.remote)
            prefs.edit().putInt("revision:${conflict.id}", conflict.remote.getInt("revision"))
                .putString("hash:${conflict.id}", digest(local(conflict.id))).commit()
        }
    }
}
