package com.localfirst.assistant.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.localfirst.assistant.client.*
import com.localfirst.assistant.platform.PlatformNet
import com.localfirst.assistant.ui.workspace.LibraryScreen
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
class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()
    private class Files : WorkspaceGateway {
        val rows = (0 until 55).map { index -> buildJsonObject {
            put("id", "$index"); put("name", if (index < 5) "document$index.txt" else "photo$index.png")
            put("mime", if (index < 5) "text/plain" else "image/png"); put("size", index + 1)
        } }.toMutableList()
        override fun connect(baseUrl: String, token: String) {}
        override fun lock() {}
        override suspend fun request(path: String, method: String, body: JsonObject?): JsonElement {
            if (method == "DELETE") { rows.removeAll { it.string("id") == path.substringAfterLast('/') }; return JsonObject(emptyMap()) }
            val params = path.substringAfter('?').split('&').associate { it.substringBefore('=') to PlatformNet.decodeUrl(it.substringAfter('=')) }
            val matches = rows.filter { it.string("name").contains(params["q"].orEmpty()) && (params["kind"] != "documents" || it.string("mime") == "text/plain") }
            val offset = params["offset"]!!.toInt(); val page = matches.drop(offset).take(50)
            return buildJsonObject { put("files", JsonArray(page)); put("total", matches.size); put("next_offset", if (offset + page.size < matches.size) JsonPrimitive(offset + page.size) else JsonNull) }
        }
    }
    @Test fun searchFilterSortAndPagingAreUsableInTheSharedScreen() {
        lateinit var vm: FeatureController
        var opened: String? = null
        val pc = Files()
        compose.setContent { val scope = rememberCoroutineScope(); vm = androidx.compose.runtime.remember { FeatureController(pc, scope) }; MaterialTheme { LibraryScreen(vm, { opened = it }) } }
        compose.waitUntil(8000) { !vm.state.value.fileLoading && vm.state.value.files.size == 50 }
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        list.performScrollToNode(hasText("Load more files"))
        compose.onNodeWithText("Load more files").performClick()
        compose.waitUntil(8000) { vm.state.value.files.size == 55 }
        list.performScrollToNode(hasText("Search filenames"))
        compose.onNodeWithText("Search filenames").performTextInput("missing")
        compose.onNodeWithText("Search filenames").assertIsFocused()
        compose.waitUntil(8000) { !vm.state.value.fileLoading && vm.state.value.fileTotal == 0 }
        compose.onNodeWithText("No files match this search and file type.").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()
        compose.waitUntil(8000) { !vm.state.value.fileLoading && vm.state.value.fileTotal == 55 }
        compose.onNodeWithText("Documents").performClick()
        compose.waitUntil(8000) { !vm.state.value.fileLoading && vm.state.value.fileTotal == 5 }
        compose.onNodeWithText("Documents").assertIsSelected()
        compose.onNodeWithText("Name A–Z").performClick()
        compose.waitUntil(8000) { !vm.state.value.fileLoading && vm.state.value.fileSort == "name" }
        compose.onNodeWithText("Name A–Z").assertIsSelected()
        list.performScrollToNode(hasText("document0.txt"))
        compose.onNodeWithText("document0.txt").performClick()
        compose.runOnIdle { assertEquals("0", opened); assertNull(vm.state.value.error) }
    }
}
