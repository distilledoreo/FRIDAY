package com.localfirst.assistant.client

import com.localfirst.assistant.platform.PlatformNet
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryQueryTest {
    private class Library : WorkspaceGateway {
        val files = (0 until 206).map { buildJsonObject { put("id", "$it"); put("name", if (it == 205) "Straße%_ old.txt" else "file$it.png"); put("mime", if (it == 205) "text/plain" else "image/png") } }.toMutableList()
        val calls = mutableListOf<String>()
        var hold: CompletableDeferred<Unit>? = null
        var mutation: CompletableDeferred<Unit>? = null
        override fun connect(baseUrl: String, token: String) {}
        override fun lock() {}
        override suspend fun request(path: String, method: String, body: JsonObject?): JsonElement {
            calls += "$method $path"
            if (method == "DELETE") { mutation?.await(); files.removeAll { it.string("id") == path.substringAfterLast('/') }; return JsonObject(emptyMap()) }
            if (path == "/workspace/agent/accounts") return buildJsonObject { put("accounts", JsonArray(emptyList())) }
            if (!path.startsWith("/workspace/files?")) return JsonArray(emptyList())
            val params = path.substringAfter('?').split('&').associate { it.substringBefore('=') to PlatformNet.decodeUrl(it.substringAfter('=')) }
            val q = params.getValue("q")
            // A transport that ignores cancellation must still be unable to publish its obsolete result.
            hold?.takeIf { q == "old" }?.let { withContext(NonCancellable) { it.await() } }
            val matches = files.filter { it.string("name").contains(q, ignoreCase = true) && (params["kind"] != "documents" || it.string("mime") == "text/plain") }
            val offset = params.getValue("offset").toInt()
            val rows = matches.drop(offset).take(50)
            return buildJsonObject { put("files", JsonArray(rows)); put("total", matches.size); put("next_offset", if (offset + rows.size < matches.size) JsonPrimitive(offset + rows.size) else JsonNull) }
        }
    }

    @Test fun pagesReachTheWholeCollectionAndFilenameSearchIsEncoded() = runTest {
        val pc = Library(); val vm = FeatureController(pc, this)
        vm.open("files"); advanceUntilIdle()
        assertEquals(50, vm.state.value.files.size); assertEquals(206, vm.state.value.fileTotal)
        while (vm.state.value.fileNextOffset != null) vm.moreFiles()!!.join()
        assertEquals(206, vm.state.value.files.size)
        assertEquals(206, vm.state.value.files.map { it.string("id") }.toSet().size)
        vm.searchFiles("Straße%_"); vm.filterFiles("documents"); vm.sortFiles("name"); advanceUntilIdle()
        assertEquals(listOf("205"), vm.state.value.files.map { it.string("id") })
        assertEquals("name", vm.state.value.fileSort)
        assertTrue(pc.calls.last().contains("q=${PlatformNet.encodeUrl("Straße%_")}"))
        assertNull(vm.state.value.fileNextOffset)
        vm.searchFiles("missing"); advanceUntilIdle()
        assertEquals(0, vm.state.value.fileTotal); assertTrue(vm.state.value.files.isEmpty())
    }

    @Test fun staleResponsesCannotReplaceSearchOrSurviveLeavingOrLock() = runTest {
        val pc = Library(); val vm = FeatureController(pc, this)
        vm.open("files"); advanceUntilIdle()
        pc.hold = CompletableDeferred(); vm.searchFiles("old"); advanceTimeBy(251); runCurrent()
        vm.searchFiles("file1"); advanceTimeBy(251); runCurrent()
        assertTrue(vm.state.value.files.all { it.string("name").startsWith("file1") })
        pc.hold!!.complete(Unit); advanceUntilIdle()
        assertEquals("file1", vm.state.value.fileQuery)
        assertTrue(vm.state.value.files.all { it.string("name").startsWith("file1") })
        pc.hold = CompletableDeferred(); vm.searchFiles("old"); advanceTimeBy(251); runCurrent()
        vm.open("accounts"); pc.hold!!.complete(Unit); advanceUntilIdle()
        assertEquals("accounts", vm.state.value.page); assertFalse(vm.state.value.fileLoading)
        vm.open("files"); pc.hold = CompletableDeferred(); vm.searchFiles("old"); advanceTimeBy(251); runCurrent()
        vm.reset(); pc.hold!!.complete(Unit); advanceUntilIdle()
        assertEquals(FeatureState(), vm.state.value)
    }

    @Test fun typingDoesNotCancelDeletionAndDeletionRefreshesTheCurrentQuery() = runTest {
        val pc = Library(); val vm = FeatureController(pc, this)
        vm.open("files"); advanceUntilIdle()
        pc.mutation = CompletableDeferred()
        val deletion = vm.change("/workspace/files/205", "DELETE")!!; runCurrent()
        vm.searchFiles("old"); advanceTimeBy(251); runCurrent()
        assertEquals(1, vm.state.value.files.size); assertTrue(vm.state.value.busy)
        pc.mutation!!.complete(Unit); deletion.join()
        assertFalse(vm.state.value.busy); assertEquals("old", vm.state.value.fileQuery)
        assertTrue(vm.state.value.files.isEmpty()); assertEquals(0, vm.state.value.fileTotal)
        assertNull(vm.state.value.error)
    }
}
