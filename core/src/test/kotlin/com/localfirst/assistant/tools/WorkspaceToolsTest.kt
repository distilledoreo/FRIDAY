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
            assertEquals(10, tools.size)
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
    @Test fun memoryIsSavedOnlyAfterConfirmationAndCanBeForgotten() = runBlocking {
        val dir = Files.createTempDirectory("workspace-tools").toFile()
        try {
            val knowledge = KnowledgeStore(dir.resolve("knowledge.json"))
            val registry = ToolRegistry().apply { workspaceTools(FakeGateway(), knowledge, FileConversationStore(dir.resolve("chats"))).forEach(::register) }
            val call = ToolCall("1", "remember", """{"text":"Prefer concise answers"}""")
            registry.execute(call, ToolConfirmer { false })
            assertTrue(knowledge.load().memories.isEmpty())
            registry.execute(call, ToolConfirmer { true })
            val memory = knowledge.load().memories.single()
            registry.execute(ToolCall("2", "forget_memory", """{"id":"${memory.id}"}"""), ToolConfirmer { true })
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
