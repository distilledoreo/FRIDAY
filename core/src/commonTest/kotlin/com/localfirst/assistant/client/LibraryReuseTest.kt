package com.localfirst.assistant.client

import com.localfirst.assistant.conversation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryReuseTest {
    private val docId = "a".repeat(32)
    private val imageId = "b".repeat(32)
    private fun TestScope.connected(pc: Pc) =
        WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 })

    private fun reference(id: String, name: String, mime: String, extra: JsonObject = JsonObject(emptyMap())) =
        JsonObject(buildJsonObject {
            put("id", id); put("name", name); put("mime", mime); put("size", 3)
            put("url", "assistant://artifact/$id")
        } + extra)

    @Test fun reusingASavedFileAttachesByReferenceWithoutMovingBytes() = runTest {
        val pc = Pc()
        pc.routes["GET /workspace/files/$docId/reference"] = {
            reference(docId, "notes.txt", "text/plain", buildJsonObject { put("text", "Hello reference") })
        }
        pc.routes["GET /workspace/files/$imageId/reference"] = {
            reference(imageId, "photo.png", "image/png")
        }
        val vm = connected(pc)
        vm.connect("", "token")!!.join()
        vm.reuseFile(docId)!!.join()
        vm.reuseFile(imageId)!!.join()
        val attachments = vm.state.value.attachments
        assertEquals(2, attachments.size)
        assertEquals("Hello reference", attachments[0].text)
        assertEquals(docId, attachments[0].remoteFileId)
        assertNull(attachments[0].path)
        assertEquals("remote:$imageId", attachments[1].path)
        assertEquals(imageId, attachments[1].remoteFileId)
        // No bytes were uploaded, downloaded or extracted on the client.
        assertEquals(0, pc.uploads)
        assertTrue(pc.files.isEmpty())
        assertTrue(pc.calls.none { it.first.startsWith("/extract") })
        assertTrue(pc.calls.none { it.first == "/workspace/files" && it.second != null })
        vm.setDraft("Read this")
        vm.send()!!.join()
        val chat = decodeConversation(pc.items.getValue("new").payload.getValue("chat").jsonPrimitive.content)
        assertEquals(2, (chat.messages.single() as Message.User).attachments.size)
    }

    @Test fun scannedPagesStayRemoteReferencesWithTheirNote() = runTest {
        val pc = Pc()
        pc.routes["GET /workspace/files/$docId/reference"] = {
            reference(docId, "scan.pdf", "application/pdf", buildJsonObject {
                put("text", ""); put("note", "No text layer; sent 1 page image(s).")
                put("pageImageIds", JsonArray(listOf(JsonPrimitive("c".repeat(32)))))
            })
        }
        val vm = connected(pc)
        vm.connect("", "token")!!.join()
        vm.reuseFile(docId)!!.join()
        val attachment = vm.state.value.attachments.single()
        assertEquals(listOf("remote:${"c".repeat(32)}"), attachment.pageImages)
        assertEquals("No text layer; sent 1 page image(s).", attachment.note)
        assertEquals(0, pc.uploads)
    }

    @Test fun reuseRejectsUnknownIdsMissingFilesAndAFullStrip() = runTest {
        val pc = Pc()
        pc.routes["GET /workspace/files/$docId/reference"] = {
            reference(docId, "notes.txt", "text/plain", buildJsonObject { put("text", "x") })
        }
        val vm = connected(pc)
        vm.connect("", "token")!!.join()
        vm.reuseFile("not-an-id")!!.join()
        assertTrue(vm.state.value.attachments.isEmpty())
        assertNotNull(vm.state.value.error)
        vm.dismissError()
        repeat(8) { vm.reuseFile(docId)!!.join() }
        assertEquals(8, vm.state.value.attachments.size)
        vm.reuseFile(docId)!!.join()
        assertEquals(8, vm.state.value.attachments.size)
        assertTrue(vm.state.value.error!!.contains("eight"))
    }

    @Test fun reuseOfADeletedFileReportsTheFailure() = runTest {
        val pc = Pc()
        pc.routes["GET /workspace/files/$docId/reference"] = { throw WorkspaceException(404, "File not found") }
        val vm = connected(pc)
        vm.connect("", "token")!!.join()
        vm.reuseFile(docId)!!.join()
        assertTrue(vm.state.value.attachments.isEmpty())
        assertEquals("File not found", vm.state.value.error)
    }
}
