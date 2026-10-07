package com.localfirst.assistant

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPromptsTest {
    private val zone = ZoneId.of("America/New_York")

    @Test
    fun theSystemPromptOnlyChangesWithTheDate() {
        val morning = ZonedDateTime.of(2026, 10, 7, 9, 0, 0, 0, zone)
        val night = morning.withHour(23).withMinute(59)
        assertEquals(AssistantPrompts.system(morning), AssistantPrompts.system(night))
        assertNotEquals(AssistantPrompts.system(morning), AssistantPrompts.system(morning.plusDays(1)))
        assertTrue(AssistantPrompts.system(morning).contains("Today is Wednesday, October 7, 2026 (time zone America/New_York)"))
        assertFalse(AssistantPrompts.system(morning).contains("9:00"))
    }

    @Test
    fun theTimeTravelsAsANote() {
        assertEquals("[Sent at 11:54 PM]", AssistantPrompts.timeNote(ZonedDateTime.of(2026, 10, 7, 23, 54, 0, 0, zone)))
    }
}
