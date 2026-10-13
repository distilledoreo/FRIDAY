package com.localfirst.assistant.backup

import com.localfirst.assistant.conversation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Backups written by Android's ZipOutputStream (deflated, sizes after the data) open with the shared reader, and the reverse. */
class AndroidBackupFormatTest {
    private val inflate = Inflate { data, size ->
        Inflater(true).run { setInput(data); ByteArray(size).also { out -> var n = 0; while (n < size && !finished()) n += inflate(out, n, size - n); end() } }
    }
    private val chat = StoredConversation(ConversationSummary("c0ffee00-0000-4000-8000-000000000001", "Trip “é”", 1, 2, "p"),
        listOf(Message.User("Look", listOf(Attachment("a", AttachmentKind.IMAGE, "x.jpg", "image/jpeg", path = "assets/x.jpg"))), Message.Assistant("Nice")))

    @Test fun sharedReaderOpensAnAndroidBackup() = runBlocking {
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            fun entry(name: String, data: ByteArray) { zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(data); zip.closeEntry() }
            entry("chats/${chat.summary.id}.json", encodeConversation(chat).toByteArray())
            entry("knowledge.json", """{"projects":[{"id":"p","name":"Trips"}]}""".toByteArray())
            entry("assets/x.jpg", ByteArray(5000) { (it % 7).toByte() })
        } }.toByteArray()
        val contents = ChatBackup.read(bytes, inflate)
        assertEquals(chat, contents.chats.single())
        assertEquals("Trips", contents.knowledge.projects.single().name)
        assertEquals(5000, contents.assets.getValue("assets/x.jpg").size)
    }

    @Test fun androidReadsASharedBackup() {
        val zip = ChatBackup.write(listOf(chat), Knowledge(), mapOf("assets/x.jpg" to byteArrayOf(9, 8, 7)))
        val names = mutableMapOf<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { input -> while (true) { val e = input.nextEntry ?: break; names[e.name] = input.readBytes() } }
        assertEquals(setOf("chats/${chat.summary.id}.json", "knowledge.json", "assets/x.jpg"), names.keys)
        assertEquals(chat, decodeConversation(names.getValue("chats/${chat.summary.id}.json").decodeToString()))
        assertEquals(listOf<Byte>(9, 8, 7), names.getValue("assets/x.jpg").toList())
    }
}
