package com.localfirst.assistant.workspace

import android.content.Context
import android.net.Uri
import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.json.JsonCodec
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Portable backups exclude server addresses, credentials, preferences, and executable code. */
class BackupService(private val context: Context, private val chats: ConversationStore, private val knowledge: KnowledgeStore) {
    fun export(uri: Uri) {
        val root = File(context.filesDir, "attachments").canonicalFile
        val temporary = File.createTempFile("assistant-backup-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(temporary.outputStream()).use { zip ->
                fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                val assets = linkedMapOf<String, File>()
                chats.list().forEach { summary -> chats.load(summary.id)?.let { chat ->
                    val raw = JSONObject(encodeConversation(chat))
                    val messages = raw.getJSONArray("messages")
                    for (i in 0 until messages.length()) {
                        val aa = messages.getJSONObject(i).optJSONArray("attachments") ?: continue
                        for (j in 0 until aa.length()) {
                            val a = aa.getJSONObject(j)
                            fun path(value: String): String? {
                                val file = File(value)
                                if (!file.isFile || file.canonicalFile.parentFile != root) return null
                                val key = "assets/${file.name}"
                                assets[key] = file
                                return key
                            }
                            a.optString("path").takeIf { it.isNotBlank() }?.let { a.put("path", path(it) ?: JSONObject.NULL) }
                            a.optJSONArray("pageImages")?.let { pages ->
                                for (p in 0 until pages.length()) pages.put(p, path(pages.getString(p)) ?: "")
                            }
                        }
                    }
                    entry("chats/${summary.id}.json", raw.toString().toByteArray())
                } }
                entry("knowledge.json", JsonCodec.json.encodeToString(Knowledge.serializer(), knowledge.load()).toByteArray())
                require(assets.values.sumOf { it.length() } < 100L * 1024 * 1024) { "Attachments exceed the 100 MB backup limit." }
                assets.forEach { (name, file) -> entry(name, file.readBytes()) }
            }
            context.contentResolver.openOutputStream(uri)?.use { output ->
                temporary.inputStream().use { it.copyTo(output) }
            } ?: error("Couldn't create backup.")
        } finally { temporary.delete() }
    }

    fun restore(uri: Uri): Int {
        val scratch = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var total = 0L
            var count = 0
            context.contentResolver.openInputStream(uri)?.use { stream -> ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 3000) { "Backup has too many files." }
                    val target = File(scratch, entry.name).canonicalFile
                    require(target.path.startsWith(scratch.canonicalPath + File.separator) && !entry.isDirectory) { "Invalid backup path." }
                    require(entry.name.matches(Regex("(chats/[A-Za-z0-9-]+\\.json|assets/[A-Za-z0-9._-]+|knowledge\\.json)"))) { "Unknown backup entry." }
                    require(!target.exists()) { "Duplicate backup entry." }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 100L * 1024 * 1024 && target.length() + n <= 25L * 1024 * 1024) { "Backup exceeds size limits." }
                            output.write(buffer, 0, n)
                        }
                    }
                }
            } } ?: error("Couldn't read backup.")
            val imported = File(scratch, "chats").listFiles().orEmpty().map { file ->
                require(file.length() <= 8 * 1024 * 1024)
                val raw = JSONObject(file.readText())
                val messages = raw.getJSONArray("messages")
                for (i in 0 until messages.length()) {
                    val aa = messages.getJSONObject(i).optJSONArray("attachments") ?: continue
                    for (j in 0 until aa.length()) {
                        val a = aa.getJSONObject(j)
                        fun asset(value: String): String? {
                            if (value.isBlank() || value == "null") return null
                            require(value.matches(Regex("assets/[A-Za-z0-9._-]+"))) { "Invalid attachment path." }
                            val source = File(scratch, value)
                            if (!source.isFile) return null
                            // Unique paths prevent restore from overwriting existing attachment files.
                            val dest = File(context.filesDir, "attachments/${UUID.randomUUID()}-${source.name}")
                            dest.parentFile?.mkdirs(); source.copyTo(dest)
                            return dest.absolutePath
                        }
                        a.optString("path").takeIf { it.isNotBlank() }?.let { a.put("path", asset(it) ?: JSONObject.NULL) }
                        a.optJSONArray("pageImages")?.let { pages -> for (p in 0 until pages.length()) pages.put(p, asset(pages.getString(p)) ?: "") }
                    }
                }
                decodeConversation(raw.toString()).let { it.copy(summary = it.summary.copy(id = UUID.randomUUID().toString())) }
            }
            val kFile = File(scratch, "knowledge.json")
            val incoming = if (kFile.isFile) JsonCodec.json.decodeFromString(Knowledge.serializer(), kFile.readText()) else Knowledge()
            val current = knowledge.load()
            val projectIds = mutableMapOf<String, String>()
            val projects = incoming.projects.map { p ->
                if (current.projects.any { it.id == p.id && it != p }) {
                    val replacement = UUID.randomUUID().toString(); projectIds[p.id] = replacement; p.copy(id = replacement)
                } else p
            }
            val memories = incoming.memories.map { m ->
                if (current.memories.any { it.id == m.id && it.text != m.text }) m.copy(id = UUID.randomUUID().toString()) else m
            }
            val merged = Knowledge((current.memories + memories).distinctBy { it.text },
                (current.projects + projects).distinctBy { it.id })
            knowledge.save(merged)
            imported.map { c -> c.copy(summary = c.summary.copy(projectId = projectIds[c.summary.projectId] ?: c.summary.projectId)) }.forEach(chats::save)
            return imported.size
        } finally { scratch.deleteRecursively() }
    }

    fun markdown(id: String, uri: Uri) {
        val chat = chats.load(id) ?: error("Chat not found.")
        val text = buildString {
            append("# ${chat.summary.title}\n\n")
            chat.messages.forEach { m -> when (m) {
                is Message.User -> { append("## You\n\n${m.content}\n\n"); m.attachments.forEach { append("Attachment: ${it.name}\n\n") } }
                is Message.Assistant -> append("## Assistant\n\n${m.content}\n\n")
                else -> Unit
            } }
        }
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("Couldn't export chat.")
    }
}
