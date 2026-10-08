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
    @Test fun accountToolsRequireReadApprovalAndEncodeExactScope() = runBlocking {
        val dir = Files.createTempDirectory("account-tools").toFile()
        try {
            val gateway = FakeGateway()
            val tools = workspaceTools(gateway, KnowledgeStore(dir.resolve("knowledge.json")), FileConversationStore(dir.resolve("chats")))
            val registry = ToolRegistry().apply { tools.forEach(::register) }
            val account = "a".repeat(32)
            val call = ToolCall("1", "read_account_inbox", """{"account_id":"$account","query":"project & limit=999","limit":2}""")
            registry.execute(call, ToolConfirmer { false }); assertEquals("", gateway.path)
            registry.execute(call, ToolConfirmer { true })
            assertEquals("/workspace/agent/accounts/$account/mail?query=project+%26+limit%3D999&limit=2", gateway.path)
            assertTrue(tools.filter { it.name.startsWith("read_account_") }.all { it.requiresConfirmation })
            assertFalse(tools.any { it.name.contains("send_account") || it.name.contains("account_password") || it.name.contains("approve_outgoing") })
            try { tools.first { it.name == "read_account_message" }.execute(buildJsonObject { put("account_id", account); put("message_id", "../approve") }); fail("Path injection accepted") } catch (_: IllegalArgumentException) { }
            try { tools.first { it.name == "read_account_calendar" }.execute(buildJsonObject { put("account_id", account); put("start", "2026-10-08T00:00:00Z"); put("end", "2026-12-08T00:00:00Z") }); fail("Unbounded calendar accepted") } catch (_: IllegalArgumentException) { }
            assertEquals("/workspace/agent/accounts/$account/mail?query=project+%26+limit%3D999&limit=2", gateway.path)
        } finally { dir.deleteRecursively() }
    }
    @Test fun dailyBriefAndFollowupsRequireConfirmationAndNeverActivateOrExecute() = runBlocking {
        val dir = Files.createTempDirectory("brief-tools").toFile()
        try {
            val gateway = FakeGateway()
            val tools = workspaceTools(gateway, KnowledgeStore(dir.resolve("knowledge.json")), FileConversationStore(dir.resolve("chats")))
            val registry = ToolRegistry().apply { tools.forEach(::register) }
            val call = ToolCall("brief", "read_daily_brief", "{}")
            registry.execute(call, ToolConfirmer { false }); assertEquals("", gateway.path)
            registry.execute(call, ToolConfirmer { true }); assertEquals("/workspace/agent/briefing/build", gateway.path)
            assertEquals("{}", gateway.body)
            assertTrue(tools.filter { it.name in listOf("read_daily_brief", "list_followups", "save_followup") }.all { it.requiresConfirmation })
            tools.first { it.name == "save_followup" }.execute(buildJsonObject { put("title", "Explicit follow-up"); put("due", "2026-10-09T08:00:00-04:00") })
            assertEquals("/workspace/agent/briefing/followups", gateway.path)
            assertFalse(tools.any { it.name.contains("activate") || it.name.contains("approve") || it.name == "enable_checkins" })
        } finally { dir.deleteRecursively() }
    }
    @Test fun outgoingToolSavesOnlyAnExactDraftForSeparateReview() = runBlocking {
        val dir = Files.createTempDirectory("outgoing-draft").toFile()
        try {
            val gateway = FakeGateway()
            val tools = workspaceTools(gateway, KnowledgeStore(dir.resolve("knowledge.json")), FileConversationStore(dir.resolve("chats")))
            val args = buildJsonObject {
                put("kind", "send"); put("destination", "recipient@example.com")
                putJsonObject("payload") { put("account_id", "a".repeat(32)); put("to", "recipient@example.com"); put("subject", "Exact draft"); put("body", "Exact body") }
            }
            tools.first { it.name == "propose_outgoing_action" }.execute(args)
            assertEquals("/workspace/agent/outgoing/drafts", gateway.path); assertEquals(args, Json.parseToJsonElement(gateway.body!!))
            assertFalse(tools.any { it.name.contains("approve") || it.name == "send_email" })
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
