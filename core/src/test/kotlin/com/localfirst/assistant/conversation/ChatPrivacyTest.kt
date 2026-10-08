package com.localfirst.assistant.conversation

import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.ModelResponse
import com.localfirst.assistant.tools.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class ChatPrivacyTest {
    @Test fun incognitoCheckpointsCannotSaveEvenAfterAnAssistantReply() = runBlocking {
        val directory = Files.createTempDirectory("privacy-test").toFile()
        try {
            val store = FileConversationStore(directory)
            val privacy = ChatPrivacy(incognito = true)
            val provider = object : ModelProvider {
                override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>) = ModelResponse.TextResponse("Private reply")
            }
            var writes = 0
            val session = ConversationSession(provider, ToolRegistry(), "", checkpoint = {
                privacy.checkpoint { writes++; error("Incognito must never reach disk") }
            })
            session.submitUserMessage("Private question")
            assertEquals("Private reply", (session.snapshot().last() as Message.Assistant).content)
            assertEquals(0, writes)
            assertTrue(store.list().isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun incognitoRefusesRememberAndSchedulingEvenIfModelCallsThem() = runBlocking {
        var executed = 0
        val privacy = ChatPrivacy(incognito = true)
        val registry = ToolRegistry()
        listOf("remember", "schedule_task", "search_memory").forEach { name ->
            val tool = object : Tool {
                override val name = name
                override val description = "test"
                override val inputSchema = buildJsonObject { }
                override suspend fun execute(arguments: JsonObject): ToolExecutionResult { executed++; return ToolExecutionResult(true, "test") }
            }
            if (privacy.allowsTool(name)) registry.register(tool)
        }
        assertFalse(registry.execute(ToolCall("a", "remember", "{}")).success)
        assertFalse(registry.execute(ToolCall("b", "schedule_task", "{}")).success)
        assertTrue(registry.execute(ToolCall("c", "search_memory", "{}")).success)
        assertEquals(1, executed)
        assertFalse(privacy.copy(freshSlate = true).allowsTool("search_memory"))
        assertFalse(privacy.copy(freshSlate = true).recall)
        assertFalse(privacy.allowsTool("propose_agent_task"))
        assertFalse(privacy.allowsTool("list_agent_activity"))
        assertFalse(privacy.allowsTool("get_agent_report"))
        listOf("list_connected_accounts", "read_account_inbox", "read_account_message", "read_account_calendar", "read_account_calendar_event", "propose_outgoing_action").forEach { assertFalse(privacy.allowsTool(it)) }
    }
    @Test fun normalChatStillCheckpoints() = runBlocking {
        var writes = 0; ChatPrivacy().checkpoint { writes++ }; assertEquals(1, writes)
    }
}
