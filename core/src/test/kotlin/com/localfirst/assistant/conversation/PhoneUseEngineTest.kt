package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.ModelResponse
import com.localfirst.assistant.tools.Tool
import com.localfirst.assistant.tools.ToolCall
import com.localfirst.assistant.tools.ToolDefinition
import com.localfirst.assistant.tools.ToolExecutionResult
import com.localfirst.assistant.tools.ToolRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the conversation engine carries phone-use screenshots and long phone-use turns. */
class PhoneUseEngineTest {
    private class Scripted(private val next: (Int) -> ModelResponse) : ModelProvider {
        val requests = mutableListOf<List<Message>>()
        override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse {
            requests += messages
            return next(requests.size - 1)
        }
    }

    private class StateTool(override val name: String, private val shot: () -> File?) : Tool {
        var runs = 0
        override val description = name
        override val inputSchema = buildJsonObject { }
        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            runs++
            val file = shot()
            return ToolExecutionResult(
                true,
                "Clicked thing $runs.\n\nApp: Gmail (com.google.android.gm)\nWindow: Inbox\nCoordinates: 576x1280\n\n" + "0 window Inbox\n".repeat(50),
                images = listOfNotNull(file?.let { Attachment("a$runs", AttachmentKind.IMAGE, "Phone", "image/jpeg", path = it.path) }),
            )
        }
    }

    @Test
    fun aResultScreenshotIsSentOnceAfterItsResultAndKeptForTheTranscript() = runBlocking {
        val shot = File.createTempFile("state", ".jpg").apply { writeText("jpeg") }
        val tool = StateTool("get_app_state") { shot }
        val provider = Scripted { i ->
            if (i == 0) ModelResponse.ToolCallResponse(listOf(ToolCall("g", "get_app_state", """{"app":"Gmail"}""")))
            else ModelResponse.TextResponse("Your inbox is open.")
        }
        val session = ConversationSession(provider, ToolRegistry().apply { register(tool) }, systemPrompt = "")
        session.submitUserMessage("Open Gmail")

        val second = provider.requests[1]
        val image = second.last() as Message.User
        assertTrue(image.content.startsWith("Screenshot for the phone-use result above"))
        assertEquals(shot.path, image.attachments.single().path)
        // Kept on disk and on the stored result for the transcript; never stored as a user message.
        assertTrue(shot.exists())
        val result = session.snapshot().filterIsInstance<Message.ToolResult>().single()
        assertEquals(shot.path, result.image)
        assertEquals(listOf("Open Gmail"), session.snapshot().filterIsInstance<Message.User>().map { it.content })
        shot.delete()
        Unit
    }

    @Test
    fun phoneUseTurnsMayRunPastTheOrdinaryToolRoundLimit() = runBlocking {
        val tool = StateTool("click") { null }
        val provider = Scripted { i ->
            if (i < 40) ModelResponse.ToolCallResponse(listOf(ToolCall("c$i", "click", """{"app":"Gmail","element_index":$i}""")))
            else ModelResponse.TextResponse("Done after many steps.")
        }
        val session = ConversationSession(provider, ToolRegistry().apply { register(tool) }, systemPrompt = "")
        val outcome = session.submitUserMessage("Clean up my inbox")
        assertTrue(outcome is TurnOutcome.Completed)
        assertEquals(40, tool.runs)
    }

    @Test
    fun olderPhoneStatesAreShortenedInBatches() = runBlocking {
        val tool = StateTool("click") { null }
        val provider = Scripted { i ->
            if (i < 6) ModelResponse.ToolCallResponse(listOf(ToolCall("c$i", "click", """{"app":"Gmail","element_index":$i}""")))
            else ModelResponse.TextResponse("Done.")
        }
        val session = ConversationSession(provider, ToolRegistry().apply { register(tool) }, systemPrompt = "")
        session.submitUserMessage("Do six things")

        fun results(request: List<Message>) = request.filterIsInstance<Message.ToolResult>()
        // Up to four full states: nothing shortened yet.
        assertTrue(results(provider.requests[4]).all { it.content.contains("0 window Inbox") })
        // The fifth state shortens the first four at once, and the prefix then stays the same.
        val fifth = results(provider.requests[5])
        assertEquals(
            "Clicked thing 1.\nApp: Gmail (com.google.android.gm)\nWindow: Inbox\n[Older screen state omitted; its element indices no longer apply.]",
            fifth[0].content,
        )
        assertTrue(fifth.take(4).none { it.content.contains("0 window Inbox") })
        assertTrue(fifth[4].content.contains("0 window Inbox"))
        assertEquals(fifth.take(4), results(provider.requests[6]).take(4))
        // The stored history keeps the full results.
        assertTrue(session.snapshot().filterIsInstance<Message.ToolResult>().all { it.content.contains("0 window Inbox") })
    }

    @Test
    fun onlyOneScreenActionRunsPerResponse() = runBlocking {
        val click = StateTool("click") { null }
        val type = StateTool("type_text") { null }
        val provider = Scripted { i ->
            if (i == 0) ModelResponse.ToolCallResponse(listOf(
                ToolCall("a", "click", """{"app":"Gmail","element_index":1}"""),
                ToolCall("b", "type_text", """{"app":"Gmail","text":"hi"}"""),
            )) else ModelResponse.TextResponse("ok")
        }
        val session = ConversationSession(provider, ToolRegistry().apply { register(click); register(type) }, systemPrompt = "")
        session.submitUserMessage("Reply hi")
        assertEquals(1, click.runs)
        assertEquals(0, type.runs)
        val refused = session.snapshot().filterIsInstance<Message.ToolResult>().last()
        assertFalse(refused.success)
        assertTrue(refused.content.contains("only one phone UI action per response"))
    }
    @Test
    fun screenshotsKeepTheirPositionUntilBatchCompaction() = runBlocking {
        val shots = mutableListOf<File>()
        try {
            val tool = StateTool("click") {
                File.createTempFile("state", ".png").apply { writeText("png"); shots += this }
            }
            val provider = Scripted { i ->
                if (i < 5) ModelResponse.ToolCallResponse(listOf(ToolCall("c$i", "click", """{"app":"Gmail","element_index":$i}""")))
                else ModelResponse.TextResponse("Done.")
            }
            val session = ConversationSession(provider, ToolRegistry().apply { register(tool) }, systemPrompt = "")
            session.submitUserMessage("Do five things")
            val second = provider.requests[1]
            assertEquals(second, provider.requests[2].take(second.size))
            assertEquals(second, provider.requests[4].take(second.size))
            val images = provider.requests[5].filterIsInstance<Message.User>().flatMap { it.attachments }
            assertEquals(listOf(shots.last().path), images.map { it.path })
            assertTrue(shots.all { it.exists() })
        } finally {
            shots.forEach { it.delete() }
        }
    }

}
