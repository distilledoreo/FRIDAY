package com.localfirst.assistant.model

import com.localfirst.assistant.conversation.AttachmentKind
import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.tools.ToolCall
import com.localfirst.assistant.tools.ToolDefinition
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal fun resolveChatCompletionsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) {
        throw ModelProviderException("The server address is empty.")
    }
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        throw ModelProviderException("The server address must start with http:// or https://.")
    }
    return if (trimmed.endsWith("/chat/completions")) {
        trimmed
    } else {
        "$trimmed/chat/completions"
    }
}

internal fun buildChatCompletionRequest(
    model: String,
    messages: List<Message>,
    tools: List<ToolDefinition>,
    stream: Boolean = false,
): JsonObject = buildJsonObject {
    put("model", model)
    put("stream", stream)
    put("messages", messagesToOpenAi(messages))
    if (tools.isNotEmpty()) {
        put("tools", toolsToOpenAi(tools))
        put("tool_choice", "auto")
    }
}

/** Reads a stored attachment image; null when the file is gone. */
internal typealias ImageReader = (path: String) -> ByteArray?

internal val readImageFile: ImageReader = { path -> runCatching { File(path).readBytes() }.getOrNull() }

internal fun messagesToOpenAi(messages: List<Message>, readImage: ImageReader = readImageFile): JsonArray {
    val out = mutableListOf<JsonObject>()
    var index = 0
    while (index < messages.size) {
        when (val message = messages[index]) {
            is Message.System -> out += roleMessage("system", message.content)
            is Message.User -> out += if (message.attachments.isEmpty()) {
                roleMessage("user", message.content)
            } else {
                userMessageWithAttachments(message, readImage)
            }
            is Message.Assistant -> {
                val calls = followingToolCalls(messages, index + 1)
                out += if (calls.isEmpty()) {
                    roleMessage("assistant", message.content)
                } else {
                    assistantToolCallMessage(message.content, calls)
                }
                index += calls.size
            }
            is Message.ToolCall -> {
                val calls = followingToolCalls(messages, index)
                out += assistantToolCallMessage(text = null, calls = calls)
                index += calls.size - 1
            }
            is Message.ToolResult -> out += buildJsonObject {
                put("role", "tool")
                put("tool_call_id", message.toolCallId)
                put("name", message.name)
                put("content", message.content)
            }
        }
        index++
    }
    return JsonArray(out)
}

internal fun parseChatCompletion(body: String): ModelResponse {
    val root = try {
        JsonCodec.json.parseToJsonElement(body)
    } catch (e: Exception) {
        throw ModelProviderException("The model server did not return JSON.")
    }
    if (root !is JsonObject) {
        throw ModelProviderException("The model server did not return a JSON object.")
    }
    val errorMessage = errorMessage(root["error"])
    val choices = root["choices"] as? JsonArray
    if (choices == null) {
        throw ModelProviderException(
            errorMessage ?: "The model server response did not include choices.",
        )
    }
    val first = choices.firstOrNull() as? JsonObject
        ?: throw ModelProviderException(
            errorMessage ?: "The model server returned no choices.",
        )
    val message = first["message"] as? JsonObject
        ?: throw ModelProviderException("The model server choice did not include a message.")
    val text = messageText(message["content"])
    val calls = parseToolCalls(message["tool_calls"])
    return if (calls.isNotEmpty()) {
        ModelResponse.ToolCallResponse(calls = calls, text = text)
    } else {
        ModelResponse.TextResponse(text = text.orEmpty())
    }
}

internal fun snippet(text: String): String {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    if (clean.isEmpty()) return ""
    return if (clean.length <= 300) clean else clean.take(300) + "…"
}

private fun toolsToOpenAi(tools: List<ToolDefinition>): JsonArray = buildJsonArray {
    tools.forEach { tool ->
        add(
            buildJsonObject {
                put("type", "function")
                put(
                    "function",
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.inputSchema)
                    },
                )
            },
        )
    }
}

private fun followingToolCalls(messages: List<Message>, start: Int): List<Message.ToolCall> {
    val calls = mutableListOf<Message.ToolCall>()
    var index = start
    while (index < messages.size) {
        val message = messages[index] as? Message.ToolCall ?: break
        calls += message
        index++
    }
    return calls
}

/**
 * OpenAI multi-part content: one text part with the user's words and any
 * document text (wrapped in <file> tags), then the images as data URIs.
 */
private fun userMessageWithAttachments(message: Message.User, readImage: ImageReader): JsonObject {
    val text = buildString {
        append(message.content)
        for (doc in message.attachments.filter { it.kind == AttachmentKind.DOCUMENT }) {
            if (isNotEmpty()) append("\n\n")
            append("<file name=\"").append(doc.name.replace("\"", "'")).append("\">")
            doc.text?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
            doc.note?.let { append("\n[").append(it).append(']') }
            append("\n</file>")
        }
    }
    val parts = buildJsonArray {
        if (text.isNotBlank()) add(textPart(text))
        for (attachment in message.attachments) {
            val paths = attachment.imagePaths
            if (paths.isEmpty()) continue
            if (attachment.kind == AttachmentKind.DOCUMENT) add(textPart("[Pages of ${attachment.name}]"))
            for (path in paths) {
                val bytes = readImage(path)
                if (bytes == null) {
                    add(textPart("[Image no longer available: ${attachment.name}]"))
                    continue
                }
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        put(
                            "image_url",
                            buildJsonObject { put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)) },
                        )
                    },
                )
            }
        }
    }
    return buildJsonObject {
        put("role", "user")
        put("content", parts)
    }
}

private fun textPart(text: String) = buildJsonObject {
    put("type", "text")
    put("text", text)
}

private fun roleMessage(role: String, content: String): JsonObject = buildJsonObject {
    put("role", role)
    put("content", content)
}

private fun assistantToolCallMessage(text: String?, calls: List<Message.ToolCall>): JsonObject =
    buildJsonObject {
        put("role", "assistant")
        if (text.isNullOrBlank()) {
            put("content", JsonNull)
        } else {
            put("content", text)
        }
        put(
            "tool_calls",
            buildJsonArray {
                calls.forEach { call ->
                    add(
                        buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            put(
                                "function",
                                buildJsonObject {
                                    put("name", call.name)
                                    put("arguments", call.argumentsJson)
                                },
                            )
                        },
                    )
                }
            },
        )
    }

private fun parseToolCalls(element: JsonElement?): List<ToolCall> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapIndexed { index, item ->
        val obj = item as? JsonObject
            ?: throw ModelProviderException("A tool call in the model response was malformed.")
        val function = obj["function"] as? JsonObject
            ?: throw ModelProviderException("A tool call was missing its function.")
        val name = primitiveContent(function["name"]).orEmpty().trim()
        if (name.isEmpty()) {
            throw ModelProviderException("The model requested a tool without a name.")
        }
        val id = primitiveContent(obj["id"]).orEmpty().trim().ifEmpty { "call_$index" }
        ToolCall(
            id = id,
            name = name,
            argumentsJson = normalizeArguments(function["arguments"]),
        )
    }
}

private fun normalizeArguments(element: JsonElement?): String {
    return when (element) {
        null, is JsonNull -> "{}"
        is JsonPrimitive -> {
            if (!element.isString) {
                element.toString()
            } else {
                element.content.ifBlank { "{}" }
            }
        }
        else -> JsonCodec.json.encodeToString(JsonElement.serializer(), element)
    }
}

private fun messageText(content: JsonElement?): String? {
    val text = when (content) {
        null, is JsonNull -> return null
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.mapNotNull { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull
                is JsonObject -> primitiveContent(part["text"])
                else -> null
            }
        }.joinToString("")
        else -> return null
    }?.trim()
    return text?.ifEmpty { null }
}

internal fun errorMessage(error: JsonElement?): String? {
    val objectMessage = (error as? JsonObject)?.get("message")
    val text = primitiveContent(objectMessage) ?: primitiveContent(error)
    return text?.trim()?.ifEmpty { null }
}

private fun primitiveContent(element: JsonElement?): String? {
    return (element as? JsonPrimitive)?.contentOrNull
}
