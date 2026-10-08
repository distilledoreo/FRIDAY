package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.*
import com.localfirst.assistant.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WebConsentTest {
    private fun tool(name: String, action: () -> Unit) = object : Tool {
        override val name = name
        override val description = "fixture"
        override val inputSchema = buildJsonObject { }
        override suspend fun execute(arguments: JsonObject): ToolExecutionResult { action();return ToolExecutionResult(true,"Synthetic result") }
    }
    private fun search(query: String) = ToolCall("w","web_search",buildJsonObject { put("query",query) }.toString())
    private val inbox = object : Tool {
        override val name = "read_account_inbox"
        override val description = "fixture"
        override val inputSchema = buildJsonObject { }
        override suspend fun execute(arguments: JsonObject) = ToolExecutionResult(true,"From Jordan Whitfield: the Maplewood lease renewal is due Friday.")
    }
    @Test fun webCallCarryingPrivateToolDetailsAsksFirstAndSaysWhy() = runBlocking {
        var webCalls = 0;var round = 0;val prompts = mutableListOf<String>()
        val registry = ToolRegistry().apply { register(inbox);register(tool("web_search") { webCalls++ }) }
        val model = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse =
                if (round++ == 0) ModelResponse.ToolCallResponse(listOf(ToolCall("a","read_account_inbox","{}"),search("Maplewood lease renewal rules"))) else ModelResponse.TextResponse("No public disclosure.")
        }
        val session = ConversationSession(model,registry,"",confirmer=ToolConfirmer { prompts += it.prompt;false })
        session.submitUserMessage("Summarize my newest email")
        assertEquals(0,webCalls);assertEquals(1,prompts.size);assertTrue(prompts.single(),prompts.single().contains("maplewood"))
        assertFalse(session.snapshot().filterIsInstance<Message.ToolResult>().last().success)
    }
    @Test fun webCallUsingOnlyTheUsersWordsRunsWithoutAskingEvenWithMemoriesRecalled() = runBlocking {
        var webCalls = 0;var round = 0;var asked = 0
        val registry = ToolRegistry().apply { register(tool("web_search") { webCalls++ }) }
        val model = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse =
                if (round++ == 0) ModelResponse.ToolCallResponse(listOf(search("Apple CEO 2026"))) else ModelResponse.TextResponse("Answer.")
        }
        val prompt = "System.\n\nRetrieved personal context. Approved memories are user-reviewed facts.\n\nThe user lives in Plano and owns a OnePlus 13."
        val session = ConversationSession(model,registry,prompt,confirmer=ToolConfirmer { asked++;true })
        session.submitUserMessage("Who is the CEO of Apple?")
        assertEquals(0,asked);assertEquals(1,webCalls)
    }
    @Test fun webCallAddingARecalledDetailAsksFirst() = runBlocking {
        var webCalls = 0;var round = 0;val prompts = mutableListOf<String>()
        val registry = ToolRegistry().apply { register(tool("web_search") { webCalls++ }) }
        val model = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse =
                if (round++ == 0) ModelResponse.ToolCallResponse(listOf(search("weather Plano today"))) else ModelResponse.TextResponse("Answer.")
        }
        val prompt = "System.\n\nRetrieved personal context. Approved memories are user-reviewed facts.\n\nThe user lives in Plano and owns a OnePlus 13."
        val session = ConversationSession(model,registry,prompt,confirmer=ToolConfirmer { prompts += it.prompt;true })
        session.submitUserMessage("What's the weather today?")
        assertEquals(1,prompts.size);assertTrue(prompts.single().contains("plano"));assertEquals(1,webCalls)
    }
    @Test fun blockedNetworkCallsAreNotAdvertisedAndCannotRunEvenIfModelAsks() = runBlocking {
        var calls = 0;var round = 0
        val registry = ToolRegistry().apply { register(tool("web_search") { calls++ }) }
        val model = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse {
                assertFalse(tools.any { it.name == "web_search" })
                return if (round++ == 0) ModelResponse.ToolCallResponse(listOf(ToolCall("a","web_search","{}"))) else ModelResponse.TextResponse("Offline answer.")
            }
        }
        val session = ConversationSession(model,registry,"").apply { blockedTools=setOf("web_search") }
        session.submitUserMessage("Don't search")
        assertEquals(0,calls);assertFalse(session.snapshot().filterIsInstance<Message.ToolResult>().single().success)
    }
    @Test fun privateResultsFromEarlierTurnsStillCount() = runBlocking {
        var calls = 0;var round = 0;var consent = 0
        val registry = ToolRegistry().apply { register(tool("fetch_page") { calls++ }) }
        val model = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse = when (round++) {
                0 -> ModelResponse.ToolCallResponse(listOf(ToolCall("a","fetch_page",buildJsonObject { put("url","https://example.com/search?q=Whitfield") }.toString())))
                1 -> ModelResponse.ToolCallResponse(listOf(ToolCall("b","fetch_page",buildJsonObject { put("url","https://www.iana.org/help/example-domains") }.toString())))
                else -> ModelResponse.TextResponse("Done.")
            }
        }
        val session = ConversationSession(model,registry,"",initialMessages=listOf(Message.User("Earlier private read"),Message.ToolResult("old","read_daily_brief","Lunch with Dana Whitfield at noon",true)),confirmer=ToolConfirmer { consent++;true })
        session.submitUserMessage("Use a public source now")
        assertEquals(1,consent);assertEquals(2,calls)
    }
}
