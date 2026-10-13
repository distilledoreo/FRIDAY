package com.localfirst.assistant.voice

/** What the microphone heard in one listening turn. */
sealed interface ListenResult {
    data class Heard(val text: String) : ListenResult
    data object Silence : ListenResult
    data class Failed(val message: String) : ListenResult
}

interface SpeechInput {
    /**
     * Listens for one utterance. [onLevel] is 0..1 for the mic animation.
     * [completeSilenceMs] is how long silence ends the utterance; voice mode
     * passes a shorter value to answer sooner.
     */
    suspend fun listen(onPartial: (String) -> Unit, onLevel: (Float) -> Unit, completeSilenceMs: Long = 1_000L): ListenResult
}

interface Speaker {
    /** False when text-to-speech isn't available. */
    suspend fun prepare(): Boolean
    fun enqueue(text: String)
    suspend fun awaitIdle()
    fun stop()
    fun shutdown()
}

interface BargeInListener {
    /** Returns once the user starts talking. Holds the mic while it runs. */
    suspend fun awaitSpeech()
}

interface VoiceAudioFocus {
    fun acquire()
    fun release()

    /**
     * Mic off for a muted voice session while the assistant keeps speaking;
     * [unmuteMic] turns it back on. Empty by default: engines that open the mic
     * per listening turn don't need to do anything (no [listen] is made).
     */
    fun muteMic() {}
    fun unmuteMic() {}
}

/** The Android pieces voice mode needs, bundled so the view model can be built without them in tests. */
class VoiceIo(
    val input: SpeechInput,
    val speaker: Speaker,
    val bargeIn: BargeInListener,
    val focus: VoiceAudioFocus,
    val ensureMicrophone: suspend () -> Boolean,
)
