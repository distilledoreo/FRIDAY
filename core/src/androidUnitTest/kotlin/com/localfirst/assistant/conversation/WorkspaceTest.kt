package com.localfirst.assistant.conversation

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class WorkspaceTest {
    @Test fun savedKnowledgeSurvivesRestartAndProjectContextIsScoped() {
        val dir = Files.createTempDirectory("knowledge-test").toFile()
        try {
            val store = KnowledgeStore(dir.resolve("knowledge.json"))
            val memory = store.remember("I prefer concise answers.")
            store.remember("I prefer concise answers.")
            val project = AssistantProject("project1", "Travel", "Plan without a car.", "Hotel is near the station.")
            store.save(store.load().copy(projects = listOf(project)))
            val reopened = KnowledgeStore(dir.resolve("knowledge.json"))
            assertEquals(1, reopened.load().memories.size)
            assertTrue(reopened.context("project1").contains("Plan without a car."))
            assertFalse(reopened.context(null).contains("Hotel"))
            reopened.forget(memory.id)
            assertTrue(store.load().memories.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun projectAndWorkspaceFilesRoundTripAndOldBackupsStillLoad() {
        val summary = ConversationSummary("chat1", "Budget", 1, 2, "project1")
        val file = Attachment("a1", AttachmentKind.DOCUMENT, "budget.csv", "text/csv", path = "/private/a1.csv", remoteFileId = "abc123")
        val stored = StoredConversation(summary, listOf(Message.User("Calculate", listOf(file)), Message.Assistant("Done")))
        assertEquals(stored, decodeConversation(encodeConversation(stored)))
        val old = """{"version":1,"summary":{"id":"old","title":"Old","createdAt":1,"updatedAt":2},"messages":[{"role":"user","content":"hello"}]}"""
        assertNull(decodeConversation(old).summary.projectId)
    }
    @Test fun fullTextHistorySearchFindsTranscriptAndScopesProjects() {
        val dir = Files.createTempDirectory("history-test").toFile()
        try {
            val store = FileConversationStore(dir)
            store.save(StoredConversation(ConversationSummary("chat1", "Trip", 1, 2, "travel"), listOf(Message.User("Reserve a hotel near Union Station"))))
            store.save(StoredConversation(ConversationSummary("chat2", "Other", 1, 3), listOf(Message.Assistant("Union Station"))))
            assertEquals(2, store.search("Union Station").size)
            assertEquals("chat1", store.search("hotel", "travel").single().first.id)
            assertTrue(store.search("hotel", "work").isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun longHistoryTrimsCompleteTurnsAndPreservesToolPairing() {
        val engine = ConversationEngine()
        val old = listOf(Message.User("Old question")) + (1..5).flatMap { n ->
            listOf(Message.ToolCall("oldcall$n", "search", "{}"),
                Message.ToolResult("oldcall$n", "search", "x".repeat(72000), true))
        } + listOf(Message.Assistant("Old answer"))
        val latest = listOf(Message.User("Latest"), Message.ToolCall("newcall", "search", "{}"), Message.ToolResult("newcall", "search", "result", true))
        val all = old + latest
        val outbound = engine.outboundMessages("Be useful", all, "Current time")
        assertFalse(outbound.any { it is Message.ToolCall && it.id.startsWith("oldcall") })
        assertEquals(1, outbound.filterIsInstance<Message.ToolCall>().size)
        assertEquals(1, outbound.filterIsInstance<Message.ToolResult>().size)
        assertTrue((outbound[1] as Message.User).content.contains("Current time"))
        assertEquals(15, all.size)
        assertTrue((outbound.first() as Message.System).content.contains("search_history"))
    }

    /**
     * Tool results are sent truncated to 16,000 characters, so one large result must not push the
     * turn before it out of the budget: dropping it is what made the model forget the exchange it
     * had just had.
     */
    @Test fun aLargeToolResultKeepsTheTurnBeforeIt() {
        val engine = ConversationEngine()
        val first = listOf(Message.User("Where is the office?"), Message.ToolCall("c1", "fetch_page", "{}"),
            Message.ToolResult("c1", "fetch_page", "p".repeat(72000), true), Message.Assistant("It is on Main Street."))
        val outbound = engine.outboundMessages("Be useful", first + listOf(Message.User("And the parking?")), "Current time")
        assertTrue(outbound.any { it is Message.User && it.content.startsWith("Where is the office?") })
        assertTrue(outbound.any { it is Message.Assistant && it.content == "It is on Main Street." })
        assertTrue(outbound.any { it is Message.ToolCall && it.id == "c1" })
        assertFalse((outbound.first() as Message.System).content.contains("Earlier turns were omitted"))
    }

    /**
     * When the server bounds the context itself (ORCHID), FRIDAY sends everything: no turn is
     * dropped and no tool result is truncated, because ORCHID decides what the model sees.
     */
    @Test fun aManagedServerReceivesTheWholeHistoryUntouched() {
        val engine = ConversationEngine(contextManaged = true)
        val first = listOf(Message.User("Where is the office?"), Message.ToolCall("c1", "fetch_page", "{}"),
            Message.ToolResult("c1", "fetch_page", "p".repeat(72000), true), Message.Assistant("It is on Main Street."))
        val history = first + listOf(Message.User("And the parking?"))
        val outbound = engine.outboundMessages("Be useful", history, "Current time")

        // System prompt plus every stored message, in order and unmodified.
        assertEquals(history.size + 1, outbound.size)
        val sent = outbound.drop(1).toMutableList()
        val noted = sent.removeAt(sent.lastIndex) as Message.User
        assertEquals(history.dropLast(1), sent)
        // Nothing was truncated: the full 72,000-character result is on the wire.
        assertEquals(72000, (outbound[3] as Message.ToolResult).content.length)
        assertFalse((outbound.first() as Message.System).content.contains("Earlier turns were omitted"))
        // Per-turn context still rides the latest user message rather than the system prompt.
        assertTrue(noted.content.startsWith("And the parking?"))
        assertTrue(noted.content.contains("Current time"))
    }
}
