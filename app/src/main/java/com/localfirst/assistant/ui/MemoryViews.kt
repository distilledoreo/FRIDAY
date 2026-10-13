package com.localfirst.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlinx.serialization.json.jsonObject

/** Android's memory pages: the shared screens with the phone's file picker and navigation. */
@Composable
internal fun MemoryHub(state: ChatUiState, vm: ChatViewModel, onImport: () -> Unit, onReview: () -> Unit, onArchive: () -> Unit, onEdit: (JSONObject) -> Unit, onContext: () -> Unit, onSource: () -> Unit = onArchive) {
    val actions = rememberNativeWorkspaceActions(vm, state)
    val shared by actions.state.collectAsState()
    FridayVisibleEffect(Unit) {
        vm.refreshArchive()
        while (true) { vm.refreshMemory(); delay(10000) }
    }
    com.localfirst.assistant.ui.workspace.MemoryScreen(actions, shared, onSource = onSource) {
        com.localfirst.assistant.ui.workspace.MemoryTools(actions, shared, onImport, onReview, onArchive, onContext)
        TextButton(vm::indexExistingChats, enabled = !shared.busy) { Text("Index existing phone chats on PC") }
    }
}

@Composable
internal fun ChatGptImport(state: ChatUiState, vm: ChatViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::stageChatGptImport) }
    val actions = rememberNativeWorkspaceActions(vm, state)
    val shared by actions.state.collectAsState()
    com.localfirst.assistant.ui.workspace.ChatGptImportScreen(actions, shared, onChoose = { picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream")) },
        platformNote = "Choose your ChatGPT export ZIP or conversations.json. It streams straight to your PC, with no export copy saved on the phone.")
}

@Composable
internal fun MemoryReview(state: ChatUiState, vm: ChatViewModel) {
    val actions = rememberNativeWorkspaceActions(vm, state)
    val shared by actions.state.collectAsState()
    com.localfirst.assistant.ui.workspace.MemoryReviewScreen(actions, shared)
}

@Composable
internal fun MemoryArchive(state: ChatUiState, vm: ChatViewModel, onSource: () -> Unit) {
    val actions = rememberNativeWorkspaceActions(vm, state)
    val shared by actions.state.collectAsState()
    com.localfirst.assistant.ui.workspace.MemoryArchiveScreen(actions, shared, onSource)
}

@Composable
internal fun MemorySource(state: ChatUiState, vm: ChatViewModel, onDeleted: () -> Unit = {}) {
    val actions = rememberNativeWorkspaceActions(vm, state)
    val shared by actions.state.collectAsState()
    // Continuing opens the chat on the phone, which closes this page.
    com.localfirst.assistant.ui.workspace.MemorySourceScreen(actions, shared, onContinue = {}, onDeleted = onDeleted)
}

@Composable
internal fun MemoryContext(state: ChatUiState) {
    val shared = remember(state.recallSources, state.recallStatus) {
        fun JSONObject.wire() = com.localfirst.assistant.client.wireJson.parseToJsonElement(toString()).jsonObject
        com.localfirst.assistant.client.WorkspaceState(recall = state.recallSources.map { it.wire() }, recallStatus = state.recallStatus)
    }
    com.localfirst.assistant.ui.workspace.MemoryContextScreen(shared)
}
