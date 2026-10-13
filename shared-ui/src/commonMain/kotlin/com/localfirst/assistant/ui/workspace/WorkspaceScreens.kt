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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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

private enum class Page(val label: String) { CHAT("Chat"), PROJECTS("Projects"), MEMORY("Memory"), TASKS("Tasks"), SETTINGS("Settings") }

@Composable
fun WorkspaceApp(vm: WorkspaceController, state: WorkspaceState, settingsExtras: @Composable ColumnScope.() -> Unit = {}) {
    var page by remember { mutableStateOf(Page.CHAT) }
    var drawerOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.connected) { if (!state.connected) { page = Page.CHAT; drawerOpen = false } }
    if (!state.connected) { ConnectScreen(vm, state); return }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 900.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) Drawer(vm, state, page, { page = it }, { page = Page.CHAT }, Modifier.width(280.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!wide) TextButton({ drawerOpen = true }) { Text("☰", style = MaterialTheme.typography.titleLarge) }
                    Text("FRIDAY", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { page = Page.SETTINGS }, contentAlignment = Alignment.Center) {
                        Text(state.preferences.displayName.firstOrNull()?.uppercase() ?: "F", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                state.error?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                            TextButton({ vm.dismissError() }) { Text("Dismiss") }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when(page) { Page.CHAT -> ChatScreen(vm, state); Page.PROJECTS -> ProjectsScreen(vm, state) { page = Page.CHAT }; Page.MEMORY -> MemoryScreen(vm, state); Page.TASKS -> TasksScreen(vm, state); Page.SETTINGS -> SettingsScreen(vm, state, settingsExtras) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.busy) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                    TextButton({ vm.refresh() }, enabled = !state.busy) { Text("Sync") }
                }
            }
        }
        if (!wide && drawerOpen) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f)).clickable { drawerOpen = false }) {
                Drawer(vm, state, page, { page = it; drawerOpen = false }, { page = Page.CHAT; drawerOpen = false }, Modifier.width(280.dp).clickable {})
            }
        }
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
private fun Drawer(vm: WorkspaceController, state: WorkspaceState, page: Page, navigate: (Page) -> Unit, chat: () -> Unit, modifier: Modifier) {
    Surface(modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ vm.newChat(); chat() }, Modifier.fillMaxWidth(), enabled = !state.busy && state.activeJob == null) { Text("New chat") }
            OutlinedTextField(state.query, vm::search, label = { Text("Search history") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row { TextButton({ navigate(Page.PROJECTS) }) { Text("Projects") }; TextButton({ navigate(Page.MEMORY) }) { Text("Memory") }; TextButton({ navigate(Page.TASKS) }) { Text("Tasks") } }
            LazyColumn(Modifier.weight(1f)) {
                items(state.filteredChats, key = { it.item.id }) { c ->
                    Surface(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !state.busy && state.activeJob == null) { vm.select(c.item.id); chat() }, color = if (c.item.id == state.activeId && page == Page.CHAT) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent) {
                        Text(c.conversation.summary.title, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (state.chats.isEmpty()) item { Text("Your synced conversations appear here.", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton({ navigate(Page.SETTINGS) }, Modifier.fillMaxWidth()) { Text("Settings") }
            TextButton(vm::lock, Modifier.fillMaxWidth()) { Text("Lock") }
        }
    }
}

@Composable
private fun ChatScreen(vm: WorkspaceController, state: WorkspaceState) {
    var rename by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf(false) }; var projects by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    val messages = state.active?.conversation?.messages.orEmpty()
    val transcript = remember(messages, state.activeJob) { Transcript.items(messages, state.activeJob != null) }
    val clipboard = LocalClipboardManager.current
    val copy: (String) -> Unit = { clipboard.setText(AnnotatedString(it)) }
    LaunchedEffect(state.activeId, transcript.size) { if (transcript.isNotEmpty()) scroll.animateScrollToItem(transcript.lastIndex) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(state.active?.conversation?.summary?.title ?: "New chat", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box {
                TextButton({ projects = true }, enabled = !state.busy && state.activeJob == null) { Text(state.knowledge.projects.firstOrNull { it.id == state.projectId }?.name ?: "General") }
                DropdownMenu(projects, { projects = false }) {
                    DropdownMenuItem({ Text("General chat") }, { vm.selectProject(null); projects = false })
                    for (p in state.knowledge.projects) DropdownMenuItem({ Text(p.name) }, { vm.selectProject(p.id); projects = false })
                }
            }
            if (state.active != null) {
                TextButton({ rename = true }, enabled = !state.busy && state.activeJob == null) { Text("Rename") }
                TextButton({ delete = true }, enabled = !state.busy && state.activeJob == null) { Text("Delete") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("What can I help with?", style = MaterialTheme.typography.headlineMedium)
                Text("Your history and memory travel with you.", color = MaterialTheme.colorScheme.secondary)
            }
            LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(transcript, key = { it.key }) { item -> when(item) {
                    is TranscriptItem.User -> SharedUserMessage(item, actionsEnabled = false, onCopy = copy)
                    is TranscriptItem.Assistant -> SharedAssistantMessage(item, actionsEnabled = !state.busy, onCopy = copy,
                        onChoose = vm::setDraft, interfaceContent = { AssistantReply(item.text, state.preferences.interactiveAnswers, vm::setDraft, messages) })
                    is TranscriptItem.ToolActivity -> SharedToolActivityCard(item)
                    TranscriptItem.Thinking -> ThinkingIndicator()
                } }
                state.detachedReply?.let { reply -> item { Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(16.dp)) { Text("Reply saved separately because the chat changed", style = MaterialTheme.typography.titleSmall); AssistantReply(reply, state.preferences.interactiveAnswers, vm::setDraft) } } } }
            }
        }
        SharedComposer(state.draft, busy = state.activeJob != null, editing = false,
            onDraftChange = vm::setDraft, onSend = { vm.send() }, onStop = { vm.stop() },
            onCancelEdit = {}, onVoice = null, sendEnabled = state.canSend)

    }
    if (rename) {
        val baseline = remember { state.active }
        EditTextDialog("Rename chat", state.active?.conversation?.summary?.title.orEmpty(), { rename = false }) { text -> vm.rename(text, baseline)?.invokeOnCompletion { if (vm.state.value.error == null) rename = false } }
    }
    if (delete) ConfirmDialog("Delete synced chat?", "This removes it from history on every client. Its separate recall archive and approved memories remain managed in Memory.", { delete = false }) { vm.delete(); delete = false }
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
