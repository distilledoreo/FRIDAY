package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.ModelProviderException
import com.localfirst.assistant.model.ModelResponse
import com.localfirst.assistant.tools.ToolRegistry
import kotlinx.coroutines.CancellationException

/**
 * Runs one turn: model response, then zero or more tool rounds, then a final
 * assistant message. Failures return without deleting [messages].
 *
 * Assistant text streams through [continueTurn]'s `onUpdate` as a trailing,
 * still-growing [Message.Assistant]. If the caller cancels (the user pressed
 * Stop), text received so far is kept as the assistant message, and tool calls
 * that never got a result are answered with a "stopped" result, so the history
 * stays valid for the next request.
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
            val partial = StringBuilder()
            val response = try {
                modelProvider.streamConversation(
                    messages = outboundMessages(systemPrompt, messages),
                    tools = toolRegistry.definitions(),
                ) { delta ->
                    // Providers may deliver deltas on their own thread.
                    val soFar = synchronized(partial) { partial.append(delta).toString() }
                    onUpdate(messages.toList() + Message.Assistant(soFar))
                }
            } catch (e: CancellationException) {
                val text = synchronized(partial) { partial.toString() }.trim()
                if (text.isNotEmpty()) {
                    messages += Message.Assistant(text)
                }
                onUpdate(messages.toList())
                throw e
            } catch (e: ModelProviderException) {
                onUpdate(messages.toList())
                return TurnOutcome.Failed(
                    messages = messages.toList(),
                    error = e.message ?: "Model request failed.",
                )
            } catch (e: Exception) {
                onUpdate(messages.toList())
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
                        onUpdate(messages.toList())
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
                    for ((index, call) in response.calls.withIndex()) {
                        val result = try {
                            toolRegistry.execute(call)
                        } catch (e: CancellationException) {
                            for (unanswered in response.calls.drop(index)) {
                                messages += Message.ToolResult(
                                    toolCallId = unanswered.id,
                                    name = unanswered.name,
                                    content = STOPPED_RESULT,
                                    success = false,
                                )
                            }
                            onUpdate(messages.toList())
                            throw e
                        }
                        messages += Message.ToolResult(
                            toolCallId = call.id,
                            name = call.name,
                            content = result.content,
                            success = result.success,
                            sources = result.sources,
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
        const val STOPPED_RESULT = "Stopped by the user before this tool finished."
    }
}
