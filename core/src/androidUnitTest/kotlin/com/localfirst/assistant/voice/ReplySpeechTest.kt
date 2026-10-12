package com.localfirst.assistant.voice

import com.localfirst.assistant.conversation.Message
import org.junit.Assert.*
import org.junit.Test

class ReplySpeechTest {
    @Test fun closesEachCommentBeforeToolsAndSpeaksEveryBubbleOnlyOnce() {
        val speech=ReplySpeech()
        val start=listOf(Message.User("Check owned fixtures"),Message.Assistant("I'll check that"),Message.ToolCall("a","web_search","{}"))
        assertEquals(listOf("I'll check that"),speech.update(start,busy=true))
        assertTrue(speech.update(start,busy=true).isEmpty())
        val next=start+Message.ToolResult("a","web_search","Owned result",true)+Message.Assistant("Now the second check")+Message.ToolCall("b","fetch_page","{}")
        assertEquals(listOf("Now the second check"),speech.update(next,busy=true))
        val final=next+Message.ToolResult("b","fetch_page","Owned result",true)+Message.Assistant("Both checks finished.")
        assertEquals(listOf("Both checks finished."),speech.update(final,busy=false))
        assertTrue(speech.update(final,busy=false).isEmpty())
    }
    @Test fun aConflatedFinalSnapshotIncludesCommentsWithoutReadingToolResultsOrOldTurns() {
        val messages=listOf(Message.User("Old request"),Message.Assistant("Never replay me."),Message.User("New request"),Message.Assistant("Checking"),Message.ToolCall("a","owned","{}"),Message.ToolResult("a","owned","PRIVATE RAW TOOL OUTPUT",true),Message.Assistant("Finished"))
        assertEquals(listOf("Checking","Finished"),ReplySpeech().update(messages,busy=false))
    }
    @Test fun cuesWaitForQuietAreCappedAndUseTheCooldownAfterRealAudio() {
        val feedback=VoiceWaitFeedback(0)
        assertNull(feedback.next(5_999))
        assertEquals("Give me a moment.",feedback.next(6_000))
        feedback.audioFinished(7_000)
        assertNull(feedback.next(35_999))
        assertEquals("I’m still working on that.",feedback.next(36_000))
        assertNull(feedback.next(120_000))
        val afterSpeech=VoiceWaitFeedback(0)
        afterSpeech.audioFinished(5_000)
        assertNull(afterSpeech.next(10_999))
        assertNotNull(afterSpeech.next(11_000))
    }
}
