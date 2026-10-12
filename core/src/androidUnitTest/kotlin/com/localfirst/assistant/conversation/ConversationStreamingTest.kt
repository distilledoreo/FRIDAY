package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.ModelResponse
import com.localfirst.assistant.tools.SourceLink
import com.localfirst.assistant.tools.Tool
import com.localfirst.assistant.tools.ToolCall
import com.localfirst.assistant.tools.ToolDefinition
import com.localfirst.assistant.tools.ToolExecutionResult
import com.localfirst.assistant.tools.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStreamingTest {
    @Test
    fun streamedTextAppearsAsAGrowingAssistantMessage() = runBlocking {
        val provider = StreamingProvider(listOf(listOf("Hel", "lo") to ModelResponse.TextResponse("Hello")))
        val updates = mutableListOf<List<Message>>()
        val session = ConversationSession(provider, ToolRegistry(), systemPrompt = "")

        session.submitUserMessage("Hi") { updates += it }

        val partials = updates.mapNotNull { (it.lastOrNull() as? Message.Assistant)?.content }
        assertEquals(listOf("Hel", "Hello", "Hello"), partials)
        assertEquals(listOf(Message.User("Hi"), Message.Assistant("Hello")), session.snapshot())
    }

    @Test
    fun stoppingMidAnswerKeepsWhatArrived() = runBlocking {
        val firstDelta = CompletableDeferred<Unit>()
        val provider = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>) =
                error("not used")

            override suspend fun streamConversation(
                messages: List<Message>,
                tools: List<ToolDefinition>,
                onTextDelta: (String) -> Unit,
            ): ModelResponse {
                onTextDelta("The answer is")
                firstDelta.complete(Unit)
                awaitCancellation()
            }
        }
        val session = ConversationSession(provider, ToolRegistry(), systemPrompt = "")
        val turn = async(Dispatchers.Default) { session.submitUserMessage("Question?") }
        withTimeout(5_000) { firstDelta.await() }
        turn.cancel()
        runCatching { turn.await() }

        assertEquals(
            listOf(Message.User("Question?"), Message.Assistant("The answer is")),
            session.snapshot(),
        )
    }

    @Test
    fun stoppingDuringAToolAnswersEveryPendingCall() = runBlocking {
        val toolStarted = CompletableDeferred<Unit>()
        val slowTool = object : Tool {
            override val name = "slow"
            override val description = "Never finishes."
            override val inputSchema = buildJsonObject { }
            override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
                toolStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val provider = StreamingProvider(
            listOf(
                emptyList<String>() to ModelResponse.ToolCallResponse(
                    calls = listOf(ToolCall("a", "slow", "{}"), ToolCall("b", "slow", "{}")),
                ),
            ),
        )
        val session = ConversationSession(provider, ToolRegistry().apply { register(slowTool) }, systemPrompt = "")
        val turn = async(Dispatchers.Default) { session.submitUserMessage("Go") }
        withTimeout(5_000) { toolStarted.await() }
        turn.cancel()
        runCatching { turn.await() }

        val results = session.snapshot().filterIsInstance<Message.ToolResult>()
        assertEquals(listOf("a", "b"), results.map { it.toolCallId })
        assertTrue(results.all { !it.success && it.content == ConversationEngine.STOPPED_RESULT })
    }

    @Test
    fun toolSourcesAreKeptOnTheResultMessage() = runBlocking {
        val sourceTool = object : Tool {
            override val name = "web_search"
            override val description = "Search."
            override val inputSchema = buildJsonObject { }
            override suspend fun execute(arguments: JsonObject) = ToolExecutionResult(
                success = true,
                content = "1. Example",
                sources = listOf(SourceLink("Example", "https://example.com")),
            )
        }
        val provider = StreamingProvider(
            listOf(
                emptyList<String>() to ModelResponse.ToolCallResponse(listOf(ToolCall("s", "web_search", "{}"))),
                listOf("Found it.") to ModelResponse.TextResponse("Found it."),
            ),
        )
        val session = ConversationSession(provider, ToolRegistry().apply { register(sourceTool) }, systemPrompt = "")
        session.submitUserMessage("Search")

        val result = session.snapshot().filterIsInstance<Message.ToolResult>().single()
        assertEquals(listOf(SourceLink("Example", "https://example.com")), result.sources)
    }

    @Test
    fun regenerateReplacesTheLastAnswer() = runBlocking {
        val provider = StreamingProvider(
            listOf(
                listOf("First") to ModelResponse.TextResponse("First"),
                listOf("Second") to ModelResponse.TextResponse("Second"),
            ),
        )
        val session = ConversationSession(provider, ToolRegistry(), systemPrompt = "")
        session.submitUserMessage("Hi")
        val outcome = session.regenerate()

        assertTrue(outcome is TurnOutcome.Completed)
        assertEquals(listOf(Message.User("Hi"), Message.Assistant("Second")), session.snapshot())
        assertEquals(listOf(Message.User("Hi")), provider.requests[1])
    }

    @Test
    fun editingAUserMessageDropsEverythingAfterIt() = runBlocking {
        val provider = StreamingProvider(
            listOf(
                listOf("A1") to ModelResponse.TextResponse("A1"),
                listOf("A2") to ModelResponse.TextResponse("A2"),
                listOf("A1 edited") to ModelResponse.TextResponse("A1 edited"),
            ),
        )
        val session = ConversationSession(provider, ToolRegistry(), systemPrompt = "")
        session.submitUserMessage("Q1")
        session.submitUserMessage("Q2")
        session.editUserMessage(index = 0, text = "Q1 better")

        assertEquals(
            listOf(Message.User("Q1 better"), Message.Assistant("A1 edited")),
            session.snapshot(),
        )
        val notUser = runCatching { session.editUserMessage(index = 1, text = "x") }.exceptionOrNull()
        assertTrue(notUser is IllegalArgumentException)
    }

    @Test
    fun aSessionCanResumeFromSavedMessages() = runBlocking {
        val provider = StreamingProvider(listOf(listOf("Yes") to ModelResponse.TextResponse("Yes")))
        val saved = listOf(Message.User("Earlier"), Message.Assistant("Reply"))
        val session = ConversationSession(provider, ToolRegistry(), systemPrompt = "", initialMessages = saved)
        session.submitUserMessage("Still there?")

        assertEquals(saved + Message.User("Still there?"), provider.requests.single())
    }

    @Test
    fun leakedThinkingMarkupIsHidden() = runBlocking {
        assertEquals("Answer.", ConversationEngine.visibleText("Draft answer. </think>\n\nAnswer."))
        assertEquals("Visible", ConversationEngine.visibleText("Visible <think>hidden so far"))
        assertEquals("Plain", ConversationEngine.visibleText("  Plain "))

        val provider = StreamingProvider(
            listOf(listOf("Nothing tomorrow. ", "</think>", " Nothing on tomorrow.") to ModelResponse.TextResponse("Nothing tomorrow. </think> Nothing on tomorrow.")),
        )
        val updates = mutableListOf<String>()
        val session = ConversationSession(provider, ToolRegistry(), systemPrompt = "")
        session.submitUserMessage("Tomorrow?") { m -> (m.lastOrNull() as? Message.Assistant)?.let { updates += it.content } }
        assertEquals(Message.Assistant("Nothing on tomorrow."), session.snapshot().last())
        assertTrue(updates.last() == "Nothing on tomorrow.")
    }

    @Test
    fun theTimeNoteGoesOnTheLatestUserMessageOnlyAndIsNotStored() = runBlocking {
        val provider = StreamingProvider(
            listOf(
                listOf("A") to ModelResponse.TextResponse("A"),
                listOf("B") to ModelResponse.TextResponse("B"),
            ),
        )
        val session = ConversationSession(provider, ToolRegistry(), systemPrompt = "Today is Wednesday.")
        session.latestUserNote = "[Sent at 9:00 AM]"
        session.submitUserMessage("First")
        session.latestUserNote = "[Sent at 9:01 AM]"
        session.submitUserMessage("Second")

        assertEquals(Message.User("First\n\n[Sent at 9:00 AM]"), provider.requests[0][1])
        // Next turn: the system prompt and the earlier exchange are unchanged except
        // that the old note is gone; only the newest message carries one.
        assertEquals(
            listOf(
                Message.System("Today is Wednesday."),
                Message.User("First"),
                Message.Assistant("A"),
                Message.User("Second\n\n[Sent at 9:01 AM]"),
            ),
            provider.requests[1],
        )
        assertEquals(listOf(Message.User("First"), Message.Assistant("A"), Message.User("Second"), Message.Assistant("B")), session.snapshot())
    }

    @Test
    fun repeatedIdenticalPhoneActionsAreRefusedInsteadOfTappingForever() = runBlocking {
        var executed = 0
        val tapTool = object : Tool {
            override val name = "control_phone_screen"
            override val description = "Tap the screen."
            override val inputSchema = buildJsonObject { }
            override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
                executed += 1
                return ToolExecutionResult(success = true, content = "tap $executed")
            }
        }
        val tapJson = """{"action":"tap","x":540,"y":1200,"screen_width":1080,"screen_height":2400}"""
        val provider = StreamingProvider(
            listOf(
                listOf("Tapping.") to ModelResponse.ToolCallResponse(listOf(ToolCall("t1", "control_phone_screen", tapJson))),
                listOf("Tapping again.") to ModelResponse.ToolCallResponse(listOf(ToolCall("t2", "control_phone_screen", tapJson))),
                listOf("Once more.") to ModelResponse.ToolCallResponse(listOf(ToolCall("t3", "control_phone_screen", tapJson))),
                listOf("Giving up.") to ModelResponse.TextResponse("I can't tell what changed; I'm stopping here."),
            ),
        )
        val session = ConversationSession(provider, ToolRegistry().apply { register(tapTool) }, systemPrompt = "")
        val outcome = session.submitUserMessage("Open settings for me")

        assertTrue(outcome is TurnOutcome.Completed)
        assertEquals(2, executed) // first attempt plus one retry; the third is refused engine-side
        val results = session.snapshot().filterIsInstance<Message.ToolResult>()
        assertEquals(3, results.size)
        assertTrue(results[0].success)
        assertTrue(results[1].success)
        assertFalse(results[2].success)
        assertTrue(results[2].content.contains("without checking the screen between attempts"))
    }

    @Test
    fun identicalCaptureCallsAreNeverRepeatGuarded() = runBlocking {
        // capture_phone_screen takes no arguments, so every capture looks "identical" — but
        // captures are observations, and refusing them strands the model with no way to verify.
        var executed = 0
        val captureTool = object : Tool {
            override val name = "capture_phone_screen"
            override val description = "Capture."
            override val inputSchema = buildJsonObject { }
            override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
                executed += 1
                return ToolExecutionResult(success = true, content = "captured $executed")
            }
        }
        val provider = StreamingProvider(
            listOf(
                listOf("One.") to ModelResponse.ToolCallResponse(listOf(ToolCall("c1", "capture_phone_screen", "{}"))),
                listOf("Two.") to ModelResponse.ToolCallResponse(listOf(ToolCall("c2", "capture_phone_screen", "{}"))),
                listOf("Three.") to ModelResponse.ToolCallResponse(listOf(ToolCall("c3", "capture_phone_screen", "{}"))),
                listOf("Seen.") to ModelResponse.TextResponse("I can see it now."),
            ),
        )
        val session = ConversationSession(provider, ToolRegistry().apply { register(captureTool) }, systemPrompt = "")
        val outcome = session.submitUserMessage("What is on my screen?")

        assertTrue(outcome is TurnOutcome.Completed)
        assertEquals(3, executed)
    }

    @Test
    fun freshScreenCheckResetsTheRepeatGuard() = runBlocking {
        var taps = 0
        val tapTool = object : Tool {
            override val name = "control_phone_screen"
            override val description = "Tap the screen."
            override val inputSchema = buildJsonObject { }
            override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
                taps += 1
                return ToolExecutionResult(success = true, content = "tap $taps")
            }
        }
        val readTool = object : Tool {
            override val name = "read_phone_screen"
            override val description = "Read."
            override val inputSchema = buildJsonObject { }
            override suspend fun execute(arguments: JsonObject) = ToolExecutionResult(success = true, content = "labels")
        }
        val tapJson = """{"action":"tap","x":540,"y":1200,"screen_width":1080,"screen_height":2400}"""
        fun tap(id: String) = ModelResponse.ToolCallResponse(listOf(ToolCall(id, "control_phone_screen", tapJson)))
        val provider = StreamingProvider(
            listOf(
                listOf("Tap.") to tap("t1"),
                listOf("Read.") to ModelResponse.ToolCallResponse(listOf(ToolCall("r", "read_phone_screen", "{}"))),
                listOf("Tap verified.") to tap("t2"), // allowed: a fresh check came between attempts
                listOf("Tap blind.") to tap("t3"), // refused: no fresh check since the last tap
                listOf("Done.") to ModelResponse.TextResponse("Finished."),
            ),
        )
        val session = ConversationSession(provider, ToolRegistry().apply { register(tapTool); register(readTool) }, systemPrompt = "")
        val outcome = session.submitUserMessage("Open settings for me")

        assertTrue(outcome is TurnOutcome.Completed)
        assertEquals(2, taps)
        val tapResults = session.snapshot().filterIsInstance<Message.ToolResult>().filter { it.name == "control_phone_screen" }
        assertEquals(3, tapResults.size)
        assertTrue(tapResults[0].success)
        assertTrue(tapResults[1].success)
        assertFalse(tapResults[2].success)
    }

    @Test
    fun identicalActionsHitAHardCapEvenWithFreshChecks() = runBlocking {
        var taps = 0
        val tapTool = object : Tool {
            override val name = "control_phone_screen"
            override val description = "Tap the screen."
            override val inputSchema = buildJsonObject { }
            override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
                taps += 1
                return ToolExecutionResult(success = true, content = "tap $taps")
            }
        }
        val readTool = object : Tool {
            override val name = "read_phone_screen"
            override val description = "Read."
            override val inputSchema = buildJsonObject { }
            override suspend fun execute(arguments: JsonObject) = ToolExecutionResult(success = true, content = "labels")
        }
        val tapJson = """{"action":"tap","x":1,"y":2,"screen_width":100,"screen_height":100}"""
        // Alternate tap/read so every tap follows a fresh check; the cap must still stop the loop.
        val script = mutableListOf<Pair<List<String>, ModelResponse>>()
        repeat(ConversationEngine.MAX_IDENTICAL_PHONE_ACTIONS + 1) { i ->
            script += listOf("Tap.") to ModelResponse.ToolCallResponse(listOf(ToolCall("t$i", "control_phone_screen", tapJson)))
            script += listOf("Read.") to ModelResponse.ToolCallResponse(listOf(ToolCall("r$i", "read_phone_screen", "{}")))
        }
        script += listOf("Done.") to ModelResponse.TextResponse("Finished.")
        val provider = StreamingProvider(script)
        val session = ConversationSession(provider, ToolRegistry().apply { register(tapTool); register(readTool) }, systemPrompt = "")
        val outcome = session.submitUserMessage("Keep tapping")

        assertTrue(outcome is TurnOutcome.Completed)
        assertEquals(ConversationEngine.MAX_IDENTICAL_PHONE_ACTIONS, taps)
        val refused = session.snapshot().filterIsInstance<Message.ToolResult>()
            .filter { it.name == "control_phone_screen" && !it.success }
            .single()
        assertTrue(refused.content.contains("tried this exact action"))
    }

    @Test
    fun duplicatePhoneActionsInsideOneResponseAreRefused() = runBlocking {
        var executed = 0
        val tapTool = object : Tool {
            override val name = "control_phone_screen"
            override val description = "Tap the screen."
            override val inputSchema = buildJsonObject { }
            override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
                executed += 1
                return ToolExecutionResult(success = true, content = "tap $executed")
            }
        }
        val tapJson = """{"action":"tap","x":10,"y":20,"screen_width":100,"screen_height":100}"""
        val provider = StreamingProvider(
            listOf(
                listOf("First.") to ModelResponse.ToolCallResponse(listOf(
                    ToolCall("a", "control_phone_screen", tapJson),
                    ToolCall("b", "control_phone_screen", tapJson),
                )),
                listOf("Done.") to ModelResponse.TextResponse("Interesting."),
            ),
        )
        val session = ConversationSession(provider, ToolRegistry().apply { register(tapTool) }, systemPrompt = "")
        val outcome = session.submitUserMessage("Open settings for me")

        assertTrue(outcome is TurnOutcome.Completed)
        assertEquals(1, executed) // only the first of the two identical calls tapped
        val results = session.snapshot().filterIsInstance<Message.ToolResult>()
        assertEquals(2, results.size)
        assertTrue(results[0].success)
        assertFalse(results[1].success)
    }

    private class StreamingProvider(
        script: List<Pair<List<String>, ModelResponse>>,
    ) : ModelProvider {
        private val steps = script.toMutableList()
        val requests = mutableListOf<List<Message>>()

        override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>) =
            error("streaming expected")

        override suspend fun streamConversation(
            messages: List<Message>,
            tools: List<ToolDefinition>,
            onTextDelta: (String) -> Unit,
        ): ModelResponse {
            requests += messages
            val (deltas, response) = steps.removeAt(0)
            deltas.forEach(onTextDelta)
            return response
        }
    }
}
