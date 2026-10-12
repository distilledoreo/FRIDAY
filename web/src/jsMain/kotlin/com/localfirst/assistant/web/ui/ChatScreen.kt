package com.localfirst.assistant.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.web.data.ChatMessage
import com.localfirst.assistant.web.state.ChatViewModel
import com.localfirst.assistant.web.theme.FridaySpacing
import com.localfirst.assistant.web.theme.LocalFridayPalette

@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val conversations by vm.conversations.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val draft by vm.draft.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val active = conversations.firstOrNull { it.id == activeId }
    var drawerOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = FridaySpacing.small, vertical = FridaySpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderCircleButton(onClick = { drawerOpen = true })
                Spacer(Modifier.width(FridaySpacing.small))
                Text("FRIDAY", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.weight(1f))
                ProfileButton()
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (active == null || active.messages.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Ask FRIDAY anything", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(FridaySpacing.medium),
                            verticalArrangement = Arrangement.spacedBy(FridaySpacing.medium),
                        ) {
                            items(active.messages, key = { it.hashCode() }) { m -> MessageRow(m) }
                            if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(FridaySpacing.small)); Text("FRIDAY is typing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) } }
                        }
                    }
                }
                if (error != null) {
                    Text(error!!, Modifier.fillMaxWidth().padding(horizontal = FridaySpacing.medium, vertical = FridaySpacing.tiny), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Composer(
                    text = draft,
                    onText = vm::setDraft,
                    onSend = vm::send,
                    busy = busy,
                )
            }

            if (drawerOpen) {
                Drawer(
                    conversations = conversations,
                    activeId = activeId,
                    onSelect = { vm.select(it); drawerOpen = false },
                    onNew = { vm.newChat(); drawerOpen = false },
                    onDelete = vm::delete,
                    onSettings = { drawerOpen = false; onOpenSettings() },
                    onDismiss = { drawerOpen = false },
                )
            }
        }
    }
}

@Composable
private fun HeaderCircleButton(onClick: () -> Unit) {
    val palette = LocalFridayPalette.current
    Box(
        Modifier.size(FridaySpacing.touch).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("☰", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ProfileButton() {
    Box(
        Modifier.size(FridaySpacing.touch).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text("F", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun MessageRow(m: ChatMessage) {
    val isUser = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (isUser) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            tonalElevation = 0.dp,
        ) {
            Text(
                m.content,
                Modifier.padding(horizontal = FridaySpacing.medium, vertical = FridaySpacing.small),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun Composer(text: String, onText: (String) -> Unit, onSend: () -> Unit, busy: Boolean) {
    Surface(Modifier.fillMaxWidth().padding(FridaySpacing.medium), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(start = FridaySpacing.medium, end = FridaySpacing.small, top = FridaySpacing.small, bottom = FridaySpacing.small), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message FRIDAY", color = MaterialTheme.colorScheme.secondary) },
                shape = RoundedCornerShape(20.dp),
                maxLines = 6,
            )
            Spacer(Modifier.width(FridaySpacing.small))
            FilledIconButton(onClick = onSend, enabled = text.isNotBlank() && !busy, modifier = Modifier.size(40.dp)) {
                Text("➤", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun Drawer(
    conversations: List<com.localfirst.assistant.web.data.Conversation>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onDismiss)) {
        Surface(Modifier.fillMaxHeight().width(300.dp).clickable(onClick = {}), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxSize().padding(FridaySpacing.medium)) {
                FilledTonalButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("New chat") }
                Spacer(Modifier.height(FridaySpacing.medium))
                LazyColumn(Modifier.weight(1f)) {
                    items(conversations, key = { it.id }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (c.id == activeId) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                                .clickable { onSelect(c.id) }
                                .padding(horizontal = FridaySpacing.small, vertical = FridaySpacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.title.ifBlank { "New chat" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("✕", Modifier.clip(CircleShape).clickable { onDelete(c.id) }.padding(FridaySpacing.tiny), color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                Spacer(Modifier.height(FridaySpacing.small))
                TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
            }
        }
    }
}

@Composable
fun SettingsDialog(vm: ChatViewModel, settings: com.localfirst.assistant.web.data.ServerSettingsStore, onDismiss: () -> Unit) {
    val current = settings.state.value
    var baseUrl by remember { mutableStateOf(current.baseUrl) }
    var apiKey by remember { mutableStateOf(current.apiKey) }
    var model by remember { mutableStateOf(current.model) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(FridaySpacing.small))
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(FridaySpacing.small))
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { settings.save(com.localfirst.assistant.web.data.ServerSettings(baseUrl, apiKey, model)); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
