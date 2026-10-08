package com.localfirst.assistant.tools

import com.localfirst.assistant.conversation.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceToolsTest {
    @Test fun schemasAreValidAndPersistentActionsRequireApproval() {
        val dir = Files.createTempDirectory("workspace-tools").toFile()
        try {
            val tools = workspaceTools(FakeGateway(), KnowledgeStore(dir.resolve("knowledge.json")), FileConversationStore(dir.resolve("chats")))
            assertFalse(tools.any { it.name.contains("approve") })
            tools.forEach { assertEquals("object", it.inputSchema["type"]?.jsonPrimitive?.content) }
            assertTrue(tools.first { it.name == "remember" }.requiresConfirmation)
            assertTrue(tools.first { it.name == "schedule_task" }.requiresConfirmation)
            assertFalse(tools.first { it.name == "execute_python" }.requiresConfirmation)
        } finally { dir.deleteRecursively() }
    }
    @Test fun fileToolsPreserveCodeAndExplicitFileIds() = runBlocking {
        val dir = Files.createTempDirectory("workspace-tools").toFile()
        try {
            val gateway = FakeGateway()
            val tools = workspaceTools(gateway, KnowledgeStore(dir.resolve("knowledge.json")), FileConversationStore(dir.resolve("chats")))
            val args = buildJsonObject { put("code", "print(17 * 23)"); putJsonArray("file_ids") { add(JsonPrimitive("file1")) } }
            tools.first { it.name == "execute_python" }.execute(args)
            assertEquals("/workspace/analyze", gateway.path)
            assertEquals(args, Json.parseToJsonElement(gateway.body!!))
            tools.first { it.name == "fetch_page" }.execute(buildJsonObject { put("url", "https://example.com") })
            assertEquals(16000, Json.parseToJsonElement(gateway.body!!).jsonObject["max_chars"]!!.jsonPrimitive.int)
        } finally { dir.deleteRecursively() }
    }
    @Test fun agentToolCanOnlySaveAPlanForUserReview() = runBlocking {
        val dir = Files.createTempDirectory("agent-tools").toFile()
        try {
            val gateway = FakeGateway()
            val tools = workspaceTools(gateway, KnowledgeStore(dir.resolve("knowledge.json")), FileConversationStore(dir.resolve("chats")))
            val args = buildJsonObject { put("prompt", "Research batteries"); putJsonArray("plan") { add(JsonPrimitive("Read public sources")) }; putJsonObject("schedule") { put("run_at", 2000000000); put("interval_seconds", 86400); put("max_runs", 7); put("timezone", "America/New_York") } }
            tools.first { it.name == "propose_agent_task" }.execute(args)
            assertEquals("/workspace/agent/tasks", gateway.path)
            assertEquals(args, Json.parseToJsonElement(gateway.body!!))
            assertFalse(tools.any { it.name.contains("approve") || it.name.contains("start_agent") })
        } finally { dir.deleteRecursively() }
    }
    @Test fun reportToolReadsOnlyTheSelectedTaskAndRejectsPathInjection() = runBlocking {
        val dir = Files.createTempDirectory("agent-reports").toFile()
        try {
            val gateway = FakeGateway()
            val tool = workspaceTools(gateway, KnowledgeStore(dir.resolve("knowledge.json")), FileConversationStore(dir.resolve("chats"))).first { it.name == "get_agent_report" }
            val id = "a".repeat(32)
            tool.execute(buildJsonObject { put("id", id) })
            assertEquals("/workspace/agent/tasks/$id/report", gateway.path)
            try { tool.execute(buildJsonObject { put("id", "../approve") }); fail("Unsafe id accepted") } catch (_: IllegalArgumentException) { }
            assertEquals("/workspace/agent/tasks/$id/report", gateway.path)
        } finally { dir.deleteRecursively() }
    }
    @Test fun memoryIsSavedOnlyAfterConfirmationAndCanBeForgotten() = runBlocking {
        val dir = Files.createTempDirectory("workspace-tools").toFile()
        try {
            val knowledge = KnowledgeStore(dir.resolve("knowledge.json"))
            val gateway = FakeGateway()
            val registry = ToolRegistry().apply { workspaceTools(gateway, knowledge, FileConversationStore(dir.resolve("chats"))).forEach(::register) }
            val call = ToolCall("1", "remember", """{"text":"Prefer concise answers"}""")
            registry.execute(call, ToolConfirmer { false })
            assertTrue(knowledge.load().memories.isEmpty())
            assertEquals("", gateway.path)
            registry.execute(call, ToolConfirmer { true })
            assertEquals("/workspace/memory/memories", gateway.path)
            assertEquals("Prefer concise answers", Json.parseToJsonElement(gateway.body!!).jsonObject["text"]!!.jsonPrimitive.content)
            registry.execute(ToolCall("2", "forget_memory", """{"id":"pc-memory-id"}"""), ToolConfirmer { true })
            assertEquals("/workspace/memory/memories/pc-memory-id", gateway.path)
            assertTrue(knowledge.load().memories.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    private class FakeGateway : WorkspaceGateway {
        var path = ""; var body: String? = null
        override suspend fun toolRequest(path: String, method: String, body: String?): String {
            this.path = path; this.body = body; return "{\"success\":true}"
        }
    }
}
