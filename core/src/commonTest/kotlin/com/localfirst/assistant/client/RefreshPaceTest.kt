package com.localfirst.assistant.client

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

/** Open screens refresh quickly only while something on them is in progress, and Images rereads Library rarely. */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshPaceTest {
    private class Pc(var imageStatus: String) : WorkspaceGateway {
        val calls = mutableListOf<String>()
        override fun connect(baseUrl: String, token: String) {}
        override fun lock() {}
        override suspend fun request(path: String, method: String, body: JsonObject?): JsonElement {
            calls += path
            return when (path) {
                "/workspace/images" -> JsonArray(listOf(buildJsonObject { put("id", "a"); put("status", imageStatus) }))
                "/workspace/files" -> JsonArray(listOf(buildJsonObject { put("id", "f"); put("mime", "image/png") }))
                else -> JsonArray(emptyList())
            }
        }
    }

    @Test fun imagesRefreshQuicklyOnlyWhileOneIsInProgress() = runTest {
        val pc = Pc("running"); val vm = FeatureController(pc, this)
        vm.open("images"); advanceUntilIdle()
        assertEquals(3_000, vm.refreshDelayMillis())
        pc.imageStatus = "completed"
        vm.refresh(); advanceUntilIdle()
        assertEquals(30_000, vm.refreshDelayMillis())
    }

    @Test fun libraryFilesAreNotReloadedOnEveryImagesRefresh() = runTest {
        val pc = Pc("completed"); val vm = FeatureController(pc, this)
        vm.open("images"); advanceUntilIdle()
        repeat(3) { vm.refresh(); advanceUntilIdle() }
        assertEquals(4, pc.calls.count { it == "/workspace/images" })
        assertEquals(1, pc.calls.count { it == "/workspace/files" })
    }
}
