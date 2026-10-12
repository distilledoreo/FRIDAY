package com.localfirst.assistant.model

import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.tools.SetMediaVolumeTool
import com.localfirst.assistant.tools.ToolCall
import com.localfirst.assistant.tools.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatMappingTest {
    @Test
    fun resolvesChatCompletionsUrl() {
        assertEquals(
            "http://10.0.2.2:11434/v1/chat/completions",
            resolveChatCompletionsUrl("http://10.0.2.2:11434/v1/"),
        )
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            resolveChatCompletionsUrl("http://127.0.0.1:8080/v1/chat/completions"),
        )
    }

    @Test
    fun rejectsAddressWithoutScheme() {
        val error = runCatching { resolveChatCompletionsUrl("192.168.1.20:11434/v1") }
            .exceptionOrNull()
        assertTrue(error is ModelProviderException)
    }

    @Test
    fun requestOmitsToolsUntilTheRegistryHasSome() {
        val plain = buildChatCompletionRequest("llama3.2", listOf(Message.User("Hi")), emptyList())
        assertEquals("llama3.2", plain["model"]!!.jsonPrimitive.content)
        assertEquals(false, plain["stream"]!!.jsonPrimitive.content.toBoolean())
        assertNull(plain["tools"])

        val withTool = buildChatCompletionRequest(
            model = "llama3.2",
            messages = listOf(Message.User("Hi")),
            tools = listOf(volumeDefinition()),
        )
        assertEquals("auto", withTool["tool_choice"]!!.jsonPrimitive.content)
        val name = withTool["tools"]!!.jsonArray.first().jsonObject["function"]!!
            .jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("set_media_volume", name)
    }

    @Test
    fun groupsParallelToolCallsAndKeepsResultsOutOfTheUserRole() {
        val wire = messagesToOpenAi(
            listOf(
                Message.User("Set the volume to 30%."),
                Message.Assistant("I'll set it."),
                Message.ToolCall("a", "set_media_volume", """{"level":30}"""),
                Message.ToolCall("b", "set_media_volume", """{"level":40}"""),
                Message.ToolResult("a", "set_media_volume", "Media volume set to 30%.", true),
                Message.ToolResult("b", "set_media_volume", "Media volume set to 40%.", true),
            ),
        )
        assertEquals(listOf("user", "assistant", "tool", "tool"), roles(wire))
        val assistant = wire[1].jsonObject
        assertEquals("I'll set it.", assistant["content"]!!.jsonPrimitive.content)
        assertEquals(2, assistant["tool_calls"]!!.jsonArray.size)
        assertEquals("a", wire[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertFalse(roles(wire).drop(1).contains("user"))
    }

    @Test
    fun parsesObjectArgumentsMissingIdsAndTextParts() {
        val toolCall = parseChatCompletion(
            """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [
                      {
                        "type": "function",
                        "function": {
                          "name": "set_media_volume",
                          "arguments": {"level": 30}
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        ) as ModelResponse.ToolCallResponse

        assertNull(toolCall.text)
        assertEquals("call_0", toolCall.calls.single().id)
        assertEquals("set_media_volume", toolCall.calls.single().name)
        val level = kotlinx.serialization.json.Json.parseToJsonElement(toolCall.calls.single().argumentsJson)
            .jsonObject["level"]!!.jsonPrimitive.int
        assertEquals(30, level)

        val text = parseChatCompletion(
            """
            {"choices":[{"message":{"content":[{"type":"text","text":"Hello"}]}}]}
            """.trimIndent(),
        )
        assertEquals(ModelResponse.TextResponse("Hello"), text)
    }

    @Test
    fun parsesServerErrorObjectsAndMalformedBodies() {
        val error = runCatching {
            parseChatCompletion("""{"error":{"message":"model not found"}}""")
        }.exceptionOrNull()
        assertTrue(error is ModelProviderException)
        assertTrue(error!!.message!!.contains("model not found"))

        val malformed = runCatching { parseChatCompletion("nope") }.exceptionOrNull()
        assertTrue(malformed is ModelProviderException)
    }

    private fun volumeDefinition(): ToolDefinition = ToolDefinition(
        name = SetMediaVolumeTool.NAME,
        description = SetMediaVolumeTool.DESCRIPTION,
        inputSchema = SetMediaVolumeTool.SCHEMA,
    )

    private fun roles(messages: JsonArray): List<String> {
        return messages.map { element ->
            (element as JsonObject)["role"] as JsonPrimitive
        }.map { it.content }
    }
}
