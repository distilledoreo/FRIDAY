package com.localfirst.assistant.presentation

import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.tools.SourceLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    @Test
    fun groupsToolCallsWithTheirResultsAndHidesBlankAssistantText() {
        val messages = listOf(
            Message.System("hidden"),
            Message.User("Search and turn it up"),
            Message.Assistant(""),
            Message.ToolCall("s", "web_search", """{"query":"SearXNG"}"""),
            Message.ToolCall("v", "set_media_volume", """{"level":70}"""),
            Message.ToolResult("s", "web_search", "1. SearXNG", true, listOf(SourceLink("SearXNG", "https://searxng.org"))),
            Message.ToolResult("v", "set_media_volume", "Android refused", false),
            Message.Assistant("Here you go."),
        )
        val items = Transcript.items(messages, busy = false)

        assertEquals(3, items.size)
        assertEquals(TranscriptItem.User(1, "Search and turn it up"), items[0])
        val activity = items[1] as TranscriptItem.ToolActivity
        assertEquals(listOf("Searched the web for “SearXNG”", "Couldn't set media volume to 70%"), activity.steps.map { it.label })
        assertEquals(listOf(ToolStepState.DONE, ToolStepState.FAILED), activity.steps.map { it.state })
        assertEquals("https://searxng.org", activity.steps[0].sources.single().url)
        assertEquals("Android refused", activity.steps[1].detail)
        val answer = items[2] as TranscriptItem.Assistant
        assertTrue(answer.isLatest)
        assertFalse(answer.streaming)
    }

    @Test
    fun showsRunningToolsAndAThinkingIndicatorWhileBusy() {
        val waiting = Transcript.items(listOf(Message.User("Hi")), busy = true)
        assertEquals(TranscriptItem.Thinking, waiting.last())

        val running = Transcript.items(
            listOf(Message.User("Find it"), Message.ToolCall("s", "web_search", """{"query":"x"}""")),
            busy = true,
        )
        val step = (running.last() as TranscriptItem.ToolActivity).steps.single()
        assertEquals(ToolStepState.RUNNING, step.state)
        assertEquals("Searching the web for “x”…", step.label)

        val afterTool = Transcript.items(
            listOf(
                Message.User("Find it"),
                Message.ToolCall("s", "web_search", "{}"),
                Message.ToolResult("s", "web_search", "ok", true),
            ),
            busy = true,
        )
        assertEquals(TranscriptItem.Thinking, afterTool.last())

        val streaming = Transcript.items(listOf(Message.User("Hi"), Message.Assistant("Hel")), busy = true)
        assertTrue((streaming.last() as TranscriptItem.Assistant).streaming)
        assertEquals(2, streaming.size)
    }

    @Test
    fun onlyTheLatestAnswerCanBeRegenerated() {
        val items = Transcript.items(
            listOf(Message.User("1"), Message.Assistant("A"), Message.User("2"), Message.Assistant("B")),
            busy = false,
        ).filterIsInstance<TranscriptItem.Assistant>()
        assertEquals(listOf(false, true), items.map { it.isLatest })

        val pendingUser = Transcript.items(listOf(Message.User("1"), Message.Assistant("A"), Message.User("2")), busy = false)
        assertFalse((pendingUser[1] as TranscriptItem.Assistant).isLatest)
    }

    @Test
    fun repeatedToolCallIdsAcrossRoundsGetTheirOwnResultsAndKeys() {
        val messages = listOf(
            Message.User("Search twice"),
            Message.ToolCall("call_0", "web_search", """{"query":"first"}"""),
            Message.ToolResult("call_0", "web_search", "one", true),
            Message.ToolCall("call_0", "web_search", """{"query":"second"}"""),
            Message.ToolResult("call_0", "web_search", "Search service down", false),
            Message.Assistant("Done."),
        )
        val items = Transcript.items(messages, busy = false)
        // Calls separated only by results merge into one card.
        val steps = items.filterIsInstance<TranscriptItem.ToolActivity>().flatMap { it.steps }
        assertEquals(listOf(ToolStepState.DONE, ToolStepState.FAILED), steps.map { it.state })
        assertEquals(items.size, items.map { it.key }.toSet().size)

        val separated = Transcript.items(
            listOf(
                Message.User("Q"),
                Message.ToolCall("call_0", "web_search", "{}"),
                Message.ToolResult("call_0", "web_search", "ok", true),
                Message.Assistant("Let me look again."),
                Message.ToolCall("call_0", "web_search", "{}"),
            ),
            busy = true,
        )
        val cards = separated.filterIsInstance<TranscriptItem.ToolActivity>()
        assertEquals(2, cards.size)
        assertEquals(ToolStepState.RUNNING, cards[1].steps.single().state)
        assertEquals(separated.size, separated.map { it.key }.toSet().size)
    }

    @Test
    fun unknownToolsAndBadArgumentsStillGetLabels() {
        val step = Transcript.step(Message.ToolCall("x", "open_app", "not json"), null)
        assertEquals("Running open_app…", step.label)
        val search = Transcript.step(Message.ToolCall("y", "web_search", "{}"), Message.ToolResult("y", "web_search", "ok", true))
        assertEquals("Searched the web", search.label)
    }

    @Test
    fun groupsConversationsByAge() {
        val day = 24L * 60 * 60 * 1000
        val today = 100 * day
        assertEquals(ConversationGroups.TODAY, ConversationGroups.label(today + 5, today))
        assertEquals(ConversationGroups.YESTERDAY, ConversationGroups.label(today - 1, today))
        assertEquals(ConversationGroups.LAST_WEEK, ConversationGroups.label(today - 3 * day, today))
        assertEquals(ConversationGroups.LAST_MONTH, ConversationGroups.label(today - 20 * day, today))
        assertEquals(ConversationGroups.OLDER, ConversationGroups.label(today - 90 * day, today))
    }
}
