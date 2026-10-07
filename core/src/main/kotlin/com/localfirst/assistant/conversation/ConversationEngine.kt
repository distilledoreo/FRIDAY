package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.ModelProviderException
import com.localfirst.assistant.model.ModelResponse
import com.localfirst.assistant.tools.ToolConfirmer
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
        confirmer: ToolConfirmer? = null,
        latestUserNote: String? = null,
    ): TurnOutcome {
        var rounds = 0
        while (true) {
            val partial = StringBuilder()
            val response = try {
                modelProvider.streamConversation(
                    messages = outboundMessages(systemPrompt, messages, latestUserNote),
                    tools = toolRegistry.definitions(),
                ) { delta ->
                    // Providers may deliver deltas on their own thread.
                    val soFar = synchronized(partial) { partial.append(delta).toString() }
                    onUpdate(messages.toList() + Message.Assistant(visibleText(soFar)))
                }
            } catch (e: CancellationException) {
                val text = visibleText(synchronized(partial) { partial.toString() })
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
                    messages += Message.Assistant(visibleText(response.text))
                    onUpdate(messages.toList())
                    return TurnOutcome.Completed(messages.toList())
                }

                is ModelResponse.ToolCallResponse -> {
                    if (response.calls.isEmpty()) {
                        messages += Message.Assistant(visibleText(response.text.orEmpty()))
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
                    val text = visibleText(response.text.orEmpty())
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
                            toolRegistry.execute(call, confirmer)
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

    /**
     * What the model is sent. [note] is appended to the latest user message
     * only, and never stored: per-turn context such as the time goes there
     * instead of the system prompt, so the system prompt and earlier messages
     * stay identical between turns and the server's prompt cache keeps working.
     */
    internal fun outboundMessages(systemPrompt: String, messages: List<Message>, note: String? = null): List<Message> {
        // Keep complete user turns, including all associated tool calls/results.
        // The on-device archive remains complete and searchable with search_history.
        val starts = messages.indices.filter { messages[it] is Message.User }
        var start = 0
        var length = messages.sumOf(::contextLength)
        for (next in starts.drop(1)) {
            if (length <= 70000) break
            length -= messages.subList(start, next).sumOf(::contextLength)
            start = next
        }
        val selected = messages.drop(start).map { m ->
            when (m) {
                is Message.ToolResult -> m.copy(content = m.content.take(16000))
                is Message.User -> m.copy(attachments = m.attachments.map { it.copy(text = it.text?.take(24000), note = if ((it.text?.length ?: 0) > 24000)
                    listOfNotNull(it.note, "Model excerpt limited to 24,000 characters. Use execute_python on the workspace file for complete analysis.").joinToString(" ") else it.note) })
                else -> m
            }
        }
        val lastUser = if (note.isNullOrBlank()) -1 else selected.indexOfLast { it is Message.User }
        val body = selected.mapIndexed { i, m ->
            if (i == lastUser) (m as Message.User).let { it.copy(content = "${it.content}\n\n$note".trim()) } else m
        }
        val prompt = systemPrompt + if (start > 0) "\nEarlier turns were omitted to fit context. Use search_history to recover details; do not assume you recall them." else ""
        if (prompt.isBlank()) return body
        return listOf(Message.System(prompt)) + body
    }

    private fun contextLength(m: Message): Int = when (m) {
        is Message.User -> m.content.length + m.attachments.sumOf { (it.text?.length ?: 0) + it.imagePaths.size * 5000 }
        is Message.Assistant -> m.content.length
        is Message.ToolResult -> m.content.length
        is Message.ToolCall -> m.argumentsJson.length
        is Message.System -> m.content.length
    }

    companion object {
        /**
         * Hides reasoning markup some models emit even with reasoning off:
         * text before a closing `</think>` (often a draft the model then repeats)
         * and anything after an unclosed `<think>`.
         */
        fun visibleText(raw: String): String {
            var text = raw
            if (text.contains(THINK_CLOSE)) text = text.substringAfterLast(THINK_CLOSE)
            if (text.contains(THINK_OPEN)) text = text.substringBefore(THINK_OPEN)
            return text.trim()
        }

        private const val THINK_OPEN = "<think>"
        private const val THINK_CLOSE = "</think>"
        const val DEFAULT_MAX_TOOL_ROUNDS = 20
        const val STOPPED_RESULT = "Stopped by the user before this tool finished."
    }
}
