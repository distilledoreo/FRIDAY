package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.ModelProviderException
import com.localfirst.assistant.model.ModelResponse
import com.localfirst.assistant.tools.ToolRegistry
import kotlinx.coroutines.CancellationException

/**
 * Runs one turn: model response, then zero or more tool rounds, then a final
 * assistant message. Failures return without deleting [messages].
 */
class ConversationEngine(
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
) {
    init {
        require(maxToolRounds >= 1) { "maxToolRounds must be at least 1." }
    }

    suspend fun continueTurn(
        messages: MutableList<Message>,
        modelProvider: ModelProvider,
        toolRegistry: ToolRegistry,
        systemPrompt: String,
        onUpdate: (List<Message>) -> Unit = {},
    ): TurnOutcome {
        var rounds = 0
        while (true) {
            val response = try {
                modelProvider.sendConversation(
                    messages = outboundMessages(systemPrompt, messages),
                    tools = toolRegistry.definitions(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelProviderException) {
                return TurnOutcome.Failed(
                    messages = messages.toList(),
                    error = e.message ?: "Model request failed.",
                )
            } catch (e: Exception) {
                return TurnOutcome.Failed(
                    messages = messages.toList(),
                    error = e.message ?: "Something went wrong talking to the model.",
                )
            }

            when (response) {
                is ModelResponse.TextResponse -> {
                    messages += Message.Assistant(response.text)
                    onUpdate(messages.toList())
                    return TurnOutcome.Completed(messages.toList())
                }

                is ModelResponse.ToolCallResponse -> {
                    if (response.calls.isEmpty()) {
                        messages += Message.Assistant(response.text.orEmpty())
                        onUpdate(messages.toList())
                        return TurnOutcome.Completed(messages.toList())
                    }
                    if (rounds >= maxToolRounds) {
                        return TurnOutcome.Failed(
                            messages = messages.toList(),
                            error = "Stopped because the model requested too many tool rounds.",
                        )
                    }
                    rounds += 1
                    val text = response.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        messages += Message.Assistant(text)
                    }
                    // Keep every call from this response together, then every result.
                    // That order matches providers that expect one assistant tool-call
                    // message followed by the matching tool results.
                    for (call in response.calls) {
                        messages += Message.ToolCall(
                            id = call.id,
                            name = call.name,
                            argumentsJson = call.argumentsJson,
                        )
                    }
                    onUpdate(messages.toList())
                    for (call in response.calls) {
                        val result = toolRegistry.execute(call)
                        messages += Message.ToolResult(
                            toolCallId = call.id,
                            name = call.name,
                            content = result.content,
                            success = result.success,
                        )
                        onUpdate(messages.toList())
                    }
                }
            }
        }
    }

    private fun outboundMessages(systemPrompt: String, messages: List<Message>): List<Message> {
        if (systemPrompt.isBlank()) return messages.toList()
        return listOf(Message.System(systemPrompt)) + messages
    }

    companion object {
        const val DEFAULT_MAX_TOOL_ROUNDS = 4
    }
}
