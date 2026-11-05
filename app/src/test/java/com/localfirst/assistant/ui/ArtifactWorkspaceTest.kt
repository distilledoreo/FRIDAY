package com.localfirst.assistant.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.localfirst.assistant.artifacts.*
import com.localfirst.assistant.client.*
import com.localfirst.assistant.ui.artifacts.ArtifactWorkspace
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ArtifactWorkspaceTest {
    @get:Rule val compose = createComposeRule()
    class Gateway : WorkspaceGateway {
        val id = "0123456789abcdef0123456789abcdef"
        var revision = 1
        var title = "Fixture document"
        var content = wireJson.parseToJsonElement("""{"version":1,"blocks":[{"id":"first","type":"paragraph","text":"Original paragraph"}]}""").jsonObject
        var accepted = false
        fun artifact() = buildJsonObject { put("id", id); put("kind", "document"); put("title", title); put("revision", revision); put("content", content); put("deleted", 0) }
        override fun connect(baseUrl: String, token: String) {}
        override fun lock() {}
        override suspend fun request(path: String, method: String, body: JsonObject?): JsonElement = when {
            path.startsWith("/workspace/artifacts?") -> JsonArray(listOf(artifact()))
            path.endsWith("/history") -> JsonArray(listOf(buildJsonObject { put("revision", revision); put("summary", "Saved fixture") }))
            path.endsWith("/comments") -> JsonArray(emptyList())
            path.endsWith("/proposals") -> wireJson.parseToJsonElement("""[{"id":"proposal","summary":"Clearer wording","base":1,"status":"${if (accepted) "accepted" else "pending"}","operations":[{"type":"replace_text","block":"first","from":0,"to":18,"expected":"Original paragraph","text":"Corrected paragraph"}]}]""")
            path.endsWith("/proposals/proposal") -> { accepted = true; revision++; content = wireJson.parseToJsonElement("""{"version":1,"blocks":[{"id":"first","type":"paragraph","text":"Corrected paragraph"}]}""").jsonObject; artifact() }
            method == "POST" -> { revision++; title = body!!.string("title"); content = body["content"]!!.jsonObject; artifact() }
            else -> artifact()
        }
    }
    @Test fun opensSavedDocumentAndRequiresReviewBeforeApplyingProposal() {
        lateinit var vm: ArtifactController
        val pc = Gateway()
        compose.setContent { val scope = rememberCoroutineScope(); vm = remember { ArtifactController(pc, scope) }; MaterialTheme { ArtifactWorkspace(vm, {}, {}) } }
        compose.waitUntil(8000) { vm.state.value.items.isNotEmpty() }
        compose.onNodeWithText("Fixture document").performClick()
        compose.waitUntil(8000) { vm.state.value.selected != null && !vm.state.value.busy }
        compose.onNodeWithText("Open rich editor").assertIsDisplayed()
        assertFalse(pc.accepted)
        compose.onNodeWithText("Suggestions").performClick()
        compose.onNodeWithText("Accept").performScrollTo().performClick()
        compose.waitUntil(8000) { pc.accepted && !vm.state.value.busy }
        assertEquals("Corrected paragraph", vm.state.value.content!!["blocks"]!!.jsonArray[0].jsonObject.string("text"))
        assertEquals(2, vm.state.value.revision)
    }
    @Test fun canvasPreservesInteractiveSourceAndLongDocuments() {
        val source = "<AppBlock title=\"Counter\"><button>Count</button></AppBlock>"
        val app = CanvasDoc.from("App", source)!!
        assertEquals("app", app.kind); assertEquals(source, app.body)
        val long = "A".repeat(70000)
        assertEquals(long, CanvasDoc.from("Long", long)!!.body)
        assertEquals(long, CanvasDoc("Long", "").edited(long).body)
    }
    @Test
    @Config(sdk = [35], qualifiers = "w1400dp-h900dp-mdpi", application = Application::class)
    fun wideWorkspaceLeavesChatInteractive() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val vm = remember { ArtifactController(Gateway(), scope) }
            var clicks by remember { mutableStateOf(0) }
            MaterialTheme {
                com.localfirst.assistant.ui.artifacts.ArtifactWorkspaceHost(vm, true, {}, {}) {
                    androidx.compose.material3.Button({ clicks++ }) { androidx.compose.material3.Text("Chat actions: $clicks") }
                }
            }
        }
        compose.onNodeWithText("Workspace width").assertIsDisplayed()
        compose.onNodeWithText("Chat actions: 0").performClick()
        compose.onNodeWithText("Chat actions: 1").assertIsDisplayed()
        compose.onNodeWithText("Artifacts").assertIsDisplayed()
    }
}
