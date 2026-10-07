package com.localfirst.assistant.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import android.net.Uri
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.presentation.Transcript
import com.localfirst.assistant.presentation.TranscriptItem
import com.localfirst.assistant.voice.VoicePhase
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    var attachmentMenu by remember { mutableStateOf(false) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addAttachments(uris)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addAttachments(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        cameraPath?.let { path ->
            val photo = File(path)
            if (saved) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", photo)
                viewModel.addAttachments(listOf(uri), deleteSource = { photo.delete() })
            } else {
                photo.delete()
            }
        }
        cameraPath = null
    }
    val state by viewModel.state.collectAsState()
    val voice by viewModel.voiceState.collectAsState()
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                            IconButton(onClick = viewModel::newChat, enabled = state.messages.isNotEmpty() || state.draft.isNotBlank() || state.draftAttachments.isNotEmpty()) {
                                Icon(Icons.Filled.Create, contentDescription = "New chat")
                            }
                        },
                    )
                },
                bottomBar = {
                    Box {
                        Composer(
                            draft = state.draft,
                            busy = state.busy,
                            editing = state.editingIndex != null,
                            onDraftChange = viewModel::onDraftChange,
                            onSend = viewModel::send,
                            onStop = viewModel::stop,
                            onCancelEdit = viewModel::cancelEditing,
                            onVoice = viewModel::startVoice,
                            sendEnabled = state.canSend,
                            attachments = state.draftAttachments,
                            editingHasAttachments = (state.messages.getOrNull(state.editingIndex ?: -1) as? Message.User)
                                ?.attachments?.isNotEmpty() == true,
                            onAttach = { attachmentMenu = true },
                            onRemoveAttachment = viewModel::removeDraftAttachment,
                            modifier = Modifier.navigationBarsPadding().imePadding(),
                        )
                        DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                            DropdownMenuItem(text = { Text("Photos") }, onClick = {
                                attachmentMenu = false
                                photos.launch(arrayOf("image/*"))
                            })
                            DropdownMenuItem(text = { Text("Take photo") }, onClick = {
                                attachmentMenu = false
                                try {
                                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                                    val photo = File.createTempFile("photo-", ".jpg", dir)
                                    cameraPath = photo.absolutePath
                                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", photo))
                                } catch (e: Exception) {
                                    cameraPath?.let { File(it).delete() }
                                    cameraPath = null
                                    Toast.makeText(context, "Couldn't open a camera app.", Toast.LENGTH_SHORT).show()
                                }
                            })
                            DropdownMenuItem(text = { Text("Files") }, onClick = {
                                attachmentMenu = false
                                files.launch(arrayOf("*/*"))
                            })
                        }
                    }
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
                        state.pendingApproval?.let { approval ->
                            item(key = "approval") {
                                ApprovalCard(approval = approval, onAnswer = viewModel::answerApproval)
                            }
                        }
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

        voice?.let { current ->
            VoiceOverlay(
                voice = current,
                approval = state.pendingApproval,
                bargeIn = state.settings.voiceBargeIn,
                onOrbTap = {
                    if (current.phase == VoicePhase.PAUSED) viewModel.startVoice() else viewModel.interruptVoice()
                },
                onAnswerApproval = viewModel::answerApproval,
                onClose = viewModel::closeVoice,
            )
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
