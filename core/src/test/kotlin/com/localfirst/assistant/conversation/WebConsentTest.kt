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
        override suspend fun execute(arguments: JsonObject) = ToolExecutionResult(true,"From Jordan Whitfield <jordan@example.org>: the Maplewood lease renewal is due Friday.")
    }
    private val memories = "System.\n\nRetrieved personal context. Approved memories are user-reviewed facts.\n\nThe user lives in Plano and owns a OnePlus 13."

    /** Plays the chat model, and answers the identifying-details check with [verdict]. */
    private class Model(private val verdict: () -> String, private val turns: List<ModelResponse>) : ModelProvider {
        var judged = 0
        private var round = 0
        override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse {
            if ((messages.firstOrNull() as? Message.System)?.content?.startsWith("You check web searches") == true) {
                judged++
                return ModelResponse.TextResponse(verdict())
            }
            return turns.getOrElse(round++) { ModelResponse.TextResponse("Done.") }
        }
    }

    @Test fun webCallIdentifyingSomeoneFromPrivateDataAsksFirstAndSaysWhy() = runBlocking {
        var webCalls = 0;val prompts = mutableListOf<String>()
        val registry = ToolRegistry().apply { register(inbox);register(tool("web_search") { webCalls++ }) }
        val model = Model({ "IDENTIFYING" }, listOf(ModelResponse.ToolCallResponse(listOf(ToolCall("a","read_account_inbox","{}"),search("Jordan Whitfield Maplewood lease")))))
        val session = ConversationSession(model,registry,"",confirmer=ToolConfirmer { prompts += it.prompt;false })
        session.submitUserMessage("Summarize my newest email")
        assertEquals(0,webCalls);assertEquals(1,prompts.size);assertTrue(prompts.single(),prompts.single().contains("whitfield"))
        assertFalse(session.snapshot().filterIsInstance<Message.ToolResult>().last().success)
    }
    @Test fun nonIdentifyingRecalledDetailsGoWithoutAsking() = runBlocking {
        var webCalls = 0;var asked = 0
        val registry = ToolRegistry().apply { register(tool("web_search") { webCalls++ }) }
        val model = Model({ "OK" }, listOf(ModelResponse.ToolCallResponse(listOf(search("weather Plano today")))))
        val session = ConversationSession(model,registry,memories,confirmer=ToolConfirmer { asked++;true })
        session.submitUserMessage("What's the weather today?")
        assertEquals(0,asked);assertEquals(1,webCalls);assertEquals(1,model.judged)
    }
    @Test fun theUsersOwnWordsNeedNoCheck() = runBlocking {
        var webCalls = 0;var asked = 0
        val registry = ToolRegistry().apply { register(tool("web_search") { webCalls++ }) }
        val model = Model({ "IDENTIFYING" }, listOf(ModelResponse.ToolCallResponse(listOf(search("Apple CEO 2026")))))
        val session = ConversationSession(model,registry,memories,confirmer=ToolConfirmer { asked++;true })
        session.submitUserMessage("Who is the CEO of Apple?")
        assertEquals(0,asked);assertEquals(1,webCalls);assertEquals(0,model.judged)
    }
    @Test fun contactDetailsAlwaysAskWithoutTheCheck() = runBlocking {
        val prompts = mutableListOf<String>()
        val registry = ToolRegistry().apply { register(inbox);register(tool("web_search") { }) }
        val model = Model({ "OK" }, listOf(ModelResponse.ToolCallResponse(listOf(ToolCall("a","read_account_inbox","{}"),search("jordan@example.org")))))
        val session = ConversationSession(model,registry,"",confirmer=ToolConfirmer { prompts += it.prompt;true })
        session.submitUserMessage("Who sent my newest email?")
        assertEquals(1,prompts.size);assertTrue(prompts.single().contains("jordan@example.org"));assertEquals(0,model.judged)
    }
    @Test fun aFailedCheckAsks() = runBlocking {
        var asked = 0
        val registry = ToolRegistry().apply { register(tool("web_search") { }) }
        val model = Model({ throw ModelProviderException("offline") }, listOf(ModelResponse.ToolCallResponse(listOf(search("weather Plano today")))))
        val session = ConversationSession(model,registry,memories,confirmer=ToolConfirmer { asked++;true })
        session.submitUserMessage("What's the weather today?")
        assertEquals(1,asked)
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
        var calls = 0;var consent = 0
        val registry = ToolRegistry().apply { register(tool("fetch_page") { calls++ }) }
        val model = Model({ "IDENTIFYING" }, listOf(
            ModelResponse.ToolCallResponse(listOf(ToolCall("a","fetch_page",buildJsonObject { put("url","https://example.com/search?q=Whitfield") }.toString()))),
            ModelResponse.ToolCallResponse(listOf(ToolCall("b","fetch_page",buildJsonObject { put("url","https://www.iana.org/help/example-domains") }.toString()))),
        ))
        val session = ConversationSession(model,registry,"",initialMessages=listOf(Message.User("Earlier private read"),Message.ToolResult("old","read_daily_brief","Lunch with Dana Whitfield at noon",true)),confirmer=ToolConfirmer { consent++;true })
        session.submitUserMessage("Use a public source now")
        assertEquals(1,consent);assertEquals(2,calls)
    }
}
