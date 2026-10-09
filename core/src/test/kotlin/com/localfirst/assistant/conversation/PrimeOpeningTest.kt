package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.ModelResponse
import com.localfirst.assistant.tools.Tool
import com.localfirst.assistant.tools.ToolDefinition
import com.localfirst.assistant.tools.ToolExecutionResult
import com.localfirst.assistant.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PrimeOpeningTest {
    private fun tool(name: String) = object : Tool {
        override val name = name
        override val description = "fixture"
        override val inputSchema = buildJsonObject { }
        override suspend fun execute(arguments: JsonObject) = ToolExecutionResult(true, "ok")
    }

    @Test
    fun aPrimeCarriesExactlyTheOpeningOfTheFirstRequest() = runBlocking {
        var primed: Pair<List<Message>, List<ToolDefinition>>? = null
        var first: Pair<List<Message>, List<ToolDefinition>>? = null
        val model = object : ModelProvider {
            override suspend fun prime(messages: List<Message>, tools: List<ToolDefinition>) { primed = messages to tools }
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse {
                first = messages to tools
                return ModelResponse.TextResponse("Hi.")
            }
        }
        val registry = ToolRegistry().apply { register(tool("web_search")); register(tool("set_alarm")) }
        ConversationSession(model, registry, "System prompt for today.").primeOpening()
        ConversationSession(model, registry, "System prompt for today.").submitUserMessage("Hello")
        assertEquals(listOf(Message.System("System prompt for today.")), primed!!.first)
        assertEquals(first!!.first.first(), primed!!.first.single())
        assertEquals(first!!.second, primed!!.second)
    }
}
