package com.localfirst.assistant.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.localfirst.assistant.genui.UiCompiler
import com.localfirst.assistant.web.data.ConversationStore
import com.localfirst.assistant.web.data.ServerSettingsStore
import com.localfirst.assistant.web.settings.AppearanceStore
import com.localfirst.assistant.web.state.ChatViewModel
import com.localfirst.assistant.web.theme.AssistantTheme
import com.localfirst.assistant.web.ui.ChatScreen
import com.localfirst.assistant.web.ui.SettingsDialog
import kotlinx.browser.document

/**
 * Browser entry point. The shared engine and presentation layer come from core's JS target; the
 * chat UI and the browser-backed infra (HTTP, storage) are added on top of it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val compiled = UiCompiler.compile("<div>hello</div>", complete = true)
    console.log("FRIDAY web engine ready: compiled ${compiled.nodes.size} node(s)")
    ComposeViewport(document.body!!) {
        App()
    }
}

@Composable
private fun App() {
    val appearance = remember { AppearanceStore() }
    val prefs by appearance.state.collectAsState()
    val settings = remember { ServerSettingsStore() }
    val scope = rememberCoroutineScope()
    val vm = remember { ChatViewModel(ConversationStore(), settings, scope) }
    var showSettings by remember { mutableStateOf(false) }
    AssistantTheme(prefs, appearance::save) {
        ChatScreen(vm) { showSettings = true }
        if (showSettings) SettingsDialog(vm, settings) { showSettings = false }
    }
}
