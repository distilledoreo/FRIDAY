package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.ModelResponse
import com.localfirst.assistant.tools.SourceLink
import com.localfirst.assistant.tools.ToolDefinition
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileConversationStoreTest {
    private val dir: File = Files.createTempDirectory("chats").toFile().apply { deleteOnExit() }

    @Test
    fun roundTripsEveryMessageKindExceptSystem() {
        val store = FileConversationStore(dir)
        val messages = listOf(
            Message.System("not stored"),
            Message.User("Find SearXNG"),
            Message.Assistant("Searching."),
            Message.ToolCall("c1", "web_search", """{"query":"SearXNG"}"""),
            Message.ToolResult(
                toolCallId = "c1",
                name = "web_search",
                content = "1. SearXNG",
                success = true,
                sources = listOf(SourceLink("SearXNG", "https://searxng.org", contentRead = true)),
            ),
            Message.Assistant("Here it is."),
        )
        store.save(StoredConversation(summary("a", updatedAt = 1), messages))

        val loaded = store.load("a")!!
        assertEquals(messages.drop(1), loaded.messages)
        assertEquals("Chat a", loaded.summary.title)
    }

    @Test
    fun listsNewestFirstAndSupportsRenameAndDelete() {
        val store = FileConversationStore(dir)
        store.save(StoredConversation(summary("old", updatedAt = 1), listOf(Message.User("1"))))
        store.save(StoredConversation(summary("new", updatedAt = 5), listOf(Message.User("2"))))
        assertEquals(listOf("new", "old"), store.list().map { it.id })

        store.rename("old", "Renamed")
        assertEquals("Renamed", store.load("old")!!.summary.title)
        assertEquals("Renamed", store.list().first { it.id == "old" }.title)

        store.delete("new")
        assertNull(store.load("new"))
        assertEquals(listOf("old"), store.list().map { it.id })
    }

    @Test
    fun rebuildsAMissingOrCorruptIndexFromTheChatFiles() {
        FileConversationStore(dir).save(StoredConversation(summary("x", updatedAt = 3), listOf(Message.User("hi"))))
        File(dir, "index.json").writeText("{ not json")

        assertEquals(listOf("x"), FileConversationStore(dir).list().map { it.id })
        File(dir, "index.json").delete()
        assertEquals(listOf("x"), FileConversationStore(dir).list().map { it.id })
    }

    @Test
    fun rejectsIdsThatCouldEscapeTheDirectory() {
        val store = FileConversationStore(dir)
        assertTrue(runCatching { store.load("../etc") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun titlesComeFromTheFirstMessageAndAreCleanedUp() {
        assertEquals("Plan a trip to Lisbon", ConversationTitles.fromFirstMessage("  Plan a trip to Lisbon\nmore"))
        assertEquals(ConversationTitles.NEW_CHAT, ConversationTitles.fromFirstMessage("   "))
        val long = ConversationTitles.fromFirstMessage("word ".repeat(30))
        assertTrue(long.length <= ConversationTitles.MAX_LENGTH + 1 && long.endsWith("…"))

        assertEquals("Lisbon Trip Ideas", ConversationTitles.sanitize("\"Lisbon Trip Ideas.\""))
        assertEquals("SearXNG setup", ConversationTitles.sanitize("Title: **SearXNG setup**\nextra"))
        assertNull(ConversationTitles.sanitize("  \n "))
    }

    @Test
    fun generatedTitlesFailQuietly() = runBlocking {
        val good = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>) =
                ModelResponse.TextResponse("“Volume Control Help”")
        }
        val broken = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse =
                throw IllegalStateException("down")
        }
        assertEquals("Volume Control Help", ConversationTitles.generate(good, "Turn it down", "Done."))
        assertNull(ConversationTitles.generate(broken, "Turn it down", "Done."))
    }

    private fun summary(id: String, updatedAt: Long) =
        ConversationSummary(id = id, title = "Chat $id", createdAt = 0, updatedAt = updatedAt)
}
