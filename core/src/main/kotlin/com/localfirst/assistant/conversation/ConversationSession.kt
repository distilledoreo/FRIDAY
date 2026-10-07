package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
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
    val systemPrompt: String,
    private val engine: ConversationEngine = ConversationEngine(),
) {
    var modelProvider: ModelProvider = modelProvider

    private val messages = mutableListOf<Message>()
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
        engine.continueTurn(
            messages = messages,
            modelProvider = modelProvider,
            toolRegistry = toolRegistry,
            systemPrompt = systemPrompt,
            onUpdate = onUpdate,
        )
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
        engine.continueTurn(
            messages = messages,
            modelProvider = modelProvider,
            toolRegistry = toolRegistry,
            systemPrompt = systemPrompt,
            onUpdate = onUpdate,
        )
    }

    suspend fun clear() {
        mutex.withLock {
            messages.clear()
        }
    }
}
