package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.ModelProviderException
import com.localfirst.assistant.model.ModelResponse
import com.localfirst.assistant.tools.SetMediaVolumeTool
import com.localfirst.assistant.tools.Tool
import com.localfirst.assistant.tools.ToolCall
import com.localfirst.assistant.tools.ToolDefinition
import com.localfirst.assistant.tools.ToolExecutionResult
import com.localfirst.assistant.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSessionTest {
    @Test
    fun multiTurnTextKeepsContextAndHidesSystemPrompt() = runBlocking {
        val provider = ScriptedModelProvider(
            { ModelResponse.TextResponse("Hello") },
            { ModelResponse.TextResponse("Still here") },
        )
        val session = session(provider, registry = ToolRegistry())

        val first = session.submitUserMessage("Hi")
        val second = session.submitUserMessage("Are you there?")

        assertTrue(first is TurnOutcome.Completed)
        assertTrue(second is TurnOutcome.Completed)
        assertEquals(
            listOf(
                Message.User("Hi"),
                Message.Assistant("Hello"),
                Message.User("Are you there?"),
                Message.Assistant("Still here"),
            ),
            session.snapshot(),
        )
        assertFalse(session.snapshot().any { it is Message.System })
        assertTrue(provider.requests[1].first() is Message.System)
        assertEquals(
            listOf(
                Message.System("Be brief."),
                Message.User("Hi"),
                Message.Assistant("Hello"),
                Message.User("Are you there?"),
            ),
            provider.requests[1],
        )
    }

    @Test
    fun volumeToolCallReturnsResultToTheSameSession() = runBlocking {
        val levels = mutableListOf<Int>()
        val registry = ToolRegistry().apply {
            register(SetMediaVolumeTool { level -> levels += level })
        }
        val provider = ScriptedModelProvider(
            {
                ModelResponse.ToolCallResponse(
                    text = "Setting it.",
                    calls = listOf(
                        ToolCall("a", "set_media_volume", """{"level":30}"""),
                        ToolCall("b", "set_media_volume", """{"level":40}"""),
                    ),
                )
            },
            { ModelResponse.TextResponse("Done.") },
            { ModelResponse.TextResponse("It is at 40%.") },
        )
        val session = session(provider, registry)

        val outcome = session.submitUserMessage("Set the volume to 30%.")
        assertTrue(outcome is TurnOutcome.Completed)
        assertEquals(listOf(30, 40), levels)

        val history = session.snapshot()
        assertEquals(
            listOf(
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.TOOL_CALL,
                MessageRole.TOOL_CALL,
                MessageRole.TOOL_RESULT,
                MessageRole.TOOL_RESULT,
                MessageRole.ASSISTANT,
            ),
            history.map { it.role },
        )
        val firstResult = history[4] as Message.ToolResult
        assertTrue(firstResult.success)
        assertEquals("a", firstResult.toolCallId)
        assertFalse(history.any { it is Message.User && it.content.contains("Media volume") })

        session.submitUserMessage("What did you change?")
        assertEquals("It is at 40%.", (session.snapshot().last() as Message.Assistant).content)
        assertTrue(provider.requests.last().any { it is Message.ToolResult })
        assertEquals(listOf("set_media_volume"), provider.toolNames.first())
    }

    @Test
    fun unknownToolAndBadArgumentsStayInTheConversation() = runBlocking {
        var executed = false
        val registry = ToolRegistry().apply {
            register(SetMediaVolumeTool { executed = true })
        }
        val provider = ScriptedModelProvider(
            {
                ModelResponse.ToolCallResponse(
                    calls = listOf(ToolCall("1", "launch_app", """{"name":"maps"}""")),
                )
            },
            { ModelResponse.TextResponse("I can't launch apps.") },
        )
        val session = session(provider, registry)
        val outcome = session.submitUserMessage("Open maps")

        assertTrue(outcome is TurnOutcome.Completed)
        assertFalse(executed)
        val result = session.snapshot().filterIsInstance<Message.ToolResult>().single()
        assertFalse(result.success)
        assertTrue(result.content.contains("Unknown tool"))
        assertEquals("I can't launch apps.", (session.snapshot().last() as Message.Assistant).content)

        val invalid = ScriptedModelProvider(
            {
                ModelResponse.ToolCallResponse(
                    calls = listOf(ToolCall("1", "set_media_volume", "not-json")),
                )
            },
            { ModelResponse.TextResponse("That level was unusable.") },
        )
        val retrySession = session(invalid, registry)
        assertTrue(retrySession.submitUserMessage("volume") is TurnOutcome.Completed)
        assertFalse(executed)
        val bad = retrySession.snapshot().filterIsInstance<Message.ToolResult>().single()
        assertFalse(bad.success)
        assertEquals("Tool arguments must be a JSON object.", bad.content)
    }

    @Test
    fun toolFailureAndConfirmationDoNotWipeHistory() = runBlocking {
        val registry = ToolRegistry().apply {
            register(SetMediaVolumeTool { error("device busy") })
            register(ConfirmTool())
        }
        val provider = ScriptedModelProvider(
            {
                ModelResponse.ToolCallResponse(
                    calls = listOf(
                        ToolCall("1", "set_media_volume", """{"level":10}"""),
                        ToolCall("2", "place_call", """{"number":"0"}"""),
                    ),
                )
            },
            { ModelResponse.TextResponse("I could not do that.") },
        )
        val session = session(provider, registry)
        val outcome = session.submitUserMessage("Please try")

        assertTrue(outcome is TurnOutcome.Completed)
        val results = session.snapshot().filterIsInstance<Message.ToolResult>()
        assertEquals(2, results.size)
        assertTrue(results[0].content.contains("device busy"))
        assertTrue(results[1].content.contains("requires confirmation"))
        assertFalse((registry.getAvailableTools().single { it.name == "place_call" } as ConfirmTool).executed)
        assertEquals(Message.User("Please try"), session.snapshot().first())
    }

    @Test
    fun providerFailureKeepsTheUserMessageAndRetryDoesNotDuplicateIt() = runBlocking {
        val provider = ScriptedModelProvider(
            { throw ModelProviderException("Can't reach the model server.") },
            { ModelResponse.TextResponse("Recovered") },
        )
        val session = session(provider, ToolRegistry())
        val failed = session.submitUserMessage("Hello")

        assertTrue(failed is TurnOutcome.Failed)
        assertEquals("Can't reach the model server.", (failed as TurnOutcome.Failed).error)
        assertEquals(listOf(Message.User("Hello")), session.snapshot())

        val recovered = session.retry()
        assertTrue(recovered is TurnOutcome.Completed)
        assertEquals(
            listOf(Message.User("Hello"), Message.Assistant("Recovered")),
            session.snapshot(),
        )
        assertEquals(1, provider.requests[1].count { it is Message.User })
    }

    @Test
    fun toolLimitEndsWithAnAnswerWithoutTools() = runBlocking {
        var runs = 0
        val registry = ToolRegistry().apply {
            register(SetMediaVolumeTool { runs += 1 })
        }
        val provider = ScriptedModelProvider(
            { ModelResponse.ToolCallResponse(calls = listOf(call("1", 10))) },
            { ModelResponse.ToolCallResponse(calls = listOf(call("2", 20))) },
            { ModelResponse.ToolCallResponse(calls = listOf(call("3", 30))) },
            { messages ->
                assertEquals(ConversationEngine.TOOL_LIMIT_NOTE, (messages.last() as Message.User).content)
                ModelResponse.TextResponse("Volume is at 20%.")
            },
        )
        val session = ConversationSession(
            modelProvider = provider,
            toolRegistry = registry,
            systemPrompt = "Be brief.",
            engine = ConversationEngine(maxToolRounds = 2),
        )
        val outcome = session.submitUserMessage("Keep going")

        assertTrue(outcome is TurnOutcome.Completed)
        assertEquals(2, runs)
        assertEquals(emptyList<String>(), provider.toolNames.last())
        assertEquals(2, session.snapshot().count { it is Message.ToolResult })
        assertEquals(Message.Assistant("Volume is at 20%."), session.snapshot().last())
        assertEquals(Message.User("Keep going"), session.snapshot().first())
    }

    @Test
    fun repeatedFinishedCallGetsTheEarlierResultInstead() = runBlocking {
        var runs = 0
        val search = object : Tool {
            override val name = "web_search"
            override val description = "Search."
            override val inputSchema = JsonObject(emptyMap())
            override suspend fun execute(arguments: JsonObject): ToolExecutionResult { runs += 1; return ToolExecutionResult(true, "Canberra") }
        }
        val registry = ToolRegistry().apply { register(search) }
        val provider = ScriptedModelProvider(
            { ModelResponse.ToolCallResponse(calls = listOf(ToolCall("1", "web_search", """{"query":"capital of Australia","limit":5}"""))) },
            { ModelResponse.ToolCallResponse(calls = listOf(ToolCall("2", "web_search", """{ "limit": 5, "query": "capital of Australia" }"""))) },
            { ModelResponse.TextResponse("Canberra.") },
        )
        val outcome = session(provider, registry).submitUserMessage("Capital of Australia?")

        assertTrue(outcome is TurnOutcome.Completed)
        assertEquals(1, runs)
        val repeated = outcome.messages.filterIsInstance<Message.ToolResult>().last()
        assertEquals(ConversationEngine.REPEATED_CALL_RESULT, repeated.content)
        assertFalse(repeated.success)
    }

    @Test
    fun recallRidesOnTheLatestUserMessageAndLeavesTheSystemPromptAlone() = runBlocking {
        val provider = ScriptedModelProvider({ ModelResponse.TextResponse("Hi") })
        val session = session(provider, ToolRegistry())
        session.latestUserNote = "[Sent at 9:00 AM]"
        session.recallContext = "\n\nRetrieved personal context.\nApproved memory [m1]: Likes tea."
        session.submitUserMessage("Hello")
        val sent = provider.requests.single()
        assertEquals(Message.System("Be brief."), sent.first())
        val user = sent.last() as Message.User
        assertTrue(user.content.startsWith("Hello\n\nRetrieved personal context."))
        assertTrue(user.content.endsWith("Likes tea.\n\n[Sent at 9:00 AM]"))
        assertEquals(Message.User("Hello"), session.snapshot().first())
    }

    @Test
    fun emptyInputAndClearDoNotCallTheModel() = runBlocking {
        val provider = ScriptedModelProvider({ ModelResponse.TextResponse("Hi") })
        val session = session(provider, ToolRegistry())
        assertTrue(session.submitUserMessage("   ") is TurnOutcome.EmptyInput)
        assertTrue(provider.requests.isEmpty())
        session.submitUserMessage("Hi")
        session.clear()
        assertTrue(session.snapshot().isEmpty())
    }

    private fun session(provider: ModelProvider, registry: ToolRegistry): ConversationSession {
        return ConversationSession(
            modelProvider = provider,
            toolRegistry = registry,
            systemPrompt = "Be brief.",
        )
    }

    private fun call(id: String, level: Int = 15): ToolCall = ToolCall(id, "set_media_volume", """{"level":$level}""")

    private class ConfirmTool : Tool {
        var executed: Boolean = false
        override val name: String = "place_call"
        override val description: String = "Future sensitive tool."
        override val inputSchema: JsonObject = JsonObject(emptyMap<String, kotlinx.serialization.json.JsonElement>())
        override val requiresConfirmation: Boolean = true

        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            executed = true
            return ToolExecutionResult(true, "called")
        }
    }

    @Test
    fun toolCallMarkupIsRetriedWithoutToolsAndNeverShown() = runBlocking {
        val markup = "<tool_call>\n<function=execute_python>\n<parameter=code>\nprint(1)\n</parameter>\n</function>\n</tool_call>"
        val provider = ScriptedModelProvider(
            { ModelResponse.TextResponse(markup) },
            { messages ->
                assertEquals(ConversationEngine.TOOL_MARKUP_NOTE, (messages.last() as Message.User).content)
                ModelResponse.TextResponse("The total is 173.85.")
            },
        )
        val session = session(provider, ToolRegistry().apply { register(SetMediaVolumeTool { }) })
        val outcome = session.submitUserMessage("Sum it")
        assertTrue(outcome is TurnOutcome.Completed)
        assertEquals(emptyList<String>(), provider.toolNames.last())
        assertEquals(listOf(Message.User("Sum it"), Message.Assistant("The total is 173.85.")), session.snapshot())
    }

    @Test
    fun repeatedMarkupEndsInAPlainNote() = runBlocking {
        val provider = ScriptedModelProvider(
            { ModelResponse.TextResponse("<tool_call><function=web_search></function></tool_call>") },
            { ModelResponse.TextResponse("<tool_call><function=web_search></function></tool_call>") },
        )
        val outcome = session(provider, ToolRegistry()).submitUserMessage("Look it up")
        val last = (outcome as TurnOutcome.Completed).messages.last() as Message.Assistant
        assertEquals("I couldn't finish writing the answer.", last.content)
    }

    private class ScriptedModelProvider(
        vararg responders: (List<Message>) -> ModelResponse,
    ) : ModelProvider {
        private val steps = responders.toMutableList()
        val requests = mutableListOf<List<Message>>()
        val toolNames = mutableListOf<List<String>>()

        override suspend fun sendConversation(
            messages: List<Message>,
            tools: List<ToolDefinition>,
        ): ModelResponse {
            requests += messages
            toolNames += tools.map { it.name }
            check(steps.isNotEmpty()) { "Unexpected model request." }
            return steps.removeAt(0)(messages)
        }
    }
}
