package com.localfirst.assistant.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortableSpeechTest {
    @Test fun liveVoiceUsesOneSavedIdentityAndNeverAddsItsPromptToPrivateChats() {
        val first = LiveVoiceConversation.empty(10)
        val second = LiveVoiceConversation.empty(20)
        assertEquals(first.summary.id, second.summary.id)
        assertEquals("Live voice", first.summary.title)
        assertTrue(LiveVoiceConversation.instructions(first.summary.id).contains("Start with the answer"))
        assertEquals("", LiveVoiceConversation.instructions(first.summary.id, incognito = true))
        assertEquals("", LiveVoiceConversation.instructions("ordinary-chat"))
    }

    @Test fun voiceCanSpeakACompleteClauseBeforeTheSentenceFinishes() {
        val voice = SpeechChunker(earlyClauses = true)
        val first = "Write down the three things you need to finish today, then pick one."
        assertEquals(listOf("Write down the three things you need to finish today,"), voice.update(first, false))
        assertEquals(listOf("then pick one."), voice.update(first, true))
        assertEquals(emptyList(), SpeechChunker().update(first, false))
    }

    @Test fun earlyVoiceDoesNotBreakAdjectivesOrDuplicateAlreadySpokenWords() {
        val chunker = SpeechChunker(earlyClauses = true)
        val prefix = "Try the Pomodoro technique, breaking work into short, timed intervals,"
        assertEquals(emptyList(), chunker.update(prefix + " wh", false))
        assertEquals(listOf(prefix), chunker.update(prefix + " which helps focus.", false))
        assertEquals(listOf("which helps focus."), chunker.update(prefix + " which helps focus.", true))
    }

    @Test fun markdownCanBeSpokenOnEveryPlatform() {
        assertEquals("Hello from the fixture.", SpeechText.fromMarkdown("Hello from the fixture."))
        assertEquals("Read the docs. The code is on screen.", SpeechText.fromMarkdown("Read **[the docs](https://example.com)**.\n```kotlin\nval x = 1\n```"))
    }
}
