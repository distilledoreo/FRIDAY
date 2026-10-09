package com.localfirst.assistant.voice

import com.localfirst.assistant.ui.ChatUiState
import com.localfirst.assistant.ui.PendingApproval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VoicePhase { STARTING, LISTENING, THINKING, SPEAKING, CONFIRMING, PAUSED }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.STARTING,
    /** What the user is saying (live) or last said. */
    val heard: String = "",
    /** Mic level 0..1 while listening. */
    val level: Float = 0f,
    /** The sentence being spoken. */
    val speaking: String = "",
    /** Why voice paused, or a hint. */
    val note: String? = null,
)

/** The audio setup for one voice session, and a note to show (for example, why it fell back). */
class VoiceSelection(val io: VoiceIo, val note: String? = null)

/**
 * Hands-free conversation: listen → send → speak the streamed reply sentence
 * by sentence → listen again. While the assistant thinks or speaks, the
 * barge-in listener watches the mic; if the user starts talking, speech stops,
 * the reply is cancelled (keeping what arrived), and it listens. Calls and
 * texts are approved by voice ("yes" / "no") or by tapping the card.
 */
class VoiceController(
    private val scope: CoroutineScope,
    private val selectIo: suspend () -> VoiceSelection,
    private val chat: StateFlow<ChatUiState>,
    private val submit: (String) -> Boolean,
    /** Replaces the last user message with [String] and asks again; false if that isn't safe. */
    private val resubmit: (String) -> Boolean,
    private val stopTurn: () -> Unit,
    private val answerApproval: (Boolean) -> Unit,
    private val bargeInEnabled: () -> Boolean,
    private val feedbackQuietMillis: Long = 6_000,
    private val feedbackIntervalMillis: Long = 30_000,
) {
    private val _state = MutableStateFlow<VoiceUiState?>(null)

    /** Null when voice mode is closed. */
    val state: StateFlow<VoiceUiState?> = _state.asStateFlow()

    private var loop: Job? = null
    private var interrupt: CompletableDeferred<Unit>? = null
    private var current: VoiceIo? = null

    // A pause mid-thought can end an utterance early. When the user keeps
    // talking right away, the next utterance continues the last one.
    private var lastSent: String? = null
    private var lastSentAt = 0L
    private var continueLast = false
    private val io: VoiceIo get() = checkNotNull(current) { "voice mode isn't running" }

    val active: Boolean get() = _state.value != null

    /** Opens voice mode, or resumes it when paused. */
    fun start() {
        if (loop?.isActive == true) return
        _state.value = (_state.value ?: VoiceUiState()).copy(phase = VoicePhase.STARTING, note = null, level = 0f)
        loop = scope.launch { runLoop() }
    }

    /** Stops listening and speaking but keeps voice mode open ("tap to talk"). */
    fun pause(note: String? = null) {
        loop?.cancel()
        loop = null
        current?.speaker?.stop()
        current?.focus?.release()
        _state.update { it?.copy(phase = VoicePhase.PAUSED, level = 0f, note = note ?: it.note) }
    }

    fun close() {
        loop?.cancel()
        loop = null
        current?.speaker?.stop()
        current?.focus?.release()
        _state.value = null
    }

    /** Tap-to-interrupt: stop talking (or thinking) and listen. */
    fun interrupt() {
        interrupt?.complete(Unit)
    }

    fun shutdown() {
        close()
        current?.speaker?.shutdown()
        current = null
    }

    private suspend fun runLoop() {
        try {
            val selection = selectIo()
            if (current !== selection.io) current?.speaker?.shutdown()
            current = selection.io
            selection.note?.let { note -> _state.update { it?.copy(note = note) } }
            if (!io.ensureMicrophone()) return pauseWith("Voice needs microphone access. Allow it and tap to talk.")
            if (!io.speaker.prepare()) return pauseWith("Text-to-speech isn't available.")
            io.focus.acquire()
            var quiet = 0
            while (true) {
                chat.value.pendingApproval?.let { confirm(it) }
                // A cancelled reply may still be wrapping up.
                withTimeoutOrNull(5_000) { chat.first { !it.busy } }
                set(VoicePhase.LISTENING, heard = "")
                val text = when (val result = io.input.listen(::onPartial, ::onLevel, VOICE_SILENCE_MS)) {
                    is ListenResult.Heard -> result.text
                    ListenResult.Silence -> {
                        if (++quiet >= 2) return pauseWith("Tap to talk.")
                        continue
                    }
                    is ListenResult.Failed -> return pauseWith(result.message)
                }
                quiet = 0
                val previous = lastSent
                val merged = if (continueLast && previous != null) "$previous $text" else null
                continueLast = false
                val sent = when {
                    merged != null && resubmit(merged) -> merged
                    submit(text) -> text
                    else -> return pauseWith("Couldn't send that. Check the server settings.")
                }
                lastSent = sent
                lastSentAt = System.currentTimeMillis()
                _state.update { it?.copy(heard = sent, level = 0f) }
                speakReply()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pauseWith("Voice stopped: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Speaks the reply as it streams; returns when it's done or the user barged in. */
    private suspend fun speakReply() = coroutineScope {
        set(VoicePhase.THINKING)
        val reply = ReplySpeech()
        var spoke = false
        val interrupted = CompletableDeferred<Unit>()
        interrupt = interrupted
        var bargeIn: Job? = null
        fun listenForBargeIn() {
            if (!bargeInEnabled()) return
            bargeIn = launch {
                io.bargeIn.awaitSpeech()
                interrupted.complete(Unit)
            }
        }

        val speaking = launch {
            listenForBargeIn()
            val audioLock = Mutex()
            fun nowMillis() = System.nanoTime() / 1_000_000
            val feedback = VoiceWaitFeedback(nowMillis(), feedbackQuietMillis, feedbackIntervalMillis)
            var audioPending = false
            var fillerPlaying = false
            var audioGeneration = 0
            var idleWatcher: Job? = null
            fun watchAudio() {
                val generation = ++audioGeneration
                idleWatcher?.cancel()
                idleWatcher = launch {
                    io.speaker.awaitIdle()
                    audioLock.withLock {
                        if (generation == audioGeneration) {
                            audioPending = false
                            fillerPlaying = false
                            feedback.audioFinished(nowMillis())
                            if (chat.value.busy && chat.value.pendingApproval == null) set(VoicePhase.THINKING, speaking = "")
                        }
                    }
                }
            }
            val waiting = launch {
                while (true) {
                    delay(minOf(500, feedbackQuietMillis.coerceAtLeast(1)))
                    audioLock.withLock {
                        if (chat.value.busy && chat.value.pendingApproval == null && !audioPending) {
                            feedback.next(nowMillis())?.let { line ->
                                audioPending = true
                                fillerPlaying = true
                                io.speaker.enqueue(line)
                                set(VoicePhase.SPEAKING, speaking = line)
                                watchAudio()
                            }
                        }
                    }
                }
            }
            try {
                chat.first { s ->
                    val chunks = if (s.turnMessagesReady) reply.update(s.messages, s.busy) else emptyList()
                    if (chunks.isNotEmpty()) audioLock.withLock {
                        // Real speech always takes priority over a waiting acknowledgement.
                        if (fillerPlaying) io.speaker.stop()
                        fillerPlaying = false
                        audioPending = true
                        chunks.forEach(io.speaker::enqueue)
                        spoke = true
                        set(VoicePhase.SPEAKING, speaking = chunks.last())
                        watchAudio()
                    }
                    val approval = s.pendingApproval
                    if (approval != null) {
                        audioLock.withLock {
                            audioGeneration++
                            idleWatcher?.cancel()
                            if (fillerPlaying) io.speaker.stop()
                            fillerPlaying = false
                            audioPending = true
                        }
                        // The mic is needed for "yes" / "no".
                        bargeIn?.cancel()
                        bargeIn?.join()
                        io.speaker.awaitIdle()
                        confirm(approval)
                        set(VoicePhase.THINKING)
                        audioLock.withLock { audioPending = false; feedback.audioFinished(nowMillis()) }
                        listenForBargeIn()
                    }
                    !s.busy && s.pendingApproval == null
                }
                io.speaker.awaitIdle()
            } finally {
                waiting.cancelAndJoin()
                idleWatcher?.cancelAndJoin()
            }
        }

        val userInterrupted = select {
            speaking.onJoin { false }
            interrupted.onAwait { true }
        }
        interrupt = null
        bargeIn?.cancel()
        if (userInterrupted) {
            speaking.cancelAndJoin()
            io.speaker.stop()
            if (chat.value.busy) stopTurn()
            // Talking again before the assistant said anything, soon after sending, means "I wasn't done".
            // Interrupting once it's speaking is a new instruction.
            continueLast = !spoke && System.currentTimeMillis() - lastSentAt < CONTINUE_WINDOW_MS
        }
    }

    /** Reads the approval aloud and listens for yes or no; the card stays tappable throughout. */
    private suspend fun confirm(approval: PendingApproval) {
        set(VoicePhase.CONFIRMING, speaking = approval.prompt)
        io.speaker.enqueue(SpeechText.fromMarkdown(approval.prompt) + ". Should I go ahead?")
        io.speaker.awaitIdle()
        repeat(2) {
            if (chat.value.pendingApproval != approval) return
            set(VoicePhase.CONFIRMING, heard = "")
            val result = io.input.listen(::onPartial, ::onLevel)
            if (chat.value.pendingApproval != approval) return
            val answer = (result as? ListenResult.Heard)?.let { VoiceReplies.approval(it.text) }
            if (answer != null) {
                answerApproval(answer)
                return
            }
            io.speaker.enqueue("Please say yes or no.")
            io.speaker.awaitIdle()
        }
        _state.update { it?.copy(note = "Tap Approve or Deny.") }
        chat.first { it.pendingApproval != approval }
    }

    private fun onPartial(text: String) = _state.update { it?.copy(heard = text) }

    private fun onLevel(level: Float) = _state.update { it?.copy(level = level) }

    private fun set(phase: VoicePhase, heard: String? = null, speaking: String? = null) = _state.update {
        it?.copy(
            phase = phase,
            heard = heard ?: it.heard,
            speaking = speaking ?: if (phase == VoicePhase.LISTENING) "" else it.speaking,
            level = 0f,
            // A fallback note stays visible; other hints clear when the phase changes.
            note = it.note?.takeIf { note -> note.startsWith(FALLBACK_PREFIX) },
        )
    }

    private fun pauseWith(note: String) {
        current?.speaker?.stop()
        current?.focus?.release()
        _state.update { it?.copy(phase = VoicePhase.PAUSED, level = 0f, note = note) }
    }

    companion object {
        /** Notes starting with this stay on screen for the whole session. */
        const val FALLBACK_PREFIX = "Using the phone's voice"

        /** Talking again within this long after sending, before any reply is spoken, continues the same message. */
        const val CONTINUE_WINDOW_MS = 8_000L

        /** Silence ending a voice-mode utterance: shorter than dictation so answers start sooner. */
        const val VOICE_SILENCE_MS = 700L
    }
}
