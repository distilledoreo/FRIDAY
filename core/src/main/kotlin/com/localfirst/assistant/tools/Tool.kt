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
     * When true, [ToolRegistry] asks a [ToolConfirmer] before calling [execute],
     * and refuses the call if there is no confirmer or the user declines.
     */
    val requiresConfirmation: Boolean
        get() = false

    /**
     * What the user is asked to approve, e.g. "Call Mom (mobile, +1 555 0100)".
     * May look things up; throw to fail the call before asking.
     */
    suspend fun confirmationPrompt(arguments: JsonObject): String = "Allow $name?"

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

/** Asks the user to approve a tool call. Returns true to run it. */
fun interface ToolConfirmer {
    suspend fun confirm(request: ConfirmationRequest): Boolean
}

data class ConfirmationRequest(
    val callId: String,
    val toolName: String,
    val prompt: String,
)
