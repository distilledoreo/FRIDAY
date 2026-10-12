package com.localfirst.assistant.web.ui

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
import com.localfirst.assistant.web.data.*
import com.localfirst.assistant.web.settings.*
import com.localfirst.assistant.web.state.*
import com.localfirst.assistant.web.theme.FridaySpacing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

private enum class Page(val label: String) { CHAT("Chat"), PROJECTS("Projects"), MEMORY("Memory"), TASKS("Tasks"), SETTINGS("Settings") }

@Composable
fun WorkspaceApp(vm: ChatViewModel, state: WebState) {
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
                    when(page) { Page.CHAT -> ChatScreen(vm, state); Page.PROJECTS -> ProjectsScreen(vm, state) { page = Page.CHAT }; Page.MEMORY -> MemoryScreen(vm, state); Page.TASKS -> TasksScreen(vm, state); Page.SETTINGS -> SettingsScreen(vm, state) }
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
private fun ConnectScreen(vm: ChatViewModel, state: WebState) {
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
private fun Drawer(vm: ChatViewModel, state: WebState, page: Page, navigate: (Page) -> Unit, chat: () -> Unit, modifier: Modifier) {
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
private fun ChatScreen(vm: ChatViewModel, state: WebState) {
    var rename by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf(false) }; var projects by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    val messages = state.active?.conversation?.messages.orEmpty()
    LaunchedEffect(state.activeId, messages.size) { if (messages.isNotEmpty()) scroll.animateScrollToItem(messages.lastIndex) }
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
                itemsIndexed(messages, key = { i, _ -> "$i-${state.activeId}" }) { _, m -> MessageRow(m, state.preferences.interactiveAnswers, vm::setDraft, messages) }
                if (state.activeJob != null) item { Text("FRIDAY is working…", color = MaterialTheme.colorScheme.secondary) }
                state.detachedReply?.let { reply -> item { Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(16.dp)) { Text("Reply saved separately because the chat changed", style = MaterialTheme.typography.titleSmall); AssistantReply(reply, state.preferences.interactiveAnswers, vm::setDraft) } } } }
            }
        }
        Surface(Modifier.fillMaxWidth().widthIn(max = 900.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(state.draft, vm::setDraft, Modifier.weight(1f), placeholder = { Text("Message FRIDAY") }, maxLines = 6, shape = RoundedCornerShape(20.dp))
                Spacer(Modifier.width(8.dp))
                if (state.activeJob != null) FilledTonalButton({ vm.stop() }, enabled = !state.busy) { Text("Stop") }
                else Button({ vm.send() }, enabled = state.canSend) { Text("Send") }
            }
        }
    }
    if (rename) {
        val baseline = remember { state.active }
        EditTextDialog("Rename chat", state.active?.conversation?.summary?.title.orEmpty(), { rename = false }) { text -> vm.rename(text, baseline)?.invokeOnCompletion { if (vm.state.value.error == null) rename = false } }
    }
    if (delete) ConfirmDialog("Delete synced chat?", "This removes it from history on every client. Its separate recall archive and approved memories remain managed in Memory.", { delete = false }) { vm.delete(); delete = false }
}

@Composable
private fun MessageRow(message: Message, interactive: Boolean, suggest: (String) -> Unit, messages: List<Message>) {
    val user = message is Message.User
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(Modifier.widthIn(max = 900.dp), shape = RoundedCornerShape(18.dp), color = if (user) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when(message) {
                    is Message.User -> { Text(message.content, style = MaterialTheme.typography.bodyLarge); for (a in message.attachments) Text("Attachment: ${a.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                    is Message.Assistant -> AssistantReply(message.content, interactive, suggest, messages)
                    is Message.ToolCall -> Text("Using ${message.name.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    is Message.ToolResult -> { Text(message.name.replace('_', ' '), style = MaterialTheme.typography.labelLarge); Text(message.content.take(2000), style = MaterialTheme.typography.bodySmall) }
                    is Message.System -> Unit
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: ChatViewModel, state: WebState) {
    val prefs = state.preferences
    ScreenColumn("Settings") {
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (theme in ThemeMode.entries) FilterChip(prefs.theme == theme, { vm.preference("theme", JsonPrimitive(theme.name)) }, label = { Text(theme.label) }, enabled = !state.busy) }
        PreferenceText("Accent color", prefs.accentHex, state.items.firstOrNull { it.id == "preferences-accent" }?.revision ?: 0, !state.busy) { text, revision -> val accent = AppearanceSettings.accent(text); if (accent != null) vm.preference("accent", JsonPrimitive(accent), revision) }
        PreferenceText("Display name", prefs.displayName, state.items.firstOrNull { it.id == "preferences-display_name" }?.revision ?: 0, !state.busy) { text, revision -> vm.preference("display_name", JsonPrimitive(text.trim().take(60)), revision) }
        Text("Proactivity", style = MaterialTheme.typography.titleMedium)
        for (level in ProactivityLevel.entries) Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(prefs.proactivity == level, { vm.preference("proactivity", JsonPrimitive(level.name)) }, enabled = !state.busy); Text(level.label) }
        SettingSwitch("Reduce motion", prefs.reducedMotion, !state.busy) { vm.preference("reduced_motion", JsonPrimitive(it)) }
        SettingSwitch("Interactive answers", prefs.interactiveAnswers, !state.busy) { vm.preference("interactive_answers", JsonPrimitive(it)) }
        Text("These preferences also apply on Android. Connection addresses, credentials and phone access stay on each device.", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Data", style = MaterialTheme.typography.titleLarge)
        val legacy = remember { legacyChats() }
        if (legacy.isNotEmpty()) OutlinedButton({ vm.importLegacy(legacy, ::importId) }, enabled = !state.busy) { Text("Import ${legacy.size} older browser chats") }
        Text("Import copies older local chats into shared history. The originals stay in this browser. Repeating an import does not duplicate them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        OutlinedButton(vm::lock) { Text("Lock this client") }
    }
}

@Composable
private fun MemoryScreen(vm: ChatViewModel, state: WebState) {
    var editing by remember { mutableStateOf<JsonObject?>(null) }; var adding by remember { mutableStateOf(false) }
    ScreenColumn("Memory") {
        Text("Approved facts and recall controls are shared with Android on your computer.", color = MaterialTheme.colorScheme.secondary)
        state.memoryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val labels = mapOf("use_memories" to "Use approved memories", "use_history" to "Recall past chats", "archive_chats" to "Archive chats for recall", "suggest_from_chats" to "Suggest memories from chats", "track_situations" to "Track recent situations", "allow_checkins" to "Allow check-ins")
        for ((key, label) in labels) SettingSwitch(label, state.memorySettings[key]?.jsonPrimitive?.booleanOrNull ?: false, !state.busy && state.memoryError == null) { vm.memorySetting(key, it) }
        Button({ adding = true }, enabled = !state.busy && state.memoryError == null) { Text("New memory") }
        if (state.knowledge.memories.isNotEmpty()) OutlinedButton({ vm.importOlderMemory() }, enabled = !state.busy) { Text("Import ${state.knowledge.memories.size} older saved memories") }
        for (m in state.memories) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(m["text"]?.jsonPrimitive?.content.orEmpty()); Row {
                    TextButton({ editing = m }, enabled = !state.busy) { Text("Edit") }
                    TextButton({ vm.forgetMemory(m.getValue("id").jsonPrimitive.content) }, enabled = !state.busy) { Text("Forget") }
                }
            }
        }
        if (state.memories.size < state.memoryTotal) OutlinedButton({ vm.moreMemories() }, enabled = !state.busy) { Text("Load more (${state.memories.size} of ${state.memoryTotal})") }
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
private fun TasksScreen(vm: ChatViewModel, state: WebState) {
    var deleting by remember { mutableStateOf<String?>(null) }
    ScreenColumn("Tasks") {
        Text("Computer work and results stay available across clients, including replies preserved after concurrent edits.", color = MaterialTheme.colorScheme.secondary)
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
    deleting?.let { id -> ConfirmDialog("Delete task?", "This removes the task and its saved result on all clients. Its synced conversation remains in history.", { deleting = null }) { vm.taskAction(id, "delete"); deleting = null } }
}

@Composable
private fun ProjectsScreen(vm: ChatViewModel, state: WebState, openChat: () -> Unit) {
    var editing by remember { mutableStateOf<AssistantProject?>(null) }; var adding by remember { mutableStateOf(false) }
    ScreenColumn("Projects") {
        Text("Project instructions and reference text are shared across clients.", color = MaterialTheme.colorScheme.secondary)
        Button({ adding = true }, enabled = !state.busy) { Text("New project") }
        for (p in state.knowledge.projects) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium); Text(p.instructions.take(300), style = MaterialTheme.typography.bodyMedium)
                Row { TextButton({ vm.newChat(p.id); openChat() }, enabled = !state.busy && state.activeJob == null) { Text("New chat") }; TextButton({ editing = p }, enabled = !state.busy) { Text("Edit") }; TextButton({ vm.deleteProject(p.id) }, enabled = !state.busy) { Text("Delete") } }
                for (chat in state.chats.filter { it.conversation.summary.projectId == p.id }) TextButton({ vm.select(chat.item.id); openChat() }) { Text(chat.conversation.summary.title) }
            }
        }
    }
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
