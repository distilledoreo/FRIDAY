package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.settings.ServerSettings

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    ChatContent(
        state = state,
        onDraftChange = viewModel::onDraftChange,
        onSend = viewModel::send,
        onRetry = viewModel::retry,
        onClear = viewModel::clearConversation,
        onOpenSettings = viewModel::openSettings,
        onDismissSettings = viewModel::dismissSettings,
        onSaveSettings = viewModel::saveSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    state: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissSettings: () -> Unit,
    onSaveSettings: (ServerSettings) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Assistant")
                        Text(
                            text = state.status,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onClear,
                        enabled = !state.busy && state.messages.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear conversation")
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        enabled = !state.busy,
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Server settings")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                draft = state.draft,
                busy = state.busy,
                error = state.error,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onRetry = onRetry,
            )
        },
    ) { padding ->
        Transcript(
            messages = state.messages,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }

    if (state.showSettings) {
        SettingsPage(
            initial = state.settings,
            error = state.settingsError,
            onDismiss = onDismissSettings,
            onSave = onSaveSettings,
        )
    }
}

@Composable
private fun Transcript(
    messages: List<Message>,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) {
        Column(
            modifier = modifier.padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Messages stay on this phone. The model runs on your computer.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Set the server address, then ask it to change the media volume.",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        modifier = modifier.padding(horizontal = 12.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages) { message ->
            MessageRow(message)
        }
    }
}

@Composable
private fun MessageRow(message: Message) {
    when (message) {
        is Message.System -> Unit
        is Message.User -> Bubble(label = "You", text = message.content, alignEnd = true)
        is Message.Assistant -> Bubble(label = "Assistant", text = message.content, alignEnd = false)
        is Message.ToolCall -> Note("Tool: ${message.name}(${message.argumentsJson})")
        is Message.ToolResult -> {
            val prefix = if (message.success) "Tool result" else "Tool error"
            Note("$prefix: ${message.content}")
        }
    }
}

@Composable
private fun Bubble(
    label: String,
    text: String,
    alignEnd: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (alignEnd) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = text.ifBlank { "(empty response)" },
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Composer(
    draft: String,
    busy: Boolean,
    error: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRetry, enabled = !busy) {
                Text("Retry")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                enabled = !busy,
                placeholder = { Text("Message") },
                maxLines = 4,
            )
            Button(
                onClick = onSend,
                enabled = !busy && draft.isNotBlank(),
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun SettingsPage(
    initial: ServerSettings,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (ServerSettings) -> Unit,
) {
    var baseUrl by remember(initial) { mutableStateOf(initial.baseUrl) }
    var model by remember(initial) { mutableStateOf(initial.model) }
    var apiKey by remember(initial) { mutableStateOf(initial.apiKey) }
    var timeout by remember(initial) { mutableStateOf(initial.timeoutSeconds.toString()) }
    var searchUrl by remember(initial) { mutableStateOf(initial.searchBaseUrl) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Server", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "OpenAI-compatible root, usually ending in /v1. The app calls /chat/completions on it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server address") },
                    placeholder = { Text(ServerSettings.DEFAULT_BASE_URL) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model name") },
                    placeholder = { Text("llama3.2") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { timeout = it.filter { ch -> ch.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Timeout seconds") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("Search", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Separate from the model. The phone POSTs /search to this address. Leave blank to keep chat and volume without web search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = searchUrl,
                    onValueChange = { searchUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search service address") },
                    placeholder = { Text(ServerSettings.DEFAULT_SEARCH_BASE_URL) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (error != null) {
                    Text(text = error, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSave(
                                ServerSettings(
                                    baseUrl = baseUrl,
                                    model = model,
                                    apiKey = apiKey,
                                    timeoutSeconds = timeout.toIntOrNull() ?: -1,
                                    searchBaseUrl = searchUrl,
                                ),
                            )
                        },
                    ) {
                        Text("Save")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
