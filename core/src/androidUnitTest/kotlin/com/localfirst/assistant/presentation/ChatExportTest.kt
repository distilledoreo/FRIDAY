package com.localfirst.assistant.presentation

import com.localfirst.assistant.conversation.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportTest {
    @Test
    fun exportsReadableMarkdown() {
        val text = ChatExport.markdown("Trip", listOf(
            Message.User("Plan my weekend"),
            Message.Assistant("Here you go.\n\n```friday\n{\"type\":\"steps\",\"items\":[\"Book train\",\"Pack\"]}\n```"),
            Message.ToolCall("c", "web_search", "{}"),
            Message.ToolResult("c", "web_search", "ok", true),
            Message.Assistant(""),
        ))
        assertEquals("# Trip\n\n## You\nPlan my weekend\n\n## FRIDAY\nHere you go.\n\n1. Book train\n2. Pack\n", text)
    }

    @Test
    fun skipsEmptyAnswersAndNotesAttachments() {
        val text = ChatExport.markdown("Files", listOf(
            Message.User("Read this", attachments = emptyList()),
        ))
        assertTrue(text.startsWith("# Files\n\n## You\nRead this"))
    }
}
