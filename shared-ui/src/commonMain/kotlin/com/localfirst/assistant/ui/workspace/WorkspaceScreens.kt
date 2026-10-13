package com.localfirst.assistant.ui.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.ui.*
import com.localfirst.assistant.presentation.Transcript
import com.localfirst.assistant.presentation.TranscriptItem
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.localfirst.assistant.ui.AppearanceControls
import com.localfirst.assistant.client.*
import com.localfirst.assistant.settings.*
import com.localfirst.assistant.client.*
import com.localfirst.assistant.ui.theme.FridaySpacing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

private enum class Page(val label: String) { CHAT("Chat"), PROJECTS("Projects"), MEMORY("Memory"), TASKS("Tasks"), FILES("Library"), IMAGES("Images"), ACTIVITY("FRIDAY"), ACCOUNTS("Accounts"), SETTINGS("Settings") }

@Composable
fun WorkspaceApp(vm: WorkspaceController, state: WorkspaceState,
    features: FeatureController? = null, code: com.localfirst.assistant.ui.code.CodeWorkspace? = null,
    onAttach: (() -> Unit)? = null, onLibraryUpload: (() -> Unit)? = null,
    onDictate: (() -> Unit)? = null, dictating: Boolean = false,
    onVoice: (() -> Unit)? = null, voiceActive: Boolean = false, voiceStatus: String = "",
    settingsExtras: @Composable ColumnScope.() -> Unit = {}) {
    var projectPage by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(Page.CHAT) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.connected) { if (!state.connected) { page = Page.CHAT; drawer.snapTo(DrawerValue.Closed) } }
    if (!state.connected) { ConnectScreen(vm, state); return }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 900.dp
        val idle = !state.busy && state.activeJob == null
        fun go(target: Page) { if (target in listOf(Page.FILES, Page.IMAGES, Page.ACTIVITY, Page.ACCOUNTS, Page.TASKS) && state.incognito) { if (!idle) return; vm.newChat() }; page = target; scope.launch { drawer.close() } }
        // The same sidebar and header as the phone. Destinations this client lacks are passed as null and left out.
        val sidebar: @Composable () -> Unit = {
            ChatDrawer(
                width = if (wide) 300.dp else 320.dp,
                conversations = state.filteredChats.map { it.conversation.summary },
                currentId = state.activeId.takeIf { page == Page.CHAT },
                onNewChat = { vm.newChat(); go(Page.CHAT) },
                onIncognito = { vm.newIncognito(); go(Page.CHAT) },
                onOpen = { vm.select(it); go(Page.CHAT) },
                onRename = { id, title -> vm.rename(title, state.chats.firstOrNull { it.item.id == id }) },
                onDelete = { vm.delete(it) },
                onOpenSettings = { go(Page.SETTINGS) },
                query = state.query,
                onQuery = vm::search,
                projects = state.knowledge.projects,
                activeProjectId = state.projectId.takeIf { page == Page.CHAT },
                onProject = { projectPage = it; go(Page.PROJECTS) },
                onProjects = { projectPage = null; go(Page.PROJECTS) },
                onActivity = features?.let { { go(Page.ACTIVITY) } },
                onFiles = features?.let { { go(Page.FILES) } },
                onImages = features?.let { { go(Page.IMAGES) } },
                destinations = buildList { addAll(listOf(
                    DrawerDestination("Memory", LineIcons.Memory, page == Page.MEMORY) { go(Page.MEMORY) },
                    DrawerDestination("Tasks", LineIcons.Clock, page == Page.TASKS) { go(Page.TASKS) },
                )); if (code != null) add(DrawerDestination("Code", LineIcons.Code, false) { if (idle) { if (state.incognito) vm.newChat(); code.open() } }) },
                deleteNote = "will be removed from history on every client. Its recall archive and approved memories stay managed in Memory.",
            )
        }
        val content: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    val working = state.activeJob != null || state.tasks.any { it["status"]?.jsonPrimitive?.contentOrNull == "running" }
                    HomeHeaderActions(
                        showNewChat = page != Page.CHAT || state.active != null || state.draft.isNotBlank() || state.attachments.isNotEmpty() || state.projectId != null,
                        onNewChat = { if (idle) { vm.newChat(); page = Page.CHAT } },
                        friday = FridayStatus("FRIDAY. " + if (working) "Working" else "Work on your computer", if (working) LocalFridayPalette.current.accent else null),
                        onFriday = { go(if (features != null) Page.ACTIVITY else Page.TASKS) },
                        profileName = state.preferences.displayName,
                        menu = buildList {
                            add((if (state.projectId == null) "Choose project" else "Projects") to { page = Page.PROJECTS })
                            if (state.projectId != null && page == Page.CHAT && idle) add("Remove from project" to { vm.selectProject(null) })
                            add("Memory" to { page = Page.MEMORY })
                            if (features != null) add("Accounts" to { go(Page.ACCOUNTS) })
                            add("Settings" to { page = Page.SETTINGS })
                            add("Lock" to { vm.lock() })
                        },
                    )
                }
                state.error?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                            TextButton({ vm.dismissError() }) { Text("Dismiss") }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when(page) { Page.CHAT -> ChatScreen(vm, state, onAttach, onDictate, dictating, onVoice, voiceActive, voiceStatus); Page.PROJECTS -> ProjectsScreen(vm, state, selectedProjectId = projectPage, openChat = { page = Page.CHAT }); Page.MEMORY -> MemoryScreen(vm, state); Page.TASKS -> TasksScreen(vm, state); Page.SETTINGS -> SettingsScreen(vm, state, settingsExtras); Page.FILES -> features?.let { LibraryScreen(it, { vm.artifact(it) }, onLibraryUpload) }; Page.IMAGES -> features?.let { ImagesScreen(it) { vm.artifact(it) } }; Page.ACTIVITY -> features?.let { ActivityScreen(it) { report -> vm.newChat(); vm.setDraft("Discuss this report:\n$report"); page = Page.CHAT } }; Page.ACCOUNTS -> features?.let { AccountsScreen(it) } }
                }
            }
        }
        if (wide) Row(Modifier.fillMaxSize()) { sidebar(); content() }
        else ModalNavigationDrawer(drawerState = drawer, drawerContent = { sidebar() }) { content() }
        // The F is the menu button, and the open sidebar's wordmark; on a wide screen the sidebar is always open.
        val drawerPixels = with(LocalDensity.current) { 320.dp.toPx() }
        val opened = when {
            wide -> 1f
            drawer.currentOffset.isNaN() -> if (drawer.isOpen) 1f else 0f
            else -> (1f + drawer.currentOffset / drawerPixels).coerceIn(0f, 1f)
        }
        code?.let { com.localfirst.assistant.ui.code.CodeScreen(it) }
        FridayBrand(opened, { if (!wide) scope.launch { if (drawer.isOpen) drawer.close() else drawer.open() } }, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 4.dp))
    }
}

@Composable
private fun ConnectScreen(vm: WorkspaceController, state: WorkspaceState) {
    var address by remember { mutableStateOf("") }; var token by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("FRIDAY", style = MaterialTheme.typography.headlineLarge)
            Text("One conversation, wherever you are.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
            Text("Connect to the same computer as your Android app to share history, projects, memory and preferences.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(address, { address = it }, label = { Text("Computer address (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
            OutlinedTextField(token, { token = it }, label = { Text("API token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
            Button({ vm.connect(address.trim(), token)?.invokeOnCompletion { if (vm.state.value.connected) token = "" } }, enabled = !state.busy && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "Connecting…" else "Connect") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Your token stays in this tab’s memory. Lock to disconnect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun ChatScreen(vm: WorkspaceController, state: WorkspaceState,
    onAttach: (() -> Unit)?, onDictate: (() -> Unit)?, dictating: Boolean,
    onVoice: (() -> Unit)?, voiceActive: Boolean, voiceStatus: String) {
    val scroll = rememberLazyListState()
    val messages = state.active?.conversation?.messages.orEmpty()
    val transcript = remember(messages, state.activeJob) { Transcript.items(messages, state.activeJob != null) }
    val clipboard = LocalClipboardManager.current
    val copy: (String) -> Unit = { clipboard.setText(AnnotatedString(it)) }
    LaunchedEffect(state.activeId, transcript.size, state.streamingText.length / 100) { if (transcript.isNotEmpty()) scroll.animateScrollToItem(if (state.streamingText.isBlank()) transcript.lastIndex else transcript.size) }
    val palette = LocalFridayPalette.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // As on the phone: FRIDAY's light fills an empty chat and dims behind a conversation, and the composer
    // rests a third of the way up until there is something to read.
    val conversation = transcript.isNotEmpty() || state.detachedReply != null
    val homeOffset by animateDpAsState(if (conversation) 0.dp else maxHeight * .34f, tween(palette.transitionMillis), label = "composer position")
    FridayAmbient(null, 0f, state.activeJob != null, Modifier.fillMaxSize().alpha(if (conversation) .14f else 1f))
    Column(Modifier.align(Alignment.TopCenter).fillMaxHeight().widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
        // A quiet label for where you are, with rename and delete kept on the chat's row in the sidebar.
        val project = state.knowledge.projects.firstOrNull { it.id == state.projectId }?.name
        (project ?: state.active?.conversation?.summary?.title)?.let { label ->
            Text(label, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(transcript, key = { it.key }) { item -> when(item) {
                    is TranscriptItem.User -> SharedUserMessage(item, actionsEnabled = false, onCopy = copy)
                    is TranscriptItem.Assistant -> SharedAssistantMessage(item, actionsEnabled = !state.busy, onCopy = copy,
                        onChoose = vm::setDraft, interfaceContent = { AssistantReply(item.text, state.preferences.interactiveAnswers, vm::setDraft, messages) })
                    is TranscriptItem.ToolActivity -> SharedToolActivityCard(item)
                    TranscriptItem.Thinking -> if (state.streamingText.isBlank()) ThinkingIndicator()
                } }
                if (state.streamingText.isNotBlank()) item(key = "streaming-reply") { NativeResponseContent(state.streamingText) }
                state.detachedReply?.let { reply -> item { Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(16.dp)) { Text("Reply saved separately because the chat changed", style = MaterialTheme.typography.titleSmall); AssistantReply(reply, state.preferences.interactiveAnswers, vm::setDraft) } } } }
            }
        }
        if (state.incognito) Text("Incognito · this conversation stays in tab memory until you leave or lock", style = MaterialTheme.typography.labelSmall)
        if (voiceStatus.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) { Text(voiceStatus, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); if (voiceActive) TextButton({ onVoice?.invoke() }) { Text("End voice") } }
        SharedComposer(state.draft, busy = state.activeJob != null, editing = false,
            onDraftChange = vm::setDraft, onSend = { vm.send() }, onStop = { vm.stop() },
            onCancelEdit = {}, onVoice = onVoice, voiceActive = voiceActive, onDictate = onDictate, dictating = dictating,
            onAttach = onAttach.takeUnless { state.incognito || state.busy }, hasAttachments = state.attachments.isNotEmpty(),
            attachmentContent = { AttachmentStrip(state.attachments, !state.busy && state.activeJob == null, vm::removeAttachment) }, sendEnabled = state.canSend)
        Spacer(Modifier.height(16.dp + homeOffset))
    }
    }
}

@Composable
private fun SettingsScreen(vm: WorkspaceController, state: WorkspaceState, extras: @Composable ColumnScope.() -> Unit) {
    val prefs = state.preferences
    ScreenColumn("Settings") {
        AppearanceControls(prefs, enabled = !state.busy,
            revision = { key -> state.items.firstOrNull { it.id == "preferences-$key" }?.revision ?: 0 },
            save = { key, value, revision -> vm.preference(key, value, revision) })
        HorizontalDivider()
        extras()
        OutlinedButton(vm::lock) { Text("Lock this client") }
    }
}

@Composable
fun MemoryScreen(vm: WorkspaceActions, state: WorkspaceState, extras: @Composable ColumnScope.() -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    var requested by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, state.busy) {
        if (!state.busy && requested != query) {
            kotlinx.coroutines.delay(350)
            if (vm.searchMemory(query) != null) requested = query
        }
    }
    var editing by remember { mutableStateOf<JsonObject?>(null) }; var adding by remember { mutableStateOf(false) }
    ScreenColumn("Memory") {
        Text("Approved facts and recall controls are shared across your clients on your computer.", color = MaterialTheme.colorScheme.secondary)
        extras()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.memoryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        for (control in memoryControls) SettingSwitch(control.label, state.memorySettings[control.key]?.jsonPrimitive?.booleanOrNull ?: control.default, !state.busy && state.memoryError == null) { vm.memorySetting(control.key, it) }
        Button({ adding = true }, enabled = !state.busy && state.memoryError == null) { Text("New memory") }
        if (state.knowledge.memories.isNotEmpty()) OutlinedButton({ vm.importOlderMemory() }, enabled = !state.busy) { Text("Import ${state.knowledge.memories.size} older saved memories") }
        OutlinedTextField(query, { query = it.take(2000) }, Modifier.fillMaxWidth(), label = { Text("Search approved memories") }, singleLine = true)
        for (m in state.memories) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(m["text"]?.jsonPrimitive?.content.orEmpty())
                Text(listOfNotNull(m["category"]?.jsonPrimitive?.contentOrNull, m["source_title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                m["quote"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { Text("Source: “$it”", style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton({ editing = m }, enabled = !state.busy) { Text("Edit") }
                    TextButton({ vm.forgetMemory(m.getValue("id").jsonPrimitive.content) }, enabled = !state.busy) { Text("Forget") }
                }
            }
        }
        if (state.memoryHasMore) OutlinedButton({ vm.moreMemories() }, enabled = !state.busy) { Text("Load more (${state.memories.size} of ${state.memoryTotal})") }
        HorizontalDivider(); Text("Recall archive", style = MaterialTheme.typography.titleLarge)
        Text("Exclude a source to keep it out of recall, including after future history updates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        for (source in state.archiveSources) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            val id = source.getValue("id").jsonPrimitive.content
            val excluded = source["excluded"]?.jsonPrimitive?.intOrNull == 1
            Column(Modifier.padding(16.dp)) {
                Text(source["title"]?.jsonPrimitive?.content.orEmpty())
                Row { TextButton({ vm.source(id) }, enabled = !state.busy) { Text("View source") }; TextButton({ vm.excludeSource(id, !excluded) }, enabled = !state.busy) { Text(if (excluded) "Allow recall" else "Exclude from recall") } }
            }
        }
        if (state.archiveSources.size < state.archiveTotal) OutlinedButton({ vm.moreSources() }, enabled = !state.busy) { Text("More archive sources (${state.archiveSources.size} of ${state.archiveTotal})") }
    }
    state.sourcePreview?.let { source -> AlertDialog(onDismissRequest = vm::closeSource, title = { Text(source["title"]?.jsonPrimitive?.content.orEmpty()) }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (entry in source["messages"]?.jsonArray.orEmpty()) { val message = entry.jsonObject; Text(message["role"]?.jsonPrimitive?.content.orEmpty(), style = MaterialTheme.typography.labelLarge); Text(message["content"]?.jsonPrimitive?.content.orEmpty()) }
            if (source["truncated"]?.jsonPrimitive?.booleanOrNull == true) Text("This preview shows a bounded excerpt.", color = MaterialTheme.colorScheme.secondary)
        }
    }, confirmButton = { TextButton(vm::closeSource) { Text("Close") } }) }
    if (adding || editing != null) EditTextDialog(if (editing == null) "New memory" else "Edit memory", editing?.get("text")?.jsonPrimitive?.content.orEmpty(), { adding = false; editing = null }) { text ->
        val old = editing
        vm.saveMemory(text, old?.get("id")?.jsonPrimitive?.content, old?.get("category")?.jsonPrimitive?.content ?: if (text.contains("prefer", true)) "preference" else "other", old?.get("scope")?.jsonPrimitive?.content ?: state.projectId.orEmpty(), old?.get("pinned")?.jsonPrimitive?.intOrNull != 0)
            ?.invokeOnCompletion { if (vm.state.value.error == null) { adding = false; editing = null } }
    }
}

@Composable
fun TasksScreen(vm: WorkspaceActions, state: WorkspaceState, extras: @Composable ColumnScope.() -> Unit = {}) {
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    ScreenColumn("Tasks") {
        Text("Computer work and results stay available across clients, including replies preserved after concurrent edits.", color = MaterialTheme.colorScheme.secondary)
        extras()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({ adding = true }, enabled = !state.busy) { Text("New task") }
        if (state.tasks.isEmpty()) Text("No computer tasks yet.")
        for (task in state.tasks) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            val id = task.getValue("id").jsonPrimitive.content
            val status = task["status"]?.jsonPrimitive?.content.orEmpty()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task["prompt"]?.jsonPrimitive?.content.orEmpty().take(500), style = MaterialTheme.typography.titleMedium)
                Text(status, color = MaterialTheme.colorScheme.secondary)
                task["result"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { AssistantReply(it.take(60000), state.preferences.interactiveAnswers, vm::setDraft) }
                task["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row {
                    val actions = when(status) { "running", "scheduled" -> listOf("pause", "cancel"); "paused", "failed" -> listOf("resume", "cancel", "delete"); else -> listOf("delete") }
                    for (action in actions) TextButton({ if (action == "delete") deleting = id else vm.taskAction(id, action) }, enabled = !state.busy) { Text(action.replaceFirstChar { it.uppercase() }) }
                }
            }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("New task") }, text = {
        Column {
            TaskEditor(state.busy) { prompt, runAt, interval ->
                vm.scheduleTask(prompt, runAt, interval)?.invokeOnCompletion { if (vm.state.value.error == null) adding = false }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {}, dismissButton = { TextButton({ adding = false }) { Text("Cancel") } })
    deleting?.let { id -> ConfirmDialog("Delete task?", "This removes the task and its saved result on all clients. Its synced conversation remains in history.", { deleting = null }) { vm.taskAction(id, "delete"); deleting = null } }
}

@Composable
fun ProjectsScreen(vm: WorkspaceActions, state: WorkspaceState, initialEditingId: String? = null, initiallyAdding: Boolean = false, selectedProjectId: String? = null, useProject: ((String) -> Unit)? = null, openChat: () -> Unit) {
    var deleting by remember { mutableStateOf<AssistantProject?>(null) }
    var editing by remember(initialEditingId) { mutableStateOf(state.knowledge.projects.firstOrNull { it.id == initialEditingId }) }; var adding by remember(initiallyAdding) { mutableStateOf(initiallyAdding) }
    ScreenColumn("Projects") {
        Text("Project instructions and reference text are shared across clients.", color = MaterialTheme.colorScheme.secondary)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({ adding = true }, enabled = !state.busy) { Text("New project") }
        for (p in state.knowledge.projects.filter { selectedProjectId == null || it.id == selectedProjectId }) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium); Text(p.instructions.take(300), style = MaterialTheme.typography.bodyMedium)
                Row { TextButton({ vm.newChat(p.id); openChat() }, enabled = !state.busy && state.activeJob == null) { Text("New chat") }; if (useProject != null) TextButton({ useProject(p.id) }, enabled = !state.busy) { Text("Use in this chat") }; TextButton({ editing = p }, enabled = !state.busy) { Text("Edit") }; TextButton({ deleting = p }, enabled = !state.busy) { Text("Delete") } }
                for (chat in state.chats.filter { it.conversation.summary.projectId == p.id }) TextButton({ vm.select(chat.item.id); openChat() }) { Text(chat.conversation.summary.title) }
            }
        }
    }
    deleting?.let { project -> ConfirmDialog("Delete project?", "Project instructions and reference text are removed across clients. Chats are retained.", { deleting = null }) {
        vm.deleteProject(project.id)?.invokeOnCompletion { if (vm.state.value.error == null) deleting = null }
    } }
    if (adding || editing != null) {
        val old = editing
        val baseline = remember { state }
        var name by remember(old?.id) { mutableStateOf(old?.name.orEmpty()) }; var instructions by remember(old?.id) { mutableStateOf(old?.instructions.orEmpty()) }; var context by remember(old?.id) { mutableStateOf(old?.context.orEmpty()) }
        AlertDialog(onDismissRequest = { adding = false; editing = null }, title = { Text(if (old == null) "New project" else "Edit project") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }); OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions") }, maxLines = 6); OutlinedTextField(context, { context = it }, label = { Text("Reference text") }, maxLines = 8)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton({ vm.saveProject(old?.id, name, instructions, context, baseline)?.invokeOnCompletion { if (vm.state.value.error == null) { adding = false; editing = null } } }, enabled = !state.busy && name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton({ adding = false; editing = null }) { Text("Cancel") } })
    }
}

@Composable private fun ScreenColumn(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { Text(title, style = MaterialTheme.typography.headlineMedium); content() }
}
@Composable private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, change, enabled = enabled) } }
@Composable private fun PreferenceText(label: String, value: String, revision: Int, enabled: Boolean, save: (String, Int) -> Unit) {
    var text by remember { mutableStateOf(value) }; var baseline by remember { mutableStateOf(revision) }; var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(value, revision) { if (!dirty || text == value) { text = value; baseline = revision; dirty = false } }
    Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(text, { if (!dirty) baseline = revision; text = it; dirty = it != value }, Modifier.weight(1f), label = { Text(label) }, singleLine = true); TextButton({ save(text, baseline) }, enabled = enabled && dirty) { Text("Save") } }
}
@Composable private fun EditTextDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }; AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), maxLines = 8) }, confirmButton = { TextButton({ save(text) }, enabled = text.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
@Composable private fun ConfirmDialog(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) }, confirmButton = { TextButton(confirm) { Text("Delete") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } }) }
