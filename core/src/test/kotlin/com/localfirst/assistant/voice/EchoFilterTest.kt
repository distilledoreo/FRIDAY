package com.localfirst.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoFilterTest {
    private val said = listOf("The weather in Dallas is sunny today.", "It’ll reach about 85 degrees this afternoon.")

    @Test
    fun recognizesItsOwnWordsEvenWhenMisheard() {
        assertTrue(EchoFilter.isEcho("it'll reach about 85 degrees", said))
        assertTrue(EchoFilter.isEcho("weather in Dallas is funny today", said))
        assertTrue(EchoFilter.isEcho("", said))
    }

    @Test
    fun letsTheUserInterrupt() {
        assertFalse(EchoFilter.isEcho("stop", said))
        assertFalse(EchoFilter.isEcho("wait, what about tomorrow in Austin", said))
        assertFalse(EchoFilter.isEcho("is it sunny", emptyList()))
    }
}
