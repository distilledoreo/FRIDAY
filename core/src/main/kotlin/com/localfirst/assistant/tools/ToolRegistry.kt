package com.localfirst.assistant.tools

import com.localfirst.assistant.json.JsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * The set of tools the Android app is willing to run. Unknown names, bad
 * arguments, and execution errors become [ToolExecutionResult]s so the
 * conversation can continue.
 */
class ToolRegistry {
    private val tools = linkedMapOf<String, Tool>()

    fun register(tool: Tool) {
        require(tool.name.isNotBlank()) { "Tool name must not be blank." }
        tools[tool.name] = tool
    }

    fun getAvailableTools(): List<Tool> = tools.values.toList()

    fun definitions(): List<ToolDefinition> = tools.values.map { it.definition() }

    suspend fun execute(call: ToolCall, confirmer: ToolConfirmer? = null, forceConfirmation: Boolean = false, confirmationNote: String? = null): ToolExecutionResult {
        val tool = tools[call.name]
            ?: return ToolExecutionResult(
                success = false,
                content = "Unknown tool '${call.name}'.",
            )
        if ((tool.requiresConfirmation || forceConfirmation) && confirmer == null) {
            return ToolExecutionResult(
                success = false,
                content = "Tool '${tool.name}' requires confirmation before it can run.",
            )
        }
        val arguments = try {
            parseArguments(call.argumentsJson)
        } catch (e: IllegalArgumentException) {
            return ToolExecutionResult(
                success = false,
                content = e.message ?: "Invalid arguments for '${call.name}'.",
            )
        }
        return try {
            if ((tool.requiresConfirmation || forceConfirmation) && confirmer != null) {
                val prompt = listOfNotNull(confirmationNote, tool.confirmationPrompt(arguments)).joinToString(" ")
                if (!confirmer.confirm(ConfirmationRequest(call.id, tool.name, prompt))) {
                    return ToolExecutionResult(
                        success = false,
                        content = "The user declined: $prompt. Do not retry unless they ask again.",
                    )
                }
            }
            tool.execute(arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                content = "Tool '${tool.name}' failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun parseArguments(raw: String): JsonObject {
        if (raw.isBlank()) {
            throw IllegalArgumentException("Tool arguments were empty.")
        }
        val element = try {
            JsonCodec.json.parseToJsonElement(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("Tool arguments were not valid JSON.")
        }
        if (element !is JsonObject) {
            throw IllegalArgumentException("Tool arguments must be a JSON object.")
        }
        return element
    }
}
