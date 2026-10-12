package com.localfirst.assistant.model

import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.tools.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Rebuilds one chat completion from OpenAI-style server-sent events.
 *
 * Feed each SSE line to [acceptLine]; it returns any new assistant text so the
 * caller can show it immediately. Tool-call fragments are joined by their
 * `index`. Call [result] after the stream ends.
 */
internal class ChatCompletionStreamAccumulator {
    private val text = StringBuilder()
    private val calls = sortedMapOf<Int, PartialCall>()
    var done: Boolean = false
        private set

    fun acceptLine(line: String): String? {
        if (!line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) return null
        if (data == "[DONE]") {
            done = true
            return null
        }
        val chunk = try {
            JsonCodec.json.parseToJsonElement(data) as? JsonObject
        } catch (e: Exception) {
            throw ModelProviderException("The model server sent a stream event this app could not read.")
        } ?: throw ModelProviderException("The model server sent a stream event that was not a JSON object.")

        errorMessage(chunk["error"])?.let { throw ModelProviderException(it) }

        val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val delta = choice["delta"] as? JsonObject ?: return null
        (delta["tool_calls"] as? JsonArray)?.forEach { acceptToolCallDelta(it as? JsonObject) }
        val piece = (delta["content"] as? JsonPrimitive)?.contentOrNull
        if (piece.isNullOrEmpty()) return null
        text.append(piece)
        return piece
    }

    /** The assistant text received so far. */
    fun text(): String = text.toString()

    fun result(): ModelResponse {
        val content = text.toString().trim()
        if (calls.isEmpty()) return ModelResponse.TextResponse(content)
        val toolCalls = calls.entries.mapIndexed { position, (index, partial) ->
            val name = partial.name.toString().trim()
            if (name.isEmpty()) {
                throw ModelProviderException("The model requested a tool without a name.")
            }
            ToolCall(
                id = partial.id?.trim()?.ifEmpty { null } ?: "call_${index.takeIf { it >= 0 } ?: position}",
                name = name,
                argumentsJson = partial.arguments.toString().ifBlank { "{}" },
            )
        }
        return ModelResponse.ToolCallResponse(calls = toolCalls, text = content.ifEmpty { null })
    }

    private fun acceptToolCallDelta(obj: JsonObject?) {
        obj ?: throw ModelProviderException("A tool call in the model stream was malformed.")
        val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: calls.size
        val partial = calls.getOrPut(index) { PartialCall() }
        (obj["id"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) partial.id = it }
        val function = obj["function"] as? JsonObject ?: return
        (function["name"] as? JsonPrimitive)?.contentOrNull?.let { partial.name.append(it) }
        (function["arguments"] as? JsonPrimitive)?.contentOrNull?.let { partial.arguments.append(it) }
    }

    private class PartialCall {
        var id: String? = null
        val name = StringBuilder()
        val arguments = StringBuilder()
    }
}
