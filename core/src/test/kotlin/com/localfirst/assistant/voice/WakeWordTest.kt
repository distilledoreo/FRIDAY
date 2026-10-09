package com.localfirst.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test
    fun matchesHeyFridayInTranscript() {
        assertTrue(WakeWord.isWakeWord("hey friday"))
        assertTrue(WakeWord.isWakeWord("Hey FRIDAY, what time is it"))
        assertTrue(WakeWord.isWakeWord("well hey friday listen"))
    }

    @Test
    fun rejectsLookalikes() {
        assertFalse(WakeWord.isWakeWord("hey"))
        assertFalse(WakeWord.isWakeWord("friday"))
        assertFalse(WakeWord.isWakeWord("hey there friday is great"))
        assertFalse(WakeWord.isWakeWord(""))
        assertFalse(WakeWord.isWakeWord("heyday friday"))
    }
}
