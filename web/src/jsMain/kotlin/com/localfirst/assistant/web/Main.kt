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
import com.localfirst.assistant.ui.workspace.*
import com.localfirst.assistant.ui.code.CodeWorkspace
import com.localfirst.assistant.ui.genui.AppBlockStore
import kotlinx.coroutines.launch
import com.localfirst.assistant.conversation.Message
import kotlinx.browser.document
import org.w3c.dom.events.Event
import org.jetbrains.skiko.wasm.onWasmReady

@OptIn(ExperimentalComposeUiApi::class)
fun main() = onWasmReady {
    removeLegacyToken()
    document.getElementById("loading")?.remove()
    ComposeViewport(document.body!!) {
        val scope = rememberCoroutineScope()
        val gateway = remember { FetchWorkspaceGateway() }
        val vm = remember { WorkspaceController(WorkspaceRepository(gateway), scope, ::randomConversationId, PlatformSystem::currentTimeMillis) }
        val state by vm.state.collectAsState()
        val features = remember { FeatureController(gateway, scope, browserOAuth = true) }
        val code = remember { CodeWorkspace(scope, { path, method, body -> gateway.request(path, method, body?.json()).toString() }) }
        val featureState by features.state.collectAsState()
        var oauthWindow by remember { mutableStateOf<dynamic>(null) }
        DisposableEffect(features) {
            val listener: (Event) -> Unit = { event ->
                val message = event.asDynamic(); val data = message.data
                if (oauthWindow != null && message.source == oauthWindow && message.origin == gateway.origin() && data?.kind == "friday-oauth") {
                    if (data.code is String && data.state is String) features.finishOAuth(data.code as String, data.state as String)
                    else features.cancelOAuth()
                    oauthWindow = null
                }
            }
            window.addEventListener("message", listener)
            onDispose { window.removeEventListener("message", listener); oauthWindow?.close() }
        }
        val images = remember { BrowserImages(gateway) }
        val voice = remember { BrowserVoice(gateway, vm, scope) }
        val voiceState by voice.state.collectAsState()
        val apps = remember(state.activeId, state.incognito) { object : AppBlockStore {
            private val values = mutableMapOf<String, String>()
            override fun get(id: String) = values[id]
            override fun set(id: String, json: String) { if (json.length <= 200000 && values.size < 40) values[id] = json }
        } }
        LaunchedEffect(state.connected) { if (!state.connected) { voice.close(); images.clear(); features.reset(); code.reset(); oauthWindow?.close(); oauthWindow = null } }
        LaunchedEffect(state.activeId, state.incognito) { if (voice.state.value.recording || voice.state.value.status == "Requesting microphone…") voice.close() }
        LaunchedEffect(state.activeJob, state.active?.conversation?.messages?.lastOrNull()) {
            if (state.activeJob == null) (state.active?.conversation?.messages?.lastOrNull() as? Message.Assistant)?.let { voice.reply(it.content) }
        }
        DisposableEffect(vm) {
            vm.startPolling { document.asDynamic().hidden != true }
            val listener: (Event) -> Unit = { if (document.asDynamic().hidden != true && vm.state.value.connected) vm.refresh(true) }
            document.addEventListener("visibilitychange", listener)
            onDispose { voice.close(); features.reset(); code.reset(); document.removeEventListener("visibilitychange", listener); vm.close() }
        }
        CompositionLocalProvider(LocalOAuthLauncher provides { url -> oauthWindow = window.open(url, "friday-account-sign-in", "popup,width=600,height=750") }, LocalFeatureImages provides images::path, LocalReplyImages provides images, LocalReplyApps provides apps, LocalUriHandler provides object : UriHandler {
            override fun openUri(uri: String) {
                val artifact = Regex("^assistant://artifact/([a-f0-9]{32})$").matchEntire(uri)
                if (artifact != null) vm.artifact(artifact.groupValues[1])
                else if (uri.startsWith("https://") || uri.startsWith("http://")) window.open(uri, "_blank", "noopener,noreferrer")
            }
        }) { AssistantTheme(state.preferences) { WorkspaceApp(vm, state, features, code,
            onAttach = { pickBrowserFiles(scope, vm) }, onLibraryUpload = { pickBrowserFiles(scope, vm, false) },
            onDictate = voice::dictate, dictating = voiceState.recording,
            onVoice = voice::converse, voiceActive = voiceState.conversation, voiceStatus = voiceState.status) {
            val legacy = remember { legacyChats() }
            if (legacy.isNotEmpty()) androidx.compose.material3.OutlinedButton({ vm.importLegacy(legacy, ::importId) }, enabled = !state.busy) { androidx.compose.material3.Text("Import ${legacy.size} older browser chats") }
            androidx.compose.material3.Text("Import copies older local chats into shared history. The originals stay in this browser.")
        } } }
    }
}
