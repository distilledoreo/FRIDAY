package com.localfirst.assistant.artifacts

import com.localfirst.assistant.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactControllerTest {
    @Test fun editableLinksCannotBeConfusedWithDownloadsOrExternalUrls() {
        val id = "0123456789abcdef0123456789abcdef"
        assertEquals(id, editableArtifactId("assistant://document/$id"))
        assertNull(editableArtifactId("assistant://artifact/$id"))
        assertNull(editableArtifactId("https://example.com/document/$id"))
        assertNull(editableArtifactId("assistant://document/$id/extra"))
    }
    class Gateway : WorkspaceGateway {
        var revision = 1
        var content = blankContent("document")
        var title = "Document"
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<JsonObject>()
        val id = "0123456789abcdef0123456789abcdef"
        override fun connect(baseUrl: String, token: String) {}
        override fun lock() {}
        override suspend fun request(path: String, method: String, body: JsonObject?): JsonElement {
            if (method == "POST" && path == "/workspace/artifacts/$id") {
                writes += body!!
                gate?.await()
                if (fail) throw WorkspaceException(409, "Changed on another client")
                assertEquals(revision, body["base"]!!.jsonPrimitive.int)
                revision++; content = body["content"]!!.jsonObject; title = body.string("title")
                return buildJsonObject { put("id", id); put("revision", revision) }
            }
            if(path.endsWith("history") || path.endsWith("comments") || path.endsWith("proposals")) return JsonArray(emptyList())
            return buildJsonObject { put("id",id);put("title",title);put("revision",revision);put("kind","document");put("content",content) }
        }
    }
    @Test fun editsDuringSaveAreNotClearedByOldAcknowledgment() = runTest {
        val pc = Gateway(); val vm = ArtifactController(pc, this)
        vm.open(pc.id)!!.join(); vm.edit(title = "First")
        pc.gate = CompletableDeferred()
        val job = vm.save()!!; runCurrent()
        vm.edit(title = "Second")
        pc.gate!!.complete(Unit); job.join()
        assertEquals("Second", vm.state.value.title)
        assertEquals("Second", pc.title)
        assertEquals(2, pc.writes.size)
        assertEquals(3, vm.state.value.revision)
        assertFalse(vm.state.value.dirty)
        vm.reset(); advanceUntilIdle()
    }
    @Test fun conflictPreservesDraftAndDoesNotRetryForever() = runTest {
        val pc = Gateway(); val vm = ArtifactController(pc, this)
        vm.open(pc.id)!!.join(); pc.fail = true
        vm.edit(title = "Keep me"); advanceUntilIdle()
        assertTrue(vm.state.value.dirty); assertEquals("Keep me", vm.state.value.title)
        assertEquals(1, pc.writes.size); assertNotNull(vm.state.value.error)
        vm.back(); assertNotNull(vm.state.value.selected)
        vm.reset()
    }
    @Test fun retryReusesRequestKeyAndSavesEditsMadeAfterFailure() = runTest {
        val pc = Gateway(); val vm = ArtifactController(pc, this)
        vm.open(pc.id)!!.join(); pc.fail = true; vm.edit(title = "First"); vm.save()!!.join()
        val key = pc.writes[0].string("request")
        pc.fail = false; vm.edit(title = "Second"); vm.save()!!.join()
        assertEquals(key, pc.writes[1].string("request"))
        assertEquals("Second", pc.title)
        vm.reset(); advanceUntilIdle()
    }
    @Test fun recoveryDraftIsRestoredWithoutReplacingServerRevision() = runTest {
        val pc = Gateway()
        val drafts = object : ArtifactDrafts {
            var draft: JsonObject? = null
            override fun read(id: String) = draft
            override fun write(id: String, draft: JsonObject?) { this.draft = draft }
        }
        val first = ArtifactController(pc, this, drafts); first.open(pc.id)!!.join(); first.edit(title = "Recovered"); first.reset()
        pc.revision = 2
        val second = ArtifactController(pc, this, drafts); second.open(pc.id)!!.join()
        assertEquals("Recovered", second.state.value.title); assertTrue(second.state.value.dirty)
        assertEquals(1, second.state.value.revision)
        second.reset(); advanceUntilIdle()
    }
    @Test fun unavailableRecoveryStorageDoesNotLoseEditsOrPreventServerSave() = runTest {
        val pc = Gateway()
        val drafts = object : ArtifactDrafts {
            override fun read(id: String): JsonObject? = null
            override fun write(id: String, draft: JsonObject?) { error("Device storage full") }
        }
        val vm = ArtifactController(pc, this, drafts)
        vm.open(pc.id)!!.join(); vm.edit(title = "Still here")
        assertTrue(vm.state.value.dirty)
        assertTrue(vm.state.value.notice!!.contains("recovery is unavailable"))
        advanceUntilIdle()
        assertEquals("Still here", pc.title); assertFalse(vm.state.value.dirty)
        assertNull(vm.state.value.error)
        vm.reset()
    }
}
