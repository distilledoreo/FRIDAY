package com.localfirst.assistant.tools

import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ImageGenerationToolTest {
    @Test fun checkpointsAndReturnsImageToSameConversation() = runBlocking {
        var submissions = 0
        val gateway = object : WorkspaceGateway {
            override suspend fun toolRequest(path: String, method: String, body: String?): String {
                if (method == "POST") submissions++
                return """{"status":"completed","artifact":{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}"""
            }
        }
        var rounds = 0
        val provider = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse {
                return if (rounds++ == 0) ModelResponse.ToolCallResponse(calls = listOf(ToolCall("image", "generate_image", """{"prompt":"a robot"}""")))
                else {
                    assertTrue(messages.any { it is Message.User && it.content == "Remember blue; generate a robot" })
                    assertTrue(messages.any { it is Message.ToolResult && it.success })
                    ModelResponse.TextResponse("Your blue robot is ready")
                }
            }
        }
        val saved = mutableListOf<List<Message>>()
        lateinit var session: ConversationSession
        session = ConversationSession(provider, ToolRegistry().apply { register(ImageGenerationTool(gateway) {}) }, "",
            checkpoint = { saved += session.snapshot() })
        assertTrue(session.submitUserMessage("Remember blue; generate a robot") is TurnOutcome.Completed)
        assertEquals(1, submissions)
        assertEquals(2, saved.size)
        assertTrue(saved.first().last() is Message.ToolCall)
        assertTrue(saved.last().last() is Message.ToolResult)
    }

    @Test fun cancellationRequestsServerCancelOnce() = runBlocking {
        val calls = mutableListOf<String>()
        val gateway = object : WorkspaceGateway {
            override suspend fun toolRequest(path: String, method: String, body: String?): String {
                calls += "$method $path"
                return """{"status":"running"}"""
            }
        }
        try { ImageGenerationTool(gateway) { throw CancellationException() }.execute(buildJsonObject { put("prompt", "robot") }) }
        catch (_: CancellationException) { }
        assertEquals(1, calls.count { it.endsWith("/cancel") })
        assertEquals(1, calls.count { it == "POST /workspace/images" })
    }
}
