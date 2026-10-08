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
    @Test fun mixedPrivateAndWebCallsRequireSeparateConsentBeforePublicRequest() = runBlocking {
        var privateCalls = 0;var webCalls = 0;var round = 0;val prompts = mutableListOf<String>()
        val registry = ToolRegistry().apply { register(tool("read_account_inbox") { privateCalls++ });register(tool("web_search") { webCalls++ }) }
        val model = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse =
                if (round++ == 0) ModelResponse.ToolCallResponse(listOf(ToolCall("a","read_account_inbox","{}"),ToolCall("b","web_search","{}"))) else ModelResponse.TextResponse("No public disclosure.")
        }
        val session = ConversationSession(model,registry,"",confirmer=ToolConfirmer { prompts += it.toolName;false })
        session.submitUserMessage("Summarize selected email")
        assertEquals(1,privateCalls);assertEquals(0,webCalls);assertEquals(listOf("web_search"),prompts)
        assertFalse(session.snapshot().filterIsInstance<Message.ToolResult>().last().success)
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
    @Test fun previousPrivateResultsKeepWebConsentAcrossTurnsAndApprovalAllowsExactCall() = runBlocking {
        var calls = 0;var round = 0;var consent = 0
        val registry = ToolRegistry().apply { register(tool("fetch_page") { calls++ }) }
        val model = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse =
                if (round++ == 0) ModelResponse.ToolCallResponse(listOf(ToolCall("a","fetch_page","{}"))) else ModelResponse.TextResponse("Approved public result.")
        }
        val session = ConversationSession(model,registry,"",initialMessages=listOf(Message.User("Earlier private read"),Message.ToolResult("old","read_daily_brief","Private synthetic snapshot",true)),confirmer=ToolConfirmer { consent++;true })
        session.submitUserMessage("Use a public source now")
        assertEquals(1,consent);assertEquals(1,calls)
    }
}
