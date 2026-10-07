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

    /** Replays scripted (deltas, final response) pairs. */
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
