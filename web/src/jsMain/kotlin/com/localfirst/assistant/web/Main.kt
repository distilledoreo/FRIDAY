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
        val vm = remember { WorkspaceController(WorkspaceRepository(gateway), scope, ::randomConversationId, PlatformSystem::currentTimeMillis,
            save = { name, mime, bytes -> downloadBytes(name, mime, bytes) }, inflate = browserInflate) }
        val state by vm.state.collectAsState()
        val features = remember { FeatureController(gateway, scope, browserOAuth = true) }
        val briefing = remember { BriefingController(gateway, scope) }
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
        val audio = remember { BrowserVoiceIo(gateway, scope) }
        // The same voice loop as the phone: streamed sentences, talking over FRIDAY, and spoken approvals.
        var voiceEngine by remember { mutableStateOf(DeviceChoices.voiceEngine) }
        var bargeIn by remember { mutableStateOf(DeviceChoices.bargeIn) }
        var notify by remember { mutableStateOf(DeviceChoices.notificationMode) }
        var notifyNote by remember { mutableStateOf<String?>(null) }
        val navigation = remember { kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4) }
        // The browser's own voice where chosen and supported, as the phone can use its own; otherwise the computer's.
        val selectVoice: suspend () -> com.localfirst.assistant.voice.VoiceSelection = {
            val own = audio.browserIo
            when {
                DeviceChoices.voiceEngine != "browser" -> com.localfirst.assistant.voice.VoiceSelection(audio.io)
                own != null -> com.localfirst.assistant.voice.VoiceSelection(own)
                else -> com.localfirst.assistant.voice.VoiceSelection(audio.io, "Using the computer's voice: this browser has no built-in speech recognition.")
            }
        }
        val voice = remember { com.localfirst.assistant.voice.VoiceController(scope, selectVoice, vm.state,
            view = { s -> com.localfirst.assistant.voice.VoiceTurn(
                s.transcript + listOfNotNull(s.streamingText.takeIf { it.isNotBlank() }?.let { Message.Assistant(it) }),
                busy = s.busy || s.activeJob != null,
                approval = s.approval?.let { com.localfirst.assistant.voice.VoiceApproval(it.string("call_id"), it.string("title") + ". " + it.string("prompt")) }) },
            submit = { text -> vm.setDraft(text); vm.send() != null },
            resubmit = { text ->
                // A continuation of the last spoken message replaces it, unless a tool already ran for it.
                val messages = vm.state.value.active?.conversation?.messages.orEmpty()
                val last = messages.indexOfLast { it is Message.User }
                last >= 0 && messages.drop(last + 1).none { it is Message.ToolCall } && run { vm.startEditing(last); vm.setDraft(text); vm.send() != null }
            },
            stopTurn = { vm.stop() }, answerApproval = { vm.answerApproval(it) }, bargeInEnabled = { DeviceChoices.bargeIn }) }
        // This device's model choice, as the phone keeps its own.
        LaunchedEffect(state.models) { if (state.models.isNotEmpty()) DeviceChoices.model?.takeIf { it in state.models }?.let(vm::setModel) }
        LaunchedEffect(state.model, state.models) { if (state.models.isNotEmpty()) DeviceChoices.model = state.model }
        // Background notices, by the phone's rules; check-ins are refreshed every fifteen minutes.
        LaunchedEffect(state.connected, notify) {
            if (!state.connected || notify != "open") return@LaunchedEffect
            val seen = DeviceChoices.seen()
            val planner = NoticePlanner({ path, method, body -> gateway.request(path, method, body) }, seen, PlatformSystem::currentTimeMillis)
            var lastCheckins = 0L
            while (true) {
                val now = PlatformSystem.currentTimeMillis()
                val checkins = now - lastCheckins >= 15 * 60_000L
                if (checkins) lastCheckins = now
                val notices = try { planner.poll(checkins) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
                DeviceChoices.saveSeen(seen)
                val away = document.asDynamic().hidden == true || document.asDynamic().hasFocus() != true
                if (away) notices.forEach { n -> showNotice(n.title, n.text, n.key) { navigation.tryEmit(n.open) } }
                kotlinx.coroutines.delay(60_000)
            }
        }
        DisposableEffect(vm) {
            val leave: (Event) -> Unit = { vm.lock() }
            window.addEventListener("pagehide", leave)
            onDispose { window.removeEventListener("pagehide", leave) }
        }
        DisposableEffect(Unit) {
            val listener: (Event) -> Unit = { e -> val data = e.asDynamic().data
                if (data?.kind == "friday-notice" && data.open is String) navigation.tryEmit(data.open as String) }
            window.navigator.asDynamic().serviceWorker?.addEventListener("message", listener)
            onDispose { window.navigator.asDynamic().serviceWorker?.removeEventListener("message", listener) }
        }
        LaunchedEffect(state.connected) {
            if (state.connected && window.location.hash.startsWith("#notice=")) {
                navigation.emit(com.localfirst.assistant.platform.PlatformNet.decodeUrl(window.location.hash.removePrefix("#notice=")))
                window.history.replaceState(null, "", window.location.pathname)
            }
        }
        val voiceState by voice.state.collectAsState()
        val dictating by audio.dictating.collectAsState()
        val apps = remember(state.activeId, state.incognito) { object : AppBlockStore {
            private val values = mutableMapOf<String, String>()
            override fun get(id: String) = values[id]
            override fun set(id: String, json: String) { if (json.length <= 200000 && values.size < 40) values[id] = json }
        } }
        LaunchedEffect(state.connected) { if (!state.connected) { voice.close(); audio.close(); images.clear(); features.reset(); briefing.reset(); code.reset(); oauthWindow?.close(); oauthWindow = null } }
        // Voice belongs to one conversation; leaving it ends voice mode.
        var voiceChat by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(state.activeId, state.incognito) { if (voice.active && voiceChat != null && state.activeId != voiceChat) voice.close(); voiceChat = state.activeId }
        DisposableEffect(vm) {
            vm.startPolling { document.asDynamic().hidden != true }
            val listener: (Event) -> Unit = { if (document.asDynamic().hidden != true && vm.state.value.connected) vm.refresh(true) }
            document.addEventListener("visibilitychange", listener)
            onDispose { voice.shutdown(); audio.close(); features.reset(); code.reset(); document.removeEventListener("visibilitychange", listener); vm.close() }
        }
        CompositionLocalProvider(LocalOAuthLauncher provides { url -> oauthWindow = window.open(url, "friday-account-sign-in", "popup,width=600,height=750") }, LocalFeatureImages provides images::path, LocalReplyImages provides images, LocalReplyApps provides apps, LocalUriHandler provides object : UriHandler {
            override fun openUri(uri: String) {
                val artifact = Regex("^assistant://artifact/([a-f0-9]{32})$").matchEntire(uri)
                if (artifact != null) vm.artifact(artifact.groupValues[1])
                else if (uri.startsWith("https://") || uri.startsWith("http://")) window.open(uri, "_blank", "noopener,noreferrer")
            }
        }) { AssistantTheme(state.preferences) { WorkspaceApp(vm, state, features, code,
            onAttach = { pickBrowserFiles(scope, vm) }, onLibraryUpload = { pickBrowserFiles(scope, vm, false) },
            onDictate = { audio.dictate(vm, voice.active) }, dictating = dictating,
            voice = voiceState, voiceControls = VoiceControls(toggle = { if (voice.active) voice.close() else scope.launch {
                if (!vm.state.value.incognito) {
                    vm.openLiveVoice()?.join()
                    if (vm.state.value.activeId != com.localfirst.assistant.voice.LiveVoiceConversation.ID) return@launch
                }
                voiceChat = vm.state.value.activeId
                voice.start()
            } },
                pause = { voice.pause("Voice paused. Resume when you're ready.") }, resume = voice::start, interrupt = { voice.interrupt() },
                close = voice::close, mute = { voice.muteMic(voiceState?.micMuted != true) }),
            onShare = { text -> shareText(text) { vm.reportError(it) } },
            onImportChatGpt = { pickBrowserFile(".zip,.json,application/zip,application/json") { file ->
                vm.stageImport { gateway.sendFile("/workspace/memory/imports", file) } } },
            onRestoreBackup = { pickBrowserFile(".zip,application/zip") { file ->
                scope.launch { try { vm.restoreBackup(fileBytes(file)) } catch (e: Exception) { vm.reportError(e.message ?: "Backup could not be read") } } } },
            briefing = briefing, navigation = navigation) {
            var connection by remember { mutableStateOf(com.localfirst.assistant.client.ModelConnection()) }
            androidx.compose.material3.Text("Model connection", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            androidx.compose.material3.Text("Use the computer default, or an OpenAI-compatible endpoint reached by the computer. Credentials last for this signed-in session.")
            com.localfirst.assistant.ui.workspace.ModelConnectionFields(connection, { connection = it }, enabled = state.idle)
            androidx.compose.material3.OutlinedButton({ vm.useModelConnection(connection) }, enabled = state.idle) { androidx.compose.material3.Text("Use this connection") }
            androidx.compose.material3.TextButton({ vm.useModelConnection(null) }, enabled = state.idle) { androidx.compose.material3.Text("Use computer default") }
            DeviceSettingsSection(DeviceSettings(voiceEngine, buildList {
                    add("computer" to "Computer voice (recognition and speech on your computer)")
                    add("browser" to if (audio.browserIo != null) "This browser's voice" else "This browser's voice (unavailable here; uses the computer's)")
                }, bargeIn, notify, notificationsSupported, pushSupported, notifyNote ?: if (!notificationsSupported) "Notifications need HTTPS or localhost and a browser that supports them." else null),
                onVoiceEngine = { voiceEngine = it; DeviceChoices.voiceEngine = it; if (voice.active) { voice.close() } },
                onBargeIn = { bargeIn = it; DeviceChoices.bargeIn = it },
                onNotifications = { mode -> scope.launch {
                    try { setNotificationMode(gateway, mode); notify = mode; notifyNote = null }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { notifyNote = e.message ?: "Notification settings could not be changed." }
                } })
            val legacy = remember { legacyChats() }
            if (legacy.isNotEmpty()) androidx.compose.material3.OutlinedButton({ vm.importLegacy(legacy, ::importId) }, enabled = !state.busy) { androidx.compose.material3.Text("Import ${legacy.size} older browser chats") }
            androidx.compose.material3.Text("Import copies older local chats into shared history. The originals stay in this browser.")
        } } }
    }
}

/** The browser's share sheet where it exists, otherwise the clipboard. */
private fun shareText(text: String, failed: (String) -> Unit) {
    val trimmed = text.trim().take(20000)
    if (trimmed.isEmpty()) return
    val navigator = window.navigator.asDynamic()
    val payload = js("({})"); payload.text = trimmed
    val sharing = if (navigator.share != null) navigator.share(payload) else navigator.clipboard?.writeText(trimmed)
    (sharing as? kotlin.js.Promise<dynamic>)?.catch { error -> if (error.asDynamic()?.name != "AbortError") failed("Sharing is unavailable in this browser."); null }
        ?: failed("Sharing is unavailable in this browser.")
}
