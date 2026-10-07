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

    /**
     * Like [sendConversation], but reports assistant text through [onTextDelta]
     * as it arrives. Returns the complete response once the model is done.
     * Providers that can't stream deliver the whole text as one delta.
     */
    suspend fun streamConversation(
        messages: List<Message>,
        tools: List<ToolDefinition>,
        onTextDelta: (String) -> Unit,
    ): ModelResponse {
        val response = sendConversation(messages, tools)
        val text = when (response) {
            is ModelResponse.TextResponse -> response.text
            is ModelResponse.ToolCallResponse -> response.text.orEmpty()
        }
        if (text.isNotEmpty()) onTextDelta(text)
        return response
    }
}

class ModelProviderException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
