package com.localfirst.assistant.voice

import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.ui.ChatUiState
import com.localfirst.assistant.ui.PendingApproval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val chat = MutableStateFlow(ChatUiState())
    private val speaker = FakeSpeaker()
    private val heard = mutableListOf<ListenResult>()
    private val listens = mutableListOf<Int>()
    private var bargeIn = CompletableDeferred<Unit>()
    private var stopCalls = 0
    private val answers = mutableListOf<Boolean>()

    /** What the "model" does when a message is submitted. */
    private var reply: suspend (String) -> Unit = { text -> stream(text, "You said $text. That is a nice thing to say.") }

    private val controller = VoiceController(
        scope = scope,
        io = VoiceIo(
            input = object : SpeechInput {
                override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): ListenResult {
                    synchronized(listens) { listens += listens.size }
                    delay(20)
                    return synchronized(heard) { if (heard.isEmpty()) ListenResult.Silence else heard.removeAt(0) }
                }
            },
            speaker = speaker,
            bargeIn = object : BargeInListener {
                override suspend fun awaitSpeech() = bargeIn.await()
            },
            focus = object : VoiceAudioFocus {
                override fun acquire() = Unit
                override fun release() = Unit
            },
            ensureMicrophone = { true },
        ),
        chat = chat,
        submit = { text ->
            chat.update { it.copy(messages = it.messages + Message.User(text), busy = true) }
            scope.launch { reply(text) }
            true
        },
        stopTurn = {
            stopCalls++
            chat.update { it.copy(busy = false) }
        },
        answerApproval = { approved ->
            answers += approved
            chat.update { it.copy(pendingApproval = null) }
        },
        bargeInEnabled = { true },
    )

    @After
    fun tearDown() = scope.cancel()

    private suspend fun stream(userText: String, answer: String, finish: Boolean = true) {
        val words = answer.split(" ")
        for (i in words.indices) {
            delay(5)
            val partial = words.take(i + 1).joinToString(" ")
            chat.update { s -> s.copy(messages = s.messages.filterNot { it is Message.Assistant && s.messages.last() == it } + Message.Assistant(partial)) }
        }
        if (finish) chat.update { it.copy(busy = false) }
    }

    @Test
    fun speaksTheReplyBySentenceThenListensAgainAndPausesWhenQuiet() = runBlocking {
        heard += ListenResult.Heard("hello there")
        controller.start()
        withTimeout(5_000) { controller.state.first { it?.phase == VoicePhase.PAUSED } }

        assertEquals(listOf("You said hello there.", "That is a nice thing to say."), speaker.spoken)
        // One listen that heard something, then two quiet ones before pausing.
        assertEquals(3, listens.size)
        assertEquals("Tap to talk.", controller.state.value?.note)
    }

    @Test
    fun talkingOverTheReplyStopsItAndListens() = runBlocking {
        heard += ListenResult.Heard("tell me a long story")
        reply = { text ->
            // Keep "generating" until interrupted.
            stream(text, "Once upon a time there was a phone that could talk. It lived in a", finish = false)
        }
        speaker.holdUntilStopped = true
        controller.start()
        withTimeout(5_000) { controller.state.first { it?.phase == VoicePhase.SPEAKING } }
        heard += ListenResult.Heard("stop, what time is it")
        bargeIn.complete(Unit)
        withTimeout(5_000) { controller.state.first { it?.heard == "stop, what time is it" } }

        assertTrue(speaker.stops >= 1)
        assertEquals(1, stopCalls)
        controller.close()
    }

    @Test
    fun approvalsAreAskedAndAnsweredByVoice() = runBlocking {
        heard += ListenResult.Heard("text mom I'm on my way")
        reply = {
            delay(10)
            chat.update { it.copy(pendingApproval = PendingApproval("send_text", "Text Mom (mobile, +1 555 0100): “I'm on my way”")) }
            chat.first { it.pendingApproval == null }
            stream("", "Sent your text to Mom.")
        }
        heard += ListenResult.Heard("yes please")
        controller.start()
        withTimeout(5_000) { controller.state.first { it?.phase == VoicePhase.PAUSED } }

        assertEquals(listOf(true), answers)
        assertTrue(speaker.spoken.any { it.startsWith("Text Mom (mobile, +1 555 0100): “I'm on my way”") && it.endsWith("Should I go ahead?") })
        assertTrue(speaker.spoken.last().contains("Sent your text to Mom."))
    }

    @Test
    fun anUnclearAnswerIsAskedAgainAndNoMeansNo() = runBlocking {
        heard += ListenResult.Heard("call the dentist")
        reply = {
            chat.update { it.copy(pendingApproval = PendingApproval("place_call", "Call Dentist (work, +1 555 0199)")) }
            chat.first { it.pendingApproval == null }
            stream("", "Okay, I won't call.")
        }
        heard += ListenResult.Heard("hmm")
        heard += ListenResult.Heard("no don't")
        controller.start()
        withTimeout(5_000) { controller.state.first { it?.phase == VoicePhase.PAUSED } }

        assertEquals(listOf(false), answers)
        assertTrue(speaker.spoken.contains("Please say yes or no."))
    }

    private class FakeSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        var stops = 0
        var holdUntilStopped = false
        private var stopped = CompletableDeferred<Unit>()

        override suspend fun prepare() = true
        override fun enqueue(text: String) {
            synchronized(spoken) { spoken += text }
        }
        override suspend fun awaitIdle() {
            if (holdUntilStopped) stopped.await() else delay(5)
        }
        override fun stop() {
            stops++
            stopped.complete(Unit)
            stopped = CompletableDeferred()
        }
        override fun shutdown() = Unit
    }
}
