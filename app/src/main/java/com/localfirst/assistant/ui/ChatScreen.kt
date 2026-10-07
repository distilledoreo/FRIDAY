package com.localfirst.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.presentation.Transcript
import com.localfirst.assistant.presentation.TranscriptItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val items = remember(state.messages, state.busy) { Transcript.items(state.messages, state.busy) }

    fun closeDrawerThen(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("Copied")
        }
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawer(
                conversations = state.conversations,
                currentId = state.conversationId,
                onNewChat = { closeDrawerThen(viewModel::newChat) },
                onOpen = { id -> closeDrawerThen { viewModel.openConversation(id) } },
                onRename = viewModel::renameConversation,
                onDelete = viewModel::deleteConversation,
                onOpenSettings = { closeDrawerThen(viewModel::openSettings) },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Chats")
                        }
                    },
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = state.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.settings.model.isNotBlank()) {
                                Text(
                                    text = state.settings.model,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::newChat, enabled = state.messages.isNotEmpty()) {
                            Icon(Icons.Filled.Create, contentDescription = "New chat")
                        }
                    },
                )
            },
            bottomBar = {
                Composer(
                    draft = state.draft,
                    busy = state.busy,
                    editing = state.editingIndex != null,
                    onDraftChange = viewModel::onDraftChange,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    onCancelEdit = viewModel::cancelEditing,
                    modifier = Modifier.navigationBarsPadding().imePadding(),
                )
            },
        ) { padding ->
            if (items.isEmpty() && state.error == null) {
                EmptyState(onSuggestion = viewModel::sendSuggestion, modifier = Modifier.padding(padding))
            } else {
                // Reversed so the newest content stays pinned to the bottom while it streams.
                LazyColumn(
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
                ) {
                    val error = state.error
                    if (error != null && !state.busy) {
                        item(key = "error") {
                            ErrorCard(message = error, retryEnabled = !state.busy, onRetry = viewModel::retry)
                        }
                    }
                    items(items.asReversed(), key = { it.key }) { item ->
                        when (item) {
                            is TranscriptItem.User -> UserMessage(
                                item = item,
                                actionsEnabled = !state.busy,
                                onCopy = ::copy,
                                onEdit = viewModel::startEditing,
                            )
                            is TranscriptItem.Assistant -> AssistantMessage(
                                item = item,
                                actionsEnabled = !state.busy,
                                onCopy = ::copy,
                                onRegenerate = viewModel::regenerate,
                            )
                            is TranscriptItem.ToolActivity -> ToolActivityCard(item)
                            TranscriptItem.Thinking -> ThinkingIndicator()
                        }
                    }
                }
            }
        }
    }

    if (state.showSettings) {
        SettingsPage(
            initial = state.settings,
            error = state.settingsError,
            onDismiss = viewModel::dismissSettings,
            onSave = viewModel::saveSettings,
        )
    }
}
