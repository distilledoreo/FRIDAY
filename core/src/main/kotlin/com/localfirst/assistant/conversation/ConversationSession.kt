package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.tools.ToolConfirmer
import com.localfirst.assistant.tools.ToolRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Conversation state for one chat. Independent of the UI.
 *
 * [modelProvider] can be replaced when the server address changes; existing
 * messages stay. Tool calls from a turn that already started keep using the
 * provider captured at the beginning of that turn.
 */
class ConversationSession(
    modelProvider: ModelProvider,
    val toolRegistry: ToolRegistry,
    systemPrompt: String,
    private val engine: ConversationEngine = ConversationEngine(),
    initialMessages: List<Message> = emptyList(),
    /** Approves tools that require confirmation. Null refuses them. */
    var confirmer: ToolConfirmer? = null,
) {
    var modelProvider: ModelProvider = modelProvider

    /** Can change between turns, e.g. to include the current time. */
    var systemPrompt: String = systemPrompt

    private val messages = initialMessages.toMutableList()
    private val mutex = Mutex()

    fun snapshot(): List<Message> = messages.toList()

    suspend fun submitUserMessage(
        text: String,
        onUpdate: (List<Message>) -> Unit = {},
    ): TurnOutcome = mutex.withLock {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return@withLock TurnOutcome.EmptyInput(messages.toList())
        }
        messages += Message.User(trimmed)
        onUpdate(messages.toList())
        runTurn(onUpdate)
    }

    /**
     * Asks the model to continue from the current history. Used after a failed
     * request so a retry does not duplicate the user message or re-run tools
     * that already recorded a result.
     */
    suspend fun retry(onUpdate: (List<Message>) -> Unit = {}): TurnOutcome = mutex.withLock {
        if (messages.isEmpty()) {
            return@withLock TurnOutcome.EmptyInput(messages.toList())
        }
        runTurn(onUpdate)
    }

    /**
     * Drops everything after the latest user message (the previous answer and
     * any tool activity) and asks the model again.
     */
    suspend fun regenerate(onUpdate: (List<Message>) -> Unit = {}): TurnOutcome = mutex.withLock {
        val lastUser = messages.indexOfLast { it is Message.User }
        if (lastUser < 0) {
            return@withLock TurnOutcome.EmptyInput(messages.toList())
        }
        truncateAfter(lastUser)
        onUpdate(messages.toList())
        runTurn(onUpdate)
    }

    /**
     * Replaces the user message at [index] with [text], drops everything after
     * it, and asks the model again. [index] refers to [snapshot].
     */
    suspend fun editUserMessage(
        index: Int,
        text: String,
        onUpdate: (List<Message>) -> Unit = {},
    ): TurnOutcome = mutex.withLock {
        val trimmed = text.trim()
        require(messages.getOrNull(index) is Message.User) { "Message $index is not a user message." }
        if (trimmed.isEmpty()) {
            return@withLock TurnOutcome.EmptyInput(messages.toList())
        }
        messages[index] = Message.User(trimmed)
        truncateAfter(index)
        onUpdate(messages.toList())
        runTurn(onUpdate)
    }

    suspend fun clear() {
        mutex.withLock {
            messages.clear()
        }
    }

    private suspend fun runTurn(onUpdate: (List<Message>) -> Unit): TurnOutcome =
        engine.continueTurn(
            messages = messages,
            modelProvider = modelProvider,
            toolRegistry = toolRegistry,
            systemPrompt = systemPrompt,
            onUpdate = onUpdate,
            confirmer = confirmer,
        )

    private fun truncateAfter(index: Int) {
        while (messages.size > index + 1) {
            messages.removeAt(messages.lastIndex)
        }
    }
}
