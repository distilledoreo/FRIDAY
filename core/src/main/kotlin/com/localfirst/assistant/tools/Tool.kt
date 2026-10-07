package com.localfirst.assistant.tools

import kotlinx.serialization.json.JsonObject

/**
 * A capability the assistant is allowed to request. The model never receives
 * a handle to Android APIs; it only sees [definition] and the text returned
 * from [execute].
 */
interface Tool {
    val name: String
    val description: String
    val inputSchema: JsonObject

    /**
     * Reserved for a future confirmation step. Phase 1 has no permission UI.
     * [ToolRegistry] will not call [execute] when this is true.
     */
    val requiresConfirmation: Boolean
        get() = false

    fun definition(): ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        inputSchema = inputSchema,
    )

    suspend fun execute(arguments: JsonObject): ToolExecutionResult
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class ToolExecutionResult(
    val success: Boolean,
    /** What the model reads. */
    val content: String,
    /** Links the UI can show with the result, such as search sources. Not sent to the model. */
    val sources: List<SourceLink> = emptyList(),
)

data class SourceLink(
    val title: String,
    val url: String,
)

/**
 * A tool invocation requested by the model. [argumentsJson] is a JSON object
 * encoded as text, matching the OpenAI tool-call wire format.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)
