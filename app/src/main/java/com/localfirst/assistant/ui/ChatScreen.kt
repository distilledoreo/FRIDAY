package com.localfirst.assistant.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.core.view.WindowCompat
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import android.widget.Toast
import android.app.Activity
import android.media.projection.MediaProjectionManager
import com.localfirst.assistant.voice.ScreenContextService
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
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
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    var attachmentMenu by remember { mutableStateOf(false) }
    var imageOptions by remember { mutableStateOf(false) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.setAttachmentPickerOpen(false)
        viewModel.addAttachments(uris)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.setAttachmentPickerOpen(false)
        viewModel.addAttachments(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        viewModel.setAttachmentPickerOpen(false)
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
    val sharingScreen by ScreenContextService.active.collectAsState()
    val screenCapture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.setAttachmentPickerOpen(false)
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { ScreenContextService.start(context, it) }
    }
    val state by viewModel.state.collectAsState()
    val voice by viewModel.voiceState.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val palette = LocalFridayPalette.current
    val drawerWidth = minOf(320.dp, LocalConfiguration.current.screenWidthDp.dp - 24.dp)
    val drawerPixels = with(LocalDensity.current) { drawerWidth.toPx() }
    val drawerProgress = if(drawerState.currentOffset.isNaN()) { if(drawerState.isOpen)1f else 0f } else (1f+drawerState.currentOffset/drawerPixels).coerceIn(0f,1f)
    var dashboardOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.showWorkspace,state.showSettings) {
        if(state.showWorkspace||state.showSettings) { dashboardOpen=false;imageOptions=false }
    }
    LaunchedEffect(dashboardOpen,state.projectId,state.privacy.incognito) {
        if(dashboardOpen&&!state.privacy.incognito)viewModel.refreshDashboard()else viewModel.clearDashboard()
    }
    var composerHeight by remember { mutableIntStateOf(80) }
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            val bars=WindowCompat.getInsetsController(window,window.decorView)
            bars.isAppearanceLightStatusBars=!palette.dark&&!state.privacy.incognito
            bars.isAppearanceLightNavigationBars=!palette.dark
        }
    }
    val scope = rememberCoroutineScope()
    val backGestureInset=WindowInsets.systemGestures.getLeft(LocalDensity.current,androidx.compose.ui.platform.LocalLayoutDirection.current)
    val edgeWidth=with(LocalDensity.current) { 24.dp.toPx() }
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val items = remember(state.messages, state.busy) { Transcript.items(state.messages, state.busy) }

    fun closeDrawerThen(action: () -> Unit) {
        scope.launch { if(palette.reducedMotion)drawerState.snapTo(DrawerValue.Closed)else drawerState.animateTo(DrawerValue.Closed,tween(220)) }
        action()
    }

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("Copied")
        }
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { if(palette.reducedMotion)drawerState.snapTo(DrawerValue.Closed)else drawerState.animateTo(DrawerValue.Closed,tween(220)) } }

    val defaultUriHandler = LocalUriHandler.current
    val workspaceUriHandler = remember(defaultUriHandler, viewModel) { object : UriHandler {
        override fun openUri(uri: String) {
            if (uri.startsWith("assistant://artifact/")) viewModel.openArtifact(uri) else defaultUriHandler.openUri(uri)
        }
    } }
    CompositionLocalProvider(LocalUriHandler provides workspaceUriHandler) {
    Box(modifier = Modifier.fillMaxSize().pointerInput(backGestureInset,edgeWidth,palette.reducedMotion) {
        // Listen as an ancestor without stealing taps; leave Android's back strip to the system.
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
            if(drawerState.isOpen||down.position.x<backGestureInset||down.position.x>backGestureInset+edgeWidth)return@awaitEachGesture
            var accepted=false
            do {
                val event=awaitPointerEvent(PointerEventPass.Initial)
                val change=event.changes.firstOrNull { it.id==down.id }?:break
                val delta=change.position-down.position
                if(delta.x>edgeWidth&&delta.x>kotlin.math.abs(delta.y)*1.5f)accepted=true
                if(accepted)change.consume()
                val pressed=change.pressed
            } while(pressed)
            if(accepted)scope.launch { if(palette.reducedMotion)drawerState.snapTo(DrawerValue.Open)else drawerState.animateTo(DrawerValue.Open,tween(220)) }
        }
    }) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                MaterialTheme(colorScheme=if(state.privacy.incognito)com.localfirst.assistant.ui.theme.fridayColors(true,palette.accent)else MaterialTheme.colorScheme) {
                ChatDrawer(
                    width = drawerWidth,
                    conversations = state.conversations,
                    query = state.historyQuery,
                    onQuery = viewModel::searchHistory,
                    projects = state.knowledge.projects,
                    activeProjectId = state.projectId,
                    onProject = { id -> closeDrawerThen { viewModel.openProject(id) } },
                    onProjects = { closeDrawerThen { viewModel.openWorkspace(WorkspaceDestination.PROJECTS) } },
                    onTasks = { closeDrawerThen { viewModel.openWorkspace(WorkspaceDestination.TASKS) } },
                    onActivity = { closeDrawerThen { viewModel.openWorkspace(WorkspaceDestination.ACTIVITY) } },
                    onFiles = { closeDrawerThen { viewModel.openWorkspace(WorkspaceDestination.FILES) } },
                    onImages = { closeDrawerThen { viewModel.openWorkspace(WorkspaceDestination.IMAGES) } },
                    currentId = state.conversationId,
                    onNewChat = { closeDrawerThen(viewModel::newChat) },
                    onIncognito = { closeDrawerThen { viewModel.startIncognito() } },
                    onOpen = { id -> closeDrawerThen { viewModel.openConversation(id) } },
                    onRename = viewModel::renameConversation,
                    onDelete = viewModel::deleteConversation,
                    onOpenSettings = { closeDrawerThen { viewModel.openWorkspace(WorkspaceDestination.SETTINGS) } },
                )
                }
            },
        ) {
            val fridayBusy = state.agentTasks.any { it.optString("status") in FRIDAY_WORKING || it.optString("status") in FRIDAY_NEEDS_YOU }
            FridayVisibleEffect(fridayBusy, state.privacy.incognito) {
                if (!state.privacy.incognito) while (true) {
                    viewModel.pollFriday()
                    delay(if (fridayBusy) 3_000 else 60_000)
                }
            }
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0,0,0,0),
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    Row(Modifier.fillMaxWidth().background(if(state.privacy.incognito)androidx.compose.ui.graphics.Color(0xFF1B1B1E)else MaterialTheme.colorScheme.background).statusBarsPadding().height(64.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        if (!state.privacy.incognito) FridayStatusButton(state) { viewModel.openFriday() }
                            MoreActions(buildList {
                                add("Personal dashboard" to { dashboardOpen = true; viewModel.refreshDashboard() })
                                if (state.privacy.incognito) add("Exit incognito" to viewModel::newChat) else add("Start incognito chat" to { viewModel.startIncognito() })
                                if (state.privacy.incognito) add((if (state.privacy.freshSlate) "Use saved memories (new incognito chat)" else "Fresh slate (new incognito chat)") to { viewModel.startIncognito(!state.privacy.freshSlate) })
                                add((if (state.projectId == null) "Choose project" else "Project details") to {
                                    state.projectId?.let(viewModel::openProject) ?: viewModel.openWorkspace(WorkspaceDestination.PROJECTS)
                                })
                                if (state.projectId != null) add("Remove from project" to { viewModel.selectProject(null) })
                                if (state.conversationId != null) add("Export chat" to { viewModel.openWorkspace(WorkspaceDestination.DATA) })
                            },contentColor=if(state.privacy.incognito)androidx.compose.ui.graphics.Color(0xFFF2F1EE)else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.privacy.incognito) TextButton(shape = MaterialTheme.shapes.small, onClick = viewModel::newChat, enabled = !state.workspaceBusy,colors=androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor=com.localfirst.assistant.ui.theme.readableAccent(palette.accent,androidx.compose.ui.graphics.Color(0xFF1B1B1E)))) { Text("Exit") }
                            else if (state.messages.isNotEmpty() || state.draft.isNotBlank() || state.draftAttachments.isNotEmpty() || state.projectId != null) IconButton(onClick = viewModel::newChat, enabled = !state.busy) {
                                Icon(Icons.Filled.Create, contentDescription = "New chat")
                            }
                    }
                },
            ) { padding ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().imePadding()) {
                    val keyboardOpen=WindowInsets.ime.getBottom(LocalDensity.current)>0
                    val conversation=items.isNotEmpty()||voice!=null||state.error!=null||state.voiceError!=null
                    val emptyOffset=minOf(maxHeight*.34f,(maxHeight-with(LocalDensity.current) { composerHeight.toDp() }-48.dp).coerceAtLeast(0.dp))
                    val homeOffset by animateDpAsState(if(conversation||keyboardOpen)0.dp else emptyOffset,tween(palette.transitionMillis),label="composer position")
                    if(!state.privacy.incognito && (state.messages.isNotEmpty()||state.projectId!=null))Text(state.knowledge.projects.firstOrNull { it.id==state.projectId }?.name?:state.title,Modifier.align(Alignment.TopCenter).padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if (state.privacy.incognito) Text("Incognito · ${if(state.privacy.freshSlate) "Fresh slate" else "Won’t be saved"}", style=MaterialTheme.typography.labelMedium, color=MaterialTheme.colorScheme.onSurfaceVariant, modifier=Modifier.align(Alignment.TopCenter).padding(top=8.dp))
                    FridayAmbient(voice?.phase,voice?.level?:0f,state.busy,Modifier.fillMaxSize().alpha(if(conversation).14f else 1f))
                    if(items.isNotEmpty()||state.error!=null||state.voiceError!=null) {
                    LazyColumn(
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize().padding(top=if(state.privacy.incognito||state.messages.isNotEmpty()||state.projectId!=null)18.dp*LocalDensity.current.fontScale+16.dp else 0.dp).padding(bottom = with(LocalDensity.current) { composerHeight.toDp() }),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
                    ) {
                        state.pendingApproval?.let { approval ->
                            item(key = "approval") {
                                ApprovalCard(approval = approval, onAnswer = viewModel::answerApproval)
                            }
                        }
                        state.voiceError?.let { error -> item(key="voice-error") {
                            Column {
                                ErrorCard(error,!state.busy,viewModel::startVoice)
                                TextButton(shape = MaterialTheme.shapes.small, onClick=viewModel::closeVoice) { Text("Continue with text") }
                            }
                        } }
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
                                is TranscriptItem.ToolActivity -> ToolActivityCard(item, viewModel::loadImage, viewModel::openArtifact) { FridayTaskCard(it, state, viewModel) }
                                TranscriptItem.Thinking -> ThinkingIndicator()
                            }
                        }
                    }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom=homeOffset).onSizeChanged { composerHeight=it.height }) {
                        if(sharingScreen)Text("Screen context shared",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                        state.imageStatus?.let { Text(it,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.labelSmall) }
                        voice?.let { InlineVoice(it,viewModel::pauseVoiceFromChat,viewModel::startVoice,viewModel::interruptVoice,viewModel::closeVoice) }
                        state.groundingStatus?.let { status ->
                            Text(status, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                            if (state.groundingResearchAvailable) TextButton(shape = MaterialTheme.shapes.small, onClick = viewModel::proposeGroundedResearch, enabled = !state.busy && !state.workspaceBusy) { Text("Propose deeper research in Activity") }
                            if (state.groundingSources.isNotEmpty()) androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState())) {
                                state.groundingSources.take(5).forEach { source -> TextButton(shape = MaterialTheme.shapes.small, onClick = { defaultUriHandler.openUri(source.url) }) { Text(source.title.take(35), maxLines = 1) } }
                            }
                        }
                        if (state.privacy.incognito) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Won’t be saved · Fresh slate", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                androidx.compose.material3.Switch(state.privacy.freshSlate, { viewModel.startIncognito(it) }, enabled = !state.busy && !state.workspaceBusy)
                            }
                            Text(if (state.privacy.freshSlate) "No memories. Ends when you leave the app or start another chat." else "Saved memories are available. Ends when you leave the app or start another chat.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                        }
                        Box {
                        Composer(
                            draft = state.draft,
                            busy = state.busy,
                            editing = state.editingIndex != null,
                            onDraftChange = viewModel::onDraftChange,
                            onSend = viewModel::send,
                            imageMode = state.imageMode,
                            imageSummary = if (state.imageSettings.automatic) "Auto" else "${state.imageSettings.width}×${state.imageSettings.height}",
                            onImageSettings = { imageOptions = true },
                            onExitImageMode = { viewModel.setImageMode(false) },
                            onStop = viewModel::stop,
                            onCancelEdit = viewModel::cancelEditing,
                            onVoice = viewModel::toggleVoice,
                            voiceActive = voice != null,
                            sendEnabled = state.canSend,
                            attachments = state.draftAttachments,
                            editingHasAttachments = (state.messages.getOrNull(state.editingIndex ?: -1) as? Message.User)
                                ?.attachments?.isNotEmpty() == true,
                            onAttach = { attachmentMenu = true },
                            onRemoveAttachment = viewModel::removeDraftAttachment,
                            modifier = Modifier,
                        )
                        DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                            DropdownMenuItem(text = { Text("Model and voice") }, onClick = { attachmentMenu = false; viewModel.openSettings() })
                            DropdownMenuItem(text = { Text("Tools and integrations") }, onClick = { attachmentMenu = false; viewModel.openWorkspace(WorkspaceDestination.SETTINGS) })
                            DropdownMenuItem(text = { Text("Search the web") }, onClick = { attachmentMenu = false; viewModel.onDraftChange("Search the web for: " + state.draft) })
                            DropdownMenuItem(text = { Text("Create image") }, onClick = {
                                attachmentMenu = false
                                viewModel.setImageMode(true)
                                imageOptions = true
                            })
                            DropdownMenuItem(text = { Text("Photos") }, onClick = {
                                attachmentMenu = false
                                viewModel.setAttachmentPickerOpen(true)
                                photos.launch(arrayOf("image/*"))
                            })
                            DropdownMenuItem(text = { Text("Take photo") }, onClick = {
                                attachmentMenu = false
                                try {
                                    val dir = File(context.cacheDir, if (state.privacy.incognito) "incognito/camera" else "camera").apply { mkdirs() }
                                    val photo = File.createTempFile("photo-", ".jpg", dir)
                                    cameraPath = photo.absolutePath
                                    viewModel.setAttachmentPickerOpen(true)
                                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", photo))
                                } catch (e: Exception) {
                                    cameraPath?.let { File(it).delete() }
                                    cameraPath = null
                                    viewModel.setAttachmentPickerOpen(false)
                                    Toast.makeText(context, "Couldn't open a camera app.", Toast.LENGTH_SHORT).show()
                                }
                            })
                            DropdownMenuItem(text = { Text(if (sharingScreen) "Stop screen context" else "Share screen context") }, onClick = {
                                attachmentMenu = false
                                if (sharingScreen) ScreenContextService.stop(context)
                                else { viewModel.setAttachmentPickerOpen(true); screenCapture.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
                            })
                            DropdownMenuItem(text = { Text("Files") }, onClick = {
                                attachmentMenu = false
                                viewModel.setAttachmentPickerOpen(true)
                                files.launch(arrayOf("*/*"))
                            })
                        }
                        }
                    }
                    if(!conversation&&!keyboardOpen) {
                        TextButton(shape = MaterialTheme.shapes.small, onClick={dashboardOpen=true;viewModel.refreshDashboard()},modifier=Modifier.align(Alignment.BottomCenter).height(48.dp).width(120.dp)
                            .semantics { contentDescription="Open personal dashboard" }
                            .pointerInput(Unit) { var dragged=0f;detectVerticalDragGestures(onDragStart={dragged=0f},onDragEnd={if(dragged < -24.dp.toPx()){dashboardOpen=true;viewModel.refreshDashboard()}}) { change,amount -> dragged+=amount;change.consume() } }) {
                            Box(Modifier.width(32.dp).height(3.dp).background(MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(2.dp)))
                        }
                    }
                }
            }
        }
        FridayBrand(drawerProgress,{scope.launch { if(drawerState.targetValue==DrawerValue.Open) { if(palette.reducedMotion)drawerState.snapTo(DrawerValue.Closed)else drawerState.animateTo(DrawerValue.Closed,tween(220)) } else { if(palette.reducedMotion)drawerState.snapTo(DrawerValue.Open)else drawerState.animateTo(DrawerValue.Open,tween(220)) } }},Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start=16.dp,top=4.dp),foreground=if(state.privacy.incognito)androidx.compose.ui.graphics.Color(0xFFF2F1EE)else MaterialTheme.colorScheme.onBackground)


    }

    if (state.showWorkspace) WorkspacePage(state, viewModel)
    }
    if (dashboardOpen && !state.showWorkspace && !state.showSettings) {
        FridaySheet(onDismiss={dashboardOpen=false}) { expanded,toggle,close ->
            PersonalDashboard(state,viewModel,onClose=close,expanded=expanded,onToggle=toggle)
        }
    }
    if (imageOptions && !state.showSettings && !state.showWorkspace) {
        FridaySheet(onDismiss = { imageOptions = false }) { _,_,close ->
            ImageOptions(state, viewModel,close)
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
