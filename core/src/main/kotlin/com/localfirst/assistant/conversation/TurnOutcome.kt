package com.localfirst.assistant.conversation

/**
 * Result of one user turn. [messages] is the full session history after the attempt.
 * A [Failed] outcome leaves that history in place.
 */
sealed interface TurnOutcome {
    val messages: List<Message>

    data class Completed(override val messages: List<Message>) : TurnOutcome

    data class Failed(
        override val messages: List<Message>,
        val error: String,
    ) : TurnOutcome

    data class EmptyInput(override val messages: List<Message>) : TurnOutcome
}
