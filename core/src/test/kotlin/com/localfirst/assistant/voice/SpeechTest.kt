package com.localfirst.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    @Test
    fun markdownBecomesPlainSpeech() {
        assertEquals(
            "Top picks: Fast and cheap. See SearXNG docs for more.",
            SpeechText.fromMarkdown("## Top picks:\n- **Fast** and *cheap*.\nSee [SearXNG docs](https://docs.searxng.org) for more."),
        )
        assertEquals("Run it like this: The code is on screen. Done.", SpeechText.fromMarkdown("Run it like this:\n```bash\nls -la\n```\nDone."))
        assertEquals("Use the ls command.", SpeechText.fromMarkdown("Use the `ls` command."))
        assertEquals("Visit a link today.", SpeechText.fromMarkdown("Visit https://example.com/x today."))
        assertEquals("Name, Age. Ana, 30.", SpeechText.fromMarkdown("| Name | Age |\n|---|---|\n| Ana | 30 |"))
    }

    @Test
    fun streamingTextIsSpokenSentenceBySentence() {
        val chunker = SpeechChunker()
        assertEquals(emptyList<String>(), chunker.update("The weather today is", final = false))
        assertEquals(listOf("The weather today is sunny and warm."), chunker.update("The weather today is sunny and warm. Tomorrow", final = false))
        assertEquals(emptyList<String>(), chunker.update("The weather today is sunny and warm. Tomorrow it rains", final = false))
        assertEquals(
            listOf("Tomorrow it rains, so bring an umbrella."),
            chunker.update("The weather today is sunny and warm. Tomorrow it rains, so bring an umbrella.", final = true),
        )
    }

    @Test
    fun shortSentencesAreMergedAndDecimalsDontSplit() {
        val chunker = SpeechChunker()
        val text = "Done. Your alarm is set for 6:45 AM tomorrow. It costs 3.50 dollars. "
        assertEquals(listOf("Done. Your alarm is set for 6:45 AM tomorrow."), chunker.update(text, final = false))
        // A short last sentence waits for more text, then goes out when the reply ends.
        assertEquals(listOf("It costs 3.50 dollars."), chunker.update(text, final = true))
    }

    @Test
    fun codeBlocksWaitUntilTheyClose() {
        val chunker = SpeechChunker(minChars = 1)
        assertEquals(listOf("Here you go:"), chunker.update("Here you go:\n```kotlin\nval x = 1.\nval y", final = false))
        assertEquals(emptyList<String>(), chunker.update("Here you go:\n```kotlin\nval x = 1.\nval y = 2", final = false))
        assertEquals(
            listOf("The code is on screen.", "That's it."),
            chunker.update("Here you go:\n```kotlin\nval x = 1.\nval y = 2\n```\nThat's it.", final = true),
        )
    }

    @Test
    fun chunksWithNothingToSayAreDropped() {
        val chunker = SpeechChunker(minChars = 1)
        assertEquals(listOf("Two options:", "First one."), chunker.update("Two options:\n-\n---\nFirst one.", final = true))
    }

    @Test
    fun spokenApprovalsFavorNo() {
        assertEquals(true, VoiceReplies.approval("Yes please"))
        assertEquals(true, VoiceReplies.approval("Yeah, go ahead."))
        assertEquals(true, VoiceReplies.approval("OK send it"))
        assertEquals(false, VoiceReplies.approval("No"))
        assertEquals(false, VoiceReplies.approval("Yes, actually no, don't"))
        assertEquals(false, VoiceReplies.approval("Wait"))
        assertNull(VoiceReplies.approval("What's the weather"))
        assertNull(VoiceReplies.approval("Yesterday"))
    }

    @Test
    fun speechOnsetNeedsSustainedLoudnessAboveTheFloor() {
        val detector = SpeechOnsetDetector(framesNeeded = 5)
        repeat(50) { assertFalse(detector.accept(-60.0)) }
        // A short click doesn't count.
        repeat(3) { assertFalse(detector.accept(-25.0)) }
        assertFalse(detector.accept(-60.0))
        // Sustained speech does.
        val fired = (1..5).map { detector.accept(-25.0) }
        assertEquals(listOf(false, false, false, false, true), fired)
    }

    @Test
    fun echoLevelsOnTheSpeakerDontTriggerButRealSpeechDoes() {
        val speaker = SpeechOnsetDetector.forSpeaker()
        // Residual echo of the assistant's own voice around -40 dBFS.
        repeat(200) { i -> assertFalse(speaker.accept(if (i % 3 == 0) -38.0 else -44.0)) }
        assertTrue((1..12).map { speaker.accept(-14.0) }.last())
    }
}
