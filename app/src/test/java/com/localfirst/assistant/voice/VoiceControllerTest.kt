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
    private val resubmitted = mutableListOf<String>()
    private var deferSnapshot = false

    /** What the "model" does when a message is submitted. */
    private var reply: suspend (String) -> Unit = { text -> stream(text, "You said $text. That is a nice thing to say.") }

    private val controller by lazy { makeController() }
    private fun makeController(quietMillis:Long=6_000,intervalMillis:Long=30_000) = VoiceController(
        scope = scope,
        selectIo = { selection },
        chat = chat,
        submit = { text ->
            chat.update { it.copy(messages = if(deferSnapshot)it.messages else it.messages + Message.User(text), busy = true, turnMessagesReady = !deferSnapshot) }
            scope.launch { reply(text) }
            true
        },
        resubmit = { text ->
            resubmitted += text
            chat.update { s ->
                val lastUser = s.messages.indexOfLast { it is Message.User }
                s.copy(messages = s.messages.take(lastUser) + Message.User(text), busy = true, turnMessagesReady = true)
            }
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
        feedbackQuietMillis=quietMillis,
        feedbackIntervalMillis=intervalMillis,
    )

    private val selection get() = VoiceSelection(io)

    private val io by lazy {
        VoiceIo(
            input = object : SpeechInput {
                override suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit): ListenResult {
                    synchronized(listens) { listens += listens.size }
                    delay(20)
                    return synchronized(heard) { if (heard.isEmpty()) ListenResult.Silence else heard.removeAt(0) }
                }
            },
            speaker = speaker,
            bargeIn = object : BargeInListener {
                override suspend fun awaitSpeech() {
                    bargeIn.await()
                    // One onset per utterance, like the real mic.
                    bargeIn = CompletableDeferred()
                }
            },
            focus = object : VoiceAudioFocus {
                override fun acquire() = Unit
                override fun release() = Unit
            },
            ensureMicrophone = { true },
        )
    }

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
    fun continuingAfterAPauseMergesWithTheLastMessage() = runBlocking {
        heard += ListenResult.Heard("set a timer for")
        var first = true
        reply = { text ->
            if (first) {
                first = false
                // Still thinking when the user carries on.
                delay(10_000)
            } else {
                stream(text, "Timer set for ten minutes.")
            }
        }
        controller.start()
        withTimeout(5_000) { controller.state.first { it?.phase == VoicePhase.THINKING } }
        heard += ListenResult.Heard("ten minutes")
        bargeIn.complete(Unit)
        withTimeout(5_000) { controller.state.first { it?.phase == VoicePhase.PAUSED } }

        assertEquals(listOf("set a timer for ten minutes"), resubmitted)
        assertEquals(1, chat.value.messages.count { it is Message.User })
        assertTrue(speaker.spoken.contains("Timer set for ten minutes."))
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

    private fun spoken() = synchronized(speaker.spoken) { speaker.spoken.toList() }
    private suspend fun awaitSpoken(text:String) {
        withTimeout(3_000) { while(text !in spoken())delay(5) }
    }

    @Test fun neverReplaysThePreviousTurnWhileTheNewMessageIsBeingPrepared() = runBlocking {
        chat.value=ChatUiState(messages=listOf(Message.User("Old question"),Message.Assistant("Here is the previous answer. Do not repeat it.")),turnMessagesReady=true)
        deferSnapshot=true
        val prepared=CompletableDeferred<Unit>()
        reply={text->
            prepared.await()
            chat.update { it.copy(messages=it.messages+Message.User(text),turnMessagesReady=true) }
            stream(text,"This is the current answer.")
        }
        heard+=ListenResult.Heard("New question")
        controller.start()
        withTimeout(3_000) { controller.state.first { it?.phase==VoicePhase.THINKING } }
        delay(100)
        assertTrue(spoken().isEmpty())
        prepared.complete(Unit)
        withTimeout(3_000) { controller.state.first { it?.phase==VoicePhase.PAUSED } }
        assertEquals(listOf("This is the current answer."),spoken())
    }

    @Test fun readsShortCommentsAtEveryToolBoundaryBeforeTheToolFinishes() = runBlocking {
        val firstTool=CompletableDeferred<Unit>()
        val secondTool=CompletableDeferred<Unit>()
        reply={text->
            val first=listOf(Message.User(text),Message.Assistant("I'll check that"),Message.ToolCall("a","web_search","{}"))
            chat.update { it.copy(messages=first) }
            firstTool.await()
            val second=first+Message.ToolResult("a","web_search","Owned result",true)+Message.Assistant("Now the second check")+Message.ToolCall("b","fetch_page","{}")
            chat.update { it.copy(messages=second) }
            secondTool.await()
            chat.update { it.copy(messages=second+Message.ToolResult("b","fetch_page","Owned result",true)+Message.Assistant("Both checks finished."),busy=false) }
        }
        heard+=ListenResult.Heard("Check the owned fixtures")
        controller.start()
        awaitSpoken("I'll check that")
        assertTrue(chat.value.busy)
        firstTool.complete(Unit)
        awaitSpoken("Now the second check")
        assertTrue(chat.value.busy)
        secondTool.complete(Unit)
        withTimeout(3_000) { controller.state.first { it?.phase==VoicePhase.PAUSED } }
        assertEquals(listOf("I'll check that","Now the second check","Both checks finished."),spoken())
    }

    @Test fun aLongWaitGetsOnlyTwoVoiceCuesWithoutAddingFakeChatMessages() = runBlocking {
        val finish=CompletableDeferred<Unit>()
        reply={text->finish.await();stream(text,"The current answer is ready.")}
        val fast=makeController(quietMillis=60,intervalMillis=180)
        heard+=ListenResult.Heard("Slow owned fixture")
        fast.start()
        awaitSpoken("Give me a moment.")
        awaitSpoken("I’m still working on that.")
        delay(250)
        assertEquals(2,spoken().size)
        assertEquals(listOf(Message.User("Slow owned fixture")),chat.value.messages)
        finish.complete(Unit)
        withTimeout(3_000) { fast.state.first { it?.phase==VoicePhase.PAUSED } }
        assertEquals("The current answer is ready.",spoken().last())
    }

    @Test fun realResponseInterruptsFillerInsteadOfWaitingBehindIt() = runBlocking {
        val finish=CompletableDeferred<Unit>()
        speaker.holdUntilStopped=true
        reply={text->finish.await();stream(text,"The current answer is ready.")}
        val fast=makeController(quietMillis=60,intervalMillis=180)
        heard+=ListenResult.Heard("Slow owned fixture")
        fast.start()
        awaitSpoken("Give me a moment.")
        speaker.holdUntilStopped=false
        finish.complete(Unit)
        awaitSpoken("The current answer is ready.")
        withTimeout(3_000) { fast.state.first { it?.phase==VoicePhase.PAUSED } }
        assertTrue(speaker.stops>=1)
    }

    @Test fun fillerDoesNotPreventTheUserFromContinuingAnUnfinishedUtterance() = runBlocking {
        var first=true
        reply={text->if(first){first=false;delay(10_000)}else stream(text,"Timer set for ten minutes.")}
        val fast=makeController(quietMillis=60,intervalMillis=180)
        heard+=ListenResult.Heard("set a timer for")
        fast.start()
        awaitSpoken("Give me a moment.")
        heard+=ListenResult.Heard("ten minutes")
        bargeIn.complete(Unit)
        withTimeout(3_000) { fast.state.first { it?.phase==VoicePhase.PAUSED } }
        assertEquals(listOf("set a timer for ten minutes"),resubmitted)
        assertEquals(1,chat.value.messages.count { it is Message.User })
        assertTrue(spoken().contains("Timer set for ten minutes."))
    }

    @Test fun waitingForApprovalDoesNotAddFillerOnTopOfTheQuestion() = runBlocking {
        reply={text->
            chat.update { it.copy(pendingApproval=PendingApproval("owned","Approve this owned fixture?")) }
            chat.first { it.pendingApproval==null }
            stream(text,"The request was declined.")
        }
        val fast=makeController(quietMillis=60,intervalMillis=180)
        heard+=ListenResult.Heard("Owned approval fixture")
        fast.start()
        withTimeout(3_000) { fast.state.first { it?.phase==VoicePhase.CONFIRMING } }
        delay(350)
        assertTrue(spoken().none { it=="Give me a moment." || it=="I’m still working on that." })
        chat.update { it.copy(pendingApproval=null) }
        withTimeout(3_000) { fast.state.first { it?.phase==VoicePhase.PAUSED } }
        assertEquals("The request was declined.",spoken().last())
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
