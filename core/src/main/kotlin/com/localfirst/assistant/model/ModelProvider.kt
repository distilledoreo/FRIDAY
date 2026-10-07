package com.localfirst.assistant.model

import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.tools.ToolDefinition

/**
 * A source of model completions. The conversation engine depends only on this
 * interface, so a later cloud or routing provider can replace the local HTTP
 * client without changing session or tool code.
 */
interface ModelProvider {
    suspend fun sendConversation(
        messages: List<Message>,
        tools: List<ToolDefinition>,
    ): ModelResponse
}

class ModelProviderException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
