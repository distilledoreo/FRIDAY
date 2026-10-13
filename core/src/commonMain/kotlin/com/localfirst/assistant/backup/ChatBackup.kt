package com.localfirst.assistant.backup

import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.json.JsonCodec

/**
 * The portable backup both clients export and restore: `chats/<id>.json` in the shared conversation format,
 * `knowledge.json` (projects and older saved memories) and `assets/<name>` for attachment images. Server
 * settings and credentials are never included. Restored chats become new copies, so nothing is overwritten.
 */
object ChatBackup {
    const val MAX_TOTAL = 100 * 1024 * 1024
    const val MAX_ENTRY = 25 * 1024 * 1024
    const val MAX_CHAT = 8 * 1024 * 1024
    private val entryName = Regex("(chats/[A-Za-z0-9-]+\\.json|assets/[A-Za-z0-9._-]+|knowledge\\.json)")
    private val assetName = Regex("assets/[A-Za-z0-9._-]+")

    class Contents(val chats: List<StoredConversation>, val knowledge: Knowledge, val assets: Map<String, ByteArray>)

    /** [chats] carry attachment paths already rewritten to keys of [assets] (or null when the file isn't included). */
    fun write(chats: List<StoredConversation>, knowledge: Knowledge, assets: Map<String, ByteArray>): ByteArray {
        require(assets.keys.all { assetName.matches(it) }) { "Invalid attachment name." }
        require(assets.values.sumOf { it.size.toLong() } < MAX_TOTAL) { "Attachments exceed the 100 MB backup limit." }
        val entries = chats.map { ZipEntry("chats/${it.summary.id}.json", encodeConversation(it).encodeToByteArray()) } +
            ZipEntry("knowledge.json", JsonCodec.json.encodeToString(Knowledge.serializer(), knowledge).encodeToByteArray()) +
            assets.map { (name, bytes) -> ZipEntry(name, bytes) }
        return Zip.write(entries)
    }

    /** Validates and decodes a backup. Chats keep their saved ids; [restored] gives them new ones. */
    suspend fun read(zip: ByteArray, inflate: Inflate): Contents {
        require(zip.size <= MAX_TOTAL + 16 * 1024 * 1024) { "Backup exceeds size limits." }
        val entries = Zip.read(zip, inflate, maxEntries = 3000, maxEntryBytes = MAX_ENTRY)
        require(entries.sumOf { it.data.size.toLong() } <= MAX_TOTAL) { "Backup exceeds size limits." }
        require(entries.all { entryName.matches(it.name) }) { "Unknown backup entry." }
        require(entries.map { it.name }.toSet().size == entries.size) { "Duplicate backup entry." }
        val assets = entries.filter { it.name.startsWith("assets/") }.associate { it.name to it.data }
        val chats = entries.filter { it.name.startsWith("chats/") }.map { entry ->
            require(entry.data.size <= MAX_CHAT) { "A backed-up chat exceeds 8 MB." }
            val chat = decodeConversation(entry.data.decodeToString())
            // Attachment paths must point inside the backup, or be dropped.
            chat.copy(messages = chat.messages.map { message ->
                if (message !is Message.User) message else message.copy(attachments = message.attachments.map { a ->
                    fun keep(path: String?) = path?.takeIf { it.isNotBlank() && it != "null" }?.also { require(assetName.matches(it)) { "Invalid attachment path." } }?.takeIf { it in assets }
                    a.copy(path = keep(a.path), pageImages = a.pageImages.mapNotNull { keep(it) })
                })
            })
        }
        val knowledge = entries.firstOrNull { it.name == "knowledge.json" }?.let { JsonCodec.json.decodeFromString(Knowledge.serializer(), it.data.decodeToString()) } ?: Knowledge()
        return Contents(chats, knowledge, assets)
    }

    /**
     * Merges restored projects and memories: an incoming project whose id is taken by a different project gets a
     * new id, and its chats follow it. Returns the merged knowledge and the project ids that changed.
     */
    fun merge(current: Knowledge, incoming: Knowledge, newId: () -> String): Pair<Knowledge, Map<String, String>> {
        val renamed = mutableMapOf<String, String>()
        val projects = incoming.projects.map { p ->
            if (current.projects.any { it.id == p.id && it != p }) newId().also { renamed[p.id] = it }.let { p.copy(id = it) } else p
        }
        val memories = incoming.memories.map { m -> if (current.memories.any { it.id == m.id && it.text != m.text }) m.copy(id = newId()) else m }
        return Knowledge((current.memories + memories).distinctBy { it.text }, (current.projects + projects).distinctBy { it.id }) to renamed
    }

    /** Restored chats as new copies with fresh ids, following any project that was renamed. */
    fun restored(chats: List<StoredConversation>, projects: Map<String, String>, newId: () -> String): List<StoredConversation> =
        chats.map { c -> c.copy(summary = c.summary.copy(id = newId(), projectId = projects[c.summary.projectId] ?: c.summary.projectId)) }
}
