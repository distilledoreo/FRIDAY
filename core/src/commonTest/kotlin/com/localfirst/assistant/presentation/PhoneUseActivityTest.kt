package com.localfirst.assistant.presentation

import com.localfirst.assistant.conversation.Message
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class PhoneUseActivityTest {
    private fun call(id: String, name: String, args: String = """{"app":"Gmail"}""") = Message.ToolCall(id, name, args)
    private fun result(id: String, name: String, content: String, success: Boolean = true, image: String? = null) =
        Message.ToolResult(id, name, content, success, image = image)

    @Test
    fun aRunOfPhoneUseStepsIsItsOwnCardWithResultLabels() {
        val messages = listOf(
            Message.User("Archive Alice's email"),
            call("w", "web_search", """{"query":"x"}"""), result("w", "web_search", "results"),
            call("a", "get_app_state"), result("a", "get_app_state", "App: Gmail (com.google.android.gm)\nWindow: Inbox\n\n0 window Inbox", image = "/s1.jpg"),
            call("b", "perform_secondary_action", """{"app":"Gmail","element_index":4,"action":"Archive"}"""),
            result("b", "perform_secondary_action", "Performed “Archive” on group “Alice”.\n\nApp: Gmail", image = "/s2.jpg"),
            Message.Assistant("Archived."),
        )
        val items = Transcript.items(messages, busy = false)
        val activities = items.filterIsInstance<TranscriptItem.ToolActivity>()
        assertEquals(2, activities.size)
        assertEquals(listOf("web_search"), activities[0].steps.map { it.name })
        val phone = activities[1].steps
        assertEquals(listOf("Looked at Gmail · Inbox", "Performed “Archive” on group “Alice”"), phone.map { it.label })
        assertEquals(listOf("/s1.jpg", "/s2.jpg"), phone.map { it.image })
        assertEquals("Gmail", phone.first().app)
    }

    @Test
    fun theSummaryFollowsCodexComputerActivity() {
        fun step(i: Int, state: ToolStepState = ToolStepState.DONE, image: String? = null) =
            ToolStep("c$i", "click", state, "Step $i", image = image, app = "Gmail")
        val done = PhoneUseActivity.summarize((0 until 6).map { step(it, if (it == 1) ToolStepState.FAILED else ToolStepState.DONE, if (it == 3) "/s.jpg" else null) })
        assertFalse(done.active)
        assertEquals("Used Gmail · 6 actions · 1 failed", done.header)
        // The failure, then the screenshot, shown in their original order; the rest hidden.
        assertEquals(listOf(1, 3), done.preview)
        assertEquals(4, done.hidden)
        assertEquals("/s.jpg", done.latestImage)

        val running = PhoneUseActivity.summarize(listOf(step(0), step(1, ToolStepState.RUNNING)))
        assertTrue(running.active)
        assertEquals("Using Gmail · 2 actions", running.header)
        assertEquals(listOf(1), running.preview)
        assertEquals(0, running.hidden)

        val few = PhoneUseActivity.summarize(listOf(step(0), step(1)))
        assertEquals(listOf(0, 1), few.preview)
        assertEquals("Used Gmail · 2 actions", few.header)
    }

    @Test
    fun failedPhoneStepsShowTheirReasonInTheLabel() {
        val messages = listOf(
            Message.User("Tap compose"),
            call("a", "click", """{"app":"Gmail","element_index":2}"""),
            result("a", "click", "Call get_app_state for Gmail first.", success = false),
        )
        val step = Transcript.items(messages, busy = false).filterIsInstance<TranscriptItem.ToolActivity>().single().steps.single()
        assertEquals("Failed: Call get_app_state for Gmail first.", step.label)
        assertEquals(null, step.detail)
    }
}
