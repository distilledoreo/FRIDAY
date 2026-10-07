package com.localfirst.assistant.model

import com.localfirst.assistant.tools.ToolCall

sealed interface ModelResponse {
    data class TextResponse(val text: String) : ModelResponse

    data class ToolCallResponse(
        val calls: List<ToolCall>,
        /** Assistant text that arrived in the same model message, if any. */
        val text: String? = null,
    ) : ModelResponse
}
