package com.localfirst.assistant.presentation

import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.json.JsonCodec
import org.junit.Assert.*
import org.junit.Test

class SyncPreviewTest {
    @Test fun previewsVisibleConversationWithoutToolPayloads() {
        val chat = StoredConversation(ConversationSummary("a", "Budget", 0, 1), listOf(
            Message.User("Check the budget"), Message.Assistant(""),
            Message.ToolCall("t", "analyze", "private tool details"), Message.Assistant("It is balanced.")))
        val preview = SyncPreview.describe(encodeConversation(chat), false)
        assertEquals("Budget\n2 messages\nIt is balanced.", preview)
    }
    @Test fun boundsLongConversationPreviews() {
        val chat = StoredConversation(ConversationSummary("a", "Long", 0, 1), listOf(Message.User("x".repeat(10000))))
        assertEquals(240, SyncPreview.describe(encodeConversation(chat), false).substringAfterLast('\n').length)
    }
    @Test fun summarizesKnowledgeAndHandlesDeletedOrInvalidCopies() {
        val k = Knowledge(listOf(SavedMemory("m", "Use Celsius", 0)), listOf(AssistantProject("p", "Garden")))
        val preview = SyncPreview.describe(JsonCodec.json.encodeToString(Knowledge.serializer(), k), true)
        assertTrue(preview.contains("1 memories · 1 projects")); assertTrue(preview.contains("Use Celsius")); assertTrue(preview.contains("Garden"))
        assertEquals("Deleted or not saved on this device", SyncPreview.describe(null, false))
        assertEquals("Preview unavailable", SyncPreview.describe("invalid", false))
    }
}
