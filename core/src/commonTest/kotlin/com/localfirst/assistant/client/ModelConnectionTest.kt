package com.localfirst.assistant.client

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class ModelConnectionTest {
    @Test fun bothHostsUseTheSameValidation() {
        assertNull(ModelConnection(" https://model.example/v1 ", "chosen").validate())
        for (address in listOf("file:///tmp/model", "https://user:pass@example.org/v1", "https://example.org/v1?token=x", "https://example.org/#x"))
            assertNotNull(ModelConnection(address, "chosen").validate())
        assertNotNull(ModelConnection("https://example.org/v1", "").validate())
        assertNotNull(ModelConnection("https://example.org/v1", "chosen", timeout = "oops").validate())
    }
    @Test fun credentialsDoNotEnterTheConversationOrTurnOptions() = runTest {
        val pc = Pc()
        pc.routes["POST /workspace/model-connections"] = { body ->
            assertEquals("synthetic-model-key", body!!["api_key"]!!.jsonPrimitive.content)
            buildJsonObject { put("id", "a".repeat(32)) }
        }
        val vm = WorkspaceController(WorkspaceRepository(pc), this, { "chat" }, { 1 })
        vm.connect("", "synthetic-gateway-key")!!.join()
        vm.useModelConnection(ModelConnection("https://model.example/v1", "chosen", "synthetic-model-key"))!!.join()
        vm.setDraft("Hi"); vm.send()!!.join()
        val turn = pc.calls.last { it.first == "/workspace/items/chat/continue" }.second!!
        assertEquals("a".repeat(32), turn["connection"]!!.jsonPrimitive.content)
        assertFalse(turn.toString().contains("synthetic-model-key"))
        assertFalse(pc.items.values.toString().contains("synthetic-model-key"))
    }
    @Test fun privateUploadsRemainInThePrivateTranscript() = runTest {
        val pc = Pc()
        val vm = WorkspaceController(WorkspaceRepository(pc), this, { "private" }, { 1 })
        vm.connect("", "token")!!.join(); vm.newIncognito()
        vm.upload("notes.txt", "text/plain", "Private document".encodeToByteArray())!!.join()
        vm.setDraft("Read this"); vm.send()!!.join()
        assertNull(vm.state.value.error)
        assertEquals("Private document", (pc.privateChat!!.messages.first() as com.localfirst.assistant.conversation.Message.User).attachments.single().text)
        assertTrue(pc.items.isEmpty())
    }
}
