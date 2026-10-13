package com.localfirst.assistant.web

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import kotlinx.browser.window
import com.localfirst.assistant.platform.PlatformSystem
import com.localfirst.assistant.web.data.*
import com.localfirst.assistant.client.*
import com.localfirst.assistant.client.WorkspaceController
import com.localfirst.assistant.ui.theme.AssistantTheme
import com.localfirst.assistant.ui.workspace.WorkspaceApp
import kotlinx.browser.document
import org.w3c.dom.events.Event
import org.jetbrains.skiko.wasm.onWasmReady

@OptIn(ExperimentalComposeUiApi::class)
fun main() = onWasmReady {
    removeLegacyToken()
    document.getElementById("loading")?.remove()
    ComposeViewport(document.body!!) {
        val scope = rememberCoroutineScope()
        val vm = remember { WorkspaceController(WorkspaceRepository(FetchWorkspaceGateway()), scope, ::randomConversationId, PlatformSystem::currentTimeMillis) }
        val state by vm.state.collectAsState()
        DisposableEffect(vm) {
            vm.startPolling { document.asDynamic().hidden != true }
            val listener: (Event) -> Unit = { if (document.asDynamic().hidden != true && vm.state.value.connected) vm.refresh(true) }
            document.addEventListener("visibilitychange", listener)
            onDispose { document.removeEventListener("visibilitychange", listener); vm.close() }
        }
        CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
            override fun openUri(uri: String) {
                val artifact = Regex("^assistant://artifact/([a-f0-9]{32})$").matchEntire(uri)
                if (artifact != null) vm.artifact(artifact.groupValues[1])
                else if (uri.startsWith("https://") || uri.startsWith("http://")) window.open(uri, "_blank", "noopener,noreferrer")
            }
        }) { AssistantTheme(state.preferences) { WorkspaceApp(vm, state) {
            val legacy = remember { legacyChats() }
            if (legacy.isNotEmpty()) androidx.compose.material3.OutlinedButton({ vm.importLegacy(legacy, ::importId) }, enabled = !state.busy) { androidx.compose.material3.Text("Import ${legacy.size} older browser chats") }
            androidx.compose.material3.Text("Import copies older local chats into shared history. The originals stay in this browser.")
        } } }
    }
}
