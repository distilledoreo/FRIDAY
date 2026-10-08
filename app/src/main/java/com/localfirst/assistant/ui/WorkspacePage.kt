package com.localfirst.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localfirst.assistant.conversation.AssistantProject
import com.localfirst.assistant.workspace.BackgroundTask
import com.localfirst.assistant.workspace.SyncConflict
import com.mikepenz.markdown.m3.Markdown
import java.time.*
import java.time.format.DateTimeFormatter

enum class WorkspaceDestination(val title: String) {
    SETTINGS("Settings"), COMPUTER_ACCESS("Computer access"), APPEARANCE("Appearance"), BRIEF("Dashboard"), ACCOUNTS("Accounts"), MEMORY("Memory"), ACTIVITY("FRIDAY"), PROJECTS("Projects"), PROJECT("Project"),

    EDIT_PROJECT("Project settings"), TASKS("Tasks"), TASK("Task"), NEW_TASK("New task"),
    FILES("Library"), DATA("Data controls"), NEW_MEMORY("Add memory"), IMAGES("Images"), IMPORT_CHATGPT("Import ChatGPT"), MEMORY_REVIEW("Review memories"), MEMORY_ARCHIVE("PC archive"), MEMORY_SOURCE("Source chat"), MEMORY_CONTEXT("Recall context")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacePage(state: ChatUiState, vm: ChatViewModel) {
    val initial = state.workspaceDestination
    var page by rememberSaveable(initial) { mutableStateOf(initial) }
    var parent by rememberSaveable(initial) { mutableStateOf(initial) }
    var projectId by rememberSaveable(state.workspaceProjectId) { mutableStateOf(state.workspaceProjectId) }
    var taskId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var instructions by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    var memory by rememberSaveable { mutableStateOf("") }
    var projectError by remember { mutableStateOf<String?>(null) }
    var renamingChat by remember { mutableStateOf<com.localfirst.assistant.conversation.ConversationSummary?>(null) }
    var deletingChat by remember { mutableStateOf<com.localfirst.assistant.conversation.ConversationSummary?>(null) }
    var deletingProject by remember { mutableStateOf<AssistantProject?>(null) }
    var deletingFile by remember { mutableStateOf<com.localfirst.assistant.workspace.WorkspaceFile?>(null) }
    val project = state.knowledge.projects.firstOrNull { it.id == projectId }
    val task = state.tasks.firstOrNull { it.id == taskId }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::exportBackup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restoreBackup) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { it?.let(vm::exportChat) }
    fun goBack() {
        page = when (page) {
            WorkspaceDestination.EDIT_PROJECT -> if (project != null) WorkspaceDestination.PROJECT else WorkspaceDestination.PROJECTS
            WorkspaceDestination.PROJECT -> WorkspaceDestination.PROJECTS
            WorkspaceDestination.PROJECTS -> { if (initial == WorkspaceDestination.PROJECT || initial == WorkspaceDestination.PROJECTS) { vm.dismissWorkspace(); return }; parent }
            WorkspaceDestination.NEW_TASK, WorkspaceDestination.TASK -> WorkspaceDestination.TASKS
            WorkspaceDestination.NEW_MEMORY, WorkspaceDestination.IMPORT_CHATGPT, WorkspaceDestination.MEMORY_REVIEW, WorkspaceDestination.MEMORY_ARCHIVE, WorkspaceDestination.MEMORY_CONTEXT -> WorkspaceDestination.MEMORY
            WorkspaceDestination.MEMORY_SOURCE -> WorkspaceDestination.MEMORY_ARCHIVE
            else -> { if (page == initial) { vm.dismissWorkspace(); return }; parent }
        }
    }
    fun editProject(p: AssistantProject?) {
        projectError = null
        projectId = p?.id; name = p?.name.orEmpty(); instructions = p?.instructions.orEmpty(); reference = p?.context.orEmpty()
        page = WorkspaceDestination.EDIT_PROJECT
    }
    LaunchedEffect(state.workspaceStatus) {
        if (state.workspaceStatus == "Project saved." && page == WorkspaceDestination.EDIT_PROJECT) page = WorkspaceDestination.PROJECTS
        if (state.workspaceStatus == "Memory saved." && page == WorkspaceDestination.NEW_MEMORY) { memory = ""; page = WorkspaceDestination.MEMORY }
        if ((state.workspaceStatus == "Task scheduled." && page == WorkspaceDestination.NEW_TASK) || (state.workspaceStatus == "Task removed." && page == WorkspaceDestination.TASK)) page = WorkspaceDestination.TASKS
    }
    Dialog(onDismissRequest = ::goBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FridayDialogWindow()
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Scaffold(containerColor=MaterialTheme.colorScheme.background, modifier = Modifier.imePadding(), topBar = {
                TopAppBar(title = { FridayDialogWindow(); Text(if (page == WorkspaceDestination.PROJECT) project?.name ?: "Project" else page.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        when (page) {
                            WorkspaceDestination.PROJECTS -> IconButton(onClick = { editProject(null) }, enabled = !state.workspaceBusy) { Icon(Icons.Filled.Add, "New project") }
                            WorkspaceDestination.IMAGES -> IconButton(onClick = { vm.setImageMode(true) }) { Icon(Icons.Filled.Add, "Generate image or change settings") }
                            WorkspaceDestination.MEMORY -> IconButton(onClick = { memory = ""; vm.editMemory(null); page = WorkspaceDestination.NEW_MEMORY }, enabled = !state.workspaceBusy) { Icon(Icons.Filled.Add, "Add memory") }
                            WorkspaceDestination.TASKS -> IconButton(onClick = { page = WorkspaceDestination.NEW_TASK }, enabled = !state.workspaceBusy) { Icon(Icons.Filled.Add, "New task") }
                            WorkspaceDestination.PROJECT -> MoreActions(listOf("Edit instructions" to { editProject(project) }, "Delete project" to { deletingProject = project }))
                            WorkspaceDestination.FILES, WorkspaceDestination.TASK -> TextButton(shape = MaterialTheme.shapes.small, onClick = vm::refreshWorkspace, enabled = !state.workspaceBusy) { Text("Refresh") }
                            else -> Unit
                        }
                    })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (state.workspaceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.workspaceStatus?.takeIf { it.isNotBlank() && !it.startsWith("Computer online") }?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
                    when (page) {
                        WorkspaceDestination.COMPUTER_ACCESS -> PcSettingsPage(state,vm)
                        WorkspaceDestination.APPEARANCE -> AppearancePage()
                        WorkspaceDestination.BRIEF -> DailyBriefPage(state, vm) { parent = page; page = it }
                        WorkspaceDestination.ACCOUNTS -> AccountsPage(state, vm)
                        WorkspaceDestination.ACTIVITY -> AgentActivity(state, vm)
                        WorkspaceDestination.IMAGES -> ImageJobs(state, vm)
                        WorkspaceDestination.SETTINGS -> ScreenList {
                            item { SectionLabel("Personalization") }
                            item { SettingsRow("Appearance", "Theme, accent, proactivity and motion") { parent = page; page = WorkspaceDestination.APPEARANCE } }
                            item { SectionLabel("Your information") }
                            item { SettingsRow("Dashboard", "Calendar, weather, follow-ups and notifications") { parent = page; page = WorkspaceDestination.BRIEF } }
                            item { SettingsRow("Accounts", "Email and calendar sign-in") { parent = page; page = WorkspaceDestination.ACCOUNTS } }
                            item { SettingsRow("Memory", "Manage what the assistant remembers") { parent = page; page = WorkspaceDestination.MEMORY } }
                            item { SettingsRow("Data controls", "Sync, export and backups") { parent = page; page = WorkspaceDestination.DATA } }
                            item { SectionLabel("Assistant and connections") }
                            item { SettingsRow("Computer access", "Shell, desktop and permission rules") { parent = page; page = WorkspaceDestination.COMPUTER_ACCESS } }
                            item { SettingsRow("Voice and server", "Connection, model and voice settings", vm::openSettings) }
                            item { SettingsRow("Computer status", state.workspaceStatus?.takeIf { it.startsWith("Computer online") } ?: "Check connection and tools", vm::refreshWorkspace) }
                            item { SettingsRow("App updates", "Check for a new version", vm::checkUpdate) }
                            item { SettingsRow("Install update", "Download and verify before opening Android’s installer", vm::installUpdate) }
                        }
                        WorkspaceDestination.MEMORY -> MemoryHub(state, vm,
                            onImport = { page = WorkspaceDestination.IMPORT_CHATGPT },
                            onReview = { page = WorkspaceDestination.MEMORY_REVIEW },
                            onArchive = { page = WorkspaceDestination.MEMORY_ARCHIVE; vm.refreshArchive() },
                            onEdit = { item -> memory = item.getString("text"); vm.editMemory(item); page = WorkspaceDestination.NEW_MEMORY },
                            onContext = { page = WorkspaceDestination.MEMORY_CONTEXT })
                        WorkspaceDestination.IMPORT_CHATGPT -> ChatGptImport(state, vm)
                        WorkspaceDestination.MEMORY_REVIEW -> MemoryReview(state, vm)
                        WorkspaceDestination.MEMORY_ARCHIVE -> MemoryArchive(state, vm) { page = WorkspaceDestination.MEMORY_SOURCE }
                        WorkspaceDestination.MEMORY_SOURCE -> MemorySource(state, vm)
                        WorkspaceDestination.MEMORY_CONTEXT -> MemoryContext(state)
                        WorkspaceDestination.NEW_MEMORY -> FormScreen {
                            OutlinedTextField(memory, { memory = it.take(2000) }, label = { Text("What should I remember?") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                            Button(shape = MaterialTheme.shapes.small, onClick = { vm.saveMemory(memory) }, enabled = memory.isNotBlank() && !state.workspaceBusy, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                        }
                        WorkspaceDestination.PROJECTS -> ScreenList {
                            if (state.knowledge.projects.isEmpty()) item { EmptyList("Keep related chats together", "Create a project to share instructions and reference material across chats.") }
                            items(state.knowledge.projects, key = { it.id }) { p ->
                                SettingsRow(p.name, "${state.conversations.count { it.projectId == p.id }} chats") { projectId = p.id; page = WorkspaceDestination.PROJECT }
                            }
                            item { TextButton(shape = MaterialTheme.shapes.small, onClick = { editProject(null) }, modifier = Modifier.padding(12.dp)) { Text("New project") } }
                        }
                        WorkspaceDestination.PROJECT -> ScreenList {
                            item { Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(shape = MaterialTheme.shapes.small, onClick = { projectId?.let(vm::newProjectChat) }, enabled = !state.busy && !state.workspaceBusy) { Text("New chat") }
                                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { vm.selectProject(projectId); vm.dismissWorkspace() }, enabled = !state.busy && !state.workspaceBusy) { Text("Use in this chat") }
                            } }
                            item { SettingsRow("Instructions", project?.instructions?.take(180)?.ifBlank { "Add guidance for this project" }) { editProject(project) } }
                            item { SettingsRow("Sources", if (project?.context.isNullOrBlank()) "Add reference text or documents from a chat" else "Reference text saved") { editProject(project) } }
                            item { SectionLabel("Chats") }
                            val chats = state.conversations.filter { it.projectId == projectId }
                            if (chats.isEmpty()) item { ScreenHint("Your project chats will appear here.") }
                            items(chats, key = { it.id }) { c -> ConversationRow(c, c.id == state.conversationId,
                                onOpen = { vm.dismissWorkspace(); vm.openConversation(c.id) },
                                onRename = { renamingChat = c }, onDelete = { deletingChat = c }) }
                        }
                        WorkspaceDestination.EDIT_PROJECT -> FormScreen {
                            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Project name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(instructions, { instructions = it.take(8000) }, label = { Text("Instructions") }, placeholder = { Text("How should the assistant respond in this project?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(reference, { reference = it.take(60000) }, label = { Text("Reference text") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                            TextButton(shape = MaterialTheme.shapes.small, onClick = {
                                val source = vm.projectDocumentText()
                                if (source.isBlank()) projectError = "Attach a readable document to this chat first."
                                else { reference = (reference + "\n\n" + source).trim().take(60000); projectError = null }
                            }, enabled = !state.workspaceBusy) { Text("Add documents from current chat") }
                            projectError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Button(shape = MaterialTheme.shapes.small, onClick = { vm.saveProject(projectId, name, instructions, reference) }, enabled = name.isNotBlank() && !state.workspaceBusy, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                        }
                        WorkspaceDestination.TASKS -> ScreenList {
                            item { ScreenHint("Tasks run on your computer, including when this app is closed.") }
                            item { TextButton(shape = MaterialTheme.shapes.small, onClick = vm::refreshWorkspace, enabled = !state.workspaceBusy, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Refresh") } }
                            if (state.tasks.isEmpty()) item { EmptyList("No tasks yet", "Schedule research, reminders or file work.") }
                            items(state.tasks, key = { it.id }) { t -> SettingsRow(t.prompt, taskSummary(t)) { taskId = t.id; page = WorkspaceDestination.TASK } }
                        }
                        WorkspaceDestination.TASK -> FormScreen {
                            if (task == null) Text("This task was removed.") else {
                                Text(task.prompt, style = MaterialTheme.typography.titleLarge)
                                Text(taskSummary(task), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (task.result.isNotBlank()) Markdown(task.result)
                                if (task.error.isNotBlank()) Text(task.error, color = MaterialTheme.colorScheme.error)
                                if (task.status in listOf("scheduled", "running")) Button(shape = MaterialTheme.shapes.small, onClick = { vm.manageTask(task.id, "pause") }, enabled = !state.workspaceBusy) { Text("Pause") }
                                if (task.status in listOf("paused", "failed")) Button(shape = MaterialTheme.shapes.small, onClick = { vm.manageTask(task.id, "resume") }, enabled = !state.workspaceBusy) { Text("Resume") }
                                if (task.status !in listOf("cancelled", "completed")) TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.manageTask(task.id, "cancel") }, enabled = !state.workspaceBusy) { Text("Cancel task") }
                                else TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.removeTask(task.id) }, enabled = !state.workspaceBusy) { Text("Remove task") }
                            }
                        }
                        WorkspaceDestination.NEW_TASK -> TaskEditor(state.workspaceBusy, vm::scheduleTaskAt)
                        WorkspaceDestination.FILES -> ScreenList {
                            if (state.workspaceFiles.isEmpty()) item { EmptyList("Your files live here", "Uploaded files and files created by the assistant will appear here.") }
                            items(state.workspaceFiles, key = { it.id }) { f ->
                                FridayCard(Modifier.fillMaxWidth()) {
                                    ListItem(headlineContent={Text(f.name,maxLines=2,overflow=TextOverflow.Ellipsis)},
                                        supportingContent={Text("${if(f.size<1024) "${f.size} B" else "${f.size/1024} KB"} · ${f.mime}",style=MaterialTheme.typography.bodySmall)},
                                        leadingContent={Icon(AppIcons.Folder,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)},
                                        colors=ListItemDefaults.colors(containerColor=MaterialTheme.colorScheme.surface),
                                        modifier=Modifier.heightIn(min=72.dp).clickable(enabled=!state.workspaceBusy) { vm.openArtifact("assistant://artifact/${f.id}") },
                                        trailingContent={MoreActions(listOf("Delete" to { deletingFile=f }))})
                                }
                            }
                        }
                        WorkspaceDestination.DATA -> ScreenList {
                            item { SettingsRow("Sync now", "Share chats, projects and memories with your computer", vm::syncChats) }
                            items(state.syncConflicts, key = { it.id }) { conflict -> SyncComparison(conflict, state.workspaceBusy, vm) }
                            item { SectionLabel("Export and restore") }
                            item { SettingsRow("Export chats and files", "Create a portable backup without server settings") { backup.launch("local-assistant-backup.zip") } }
                            item { SettingsRow("Import ChatGPT export", "History and memory suggestions stored on PC") { page = WorkspaceDestination.IMPORT_CHATGPT } }
                            item { SettingsRow("Restore a backup", "Import chats as new copies") { restore.launch(arrayOf("application/zip", "application/octet-stream")) } }
                            if (state.conversationId != null) item { SettingsRow("Export current chat", "Save as Markdown") { export.launch("chat.md") } }
                        }
                    }
                }
            }
        }
    }
    deletingProject?.let { p -> ConfirmDelete("Delete ${p.name}?", "Its chats will be kept.", { deletingProject = null }) {
        vm.deleteProject(p.id); deletingProject = null; page = WorkspaceDestination.PROJECTS
    } }
    deletingFile?.let { f -> ConfirmDelete("Delete ${f.name}?", "Chats or tasks that use this file may no longer be able to open it.", { deletingFile = null }) {
        vm.deleteWorkspaceFile(f.id); deletingFile = null
    } }
    renamingChat?.let { chat ->
        var title by remember(chat.id) { mutableStateOf(chat.title) }
        AlertDialog(onDismissRequest = { renamingChat = null }, title = { FridayDialogWindow(); Text("Rename chat") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.renameConversation(chat.id, title); renamingChat = null }, enabled = title.isNotBlank()) { Text("Rename") } },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { renamingChat = null }) { Text("Cancel") } })
    }
    deletingChat?.let { chat -> ConfirmDelete("Delete chat?", "“${chat.title}” will be deleted from this phone. This can't be undone.", { deletingChat = null }) { vm.deleteConversation(chat.id); deletingChat = null } }

}

@Composable private fun ScreenList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp), content = content)
}
@Composable private fun FormScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable private fun SectionLabel(text: String) { Text(text, Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun ScreenHint(text: String) { Text(text, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun EmptyList(title: String, detail: String) { Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(title, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(12.dp)); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
} }
@Composable private fun SettingsRow(title: String, detail: String?, action: () -> Unit) {
    FridayCard(Modifier.fillMaxWidth(),onClick=action) {
        ListItem(headlineContent={Text(title)},supportingContent=detail?.let { { Text(it) } },
            trailingContent={Text("›",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)},
            colors=ListItemDefaults.colors(containerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.heightIn(min=64.dp))
    }
}
@Composable internal fun MoreActions(actions: List<Pair<String, () -> Unit>>,contentColor:androidx.compose.ui.graphics.Color=MaterialTheme.colorScheme.onSurfaceVariant) {
    var expanded by remember { mutableStateOf(false) }
    Box { IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.MoreVert, "More options",tint=contentColor) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) { actions.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() }) } }
    }
}
@Composable private fun ConfirmDelete(title: String, detail: String, dismiss: () -> Unit, confirm: () -> Unit) { AlertDialog(onDismissRequest = dismiss,
    title = { FridayDialogWindow(); Text(title) }, text = { Text(detail) }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = confirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = dismiss) { Text("Cancel") } }) }

private fun taskSummary(t: BackgroundTask): String {
    val status = t.status.replaceFirstChar(Char::uppercase)
    if (t.status != "scheduled" || t.runAt <= 0) return status
    val date = Instant.ofEpochMilli(t.runAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
    return "$status · $date" + if (t.intervalSeconds > 0) " · Repeats" else ""
}

@Composable private fun SyncComparison(c: SyncConflict, busy: Boolean, vm: ChatViewModel) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose a version", style = MaterialTheme.typography.titleMedium)
        Text(c.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("This phone", style = MaterialTheme.typography.labelLarge); Text(c.phonePreview)
        Text("Computer", style = MaterialTheme.typography.labelLarge); Text(c.computerPreview)
        Text("Keeping a version replaces the other copy of this item.", style = MaterialTheme.typography.bodySmall)
        Column { OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { vm.resolveSync(c, true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Keep this phone’s version") }
            TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.resolveSync(c, false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Keep computer’s version") } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TaskEditor(busy: Boolean, save: (String, Long, Int) -> Unit) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var later by rememberSaveable { mutableStateOf(false) }
    val future = remember { ZonedDateTime.now().plusMinutes(5) }
    var date by rememberSaveable { mutableStateOf(future.toLocalDate().toString()) }
    var hour by rememberSaveable { mutableIntStateOf(future.hour) }
    var minute by rememberSaveable { mutableIntStateOf(future.minute) }
    var repeat by rememberSaveable { mutableIntStateOf(0) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (showDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = {
            picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }; showDate = false
        }) { Text("Done") } }, dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { showDate = false }) { Text("Cancel") } }) { DatePicker(picker) }
    }
    if (showTime) {
        val picker = rememberTimePickerState(hour, minute)
        AlertDialog(onDismissRequest = { showTime = false }, title = { FridayDialogWindow(); Text("Start time") }, text = { TimeInput(picker) },
            confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { hour = picker.hour; minute = picker.minute; showTime = false }) { Text("Done") } }, dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { showTime = false }) { Text("Cancel") } })
    }
    FormScreen {
        OutlinedTextField(prompt, { prompt = it }, label = { Text("What would you like me to do?") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Schedule for later", Modifier.weight(1f)); Switch(later, { later = it }) }
        if (later) {
            SettingsRow("Date", LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE, MMM d"))) { showDate = true }
            SettingsRow("Time", LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("h:mm a"))) { showTime = true }
        }
        Text("Repeat", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.fillMaxWidth()) {
            listOf("Once" to 0, "Hourly" to 3600, "Daily" to 86400, "Weekly" to 604800).forEach { (label, seconds) -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { repeat = seconds }) { RadioButton(selected = repeat == seconds, onClick = { repeat = seconds }); Text(label) } }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(shape = MaterialTheme.shapes.small, onClick = {
            runCatching {
                val now = System.currentTimeMillis()
                val runAt = if (later) com.localfirst.assistant.presentation.TaskSchedule.resolve(LocalDate.parse(date), hour, minute, ZoneId.systemDefault(), now) else now
                error = null; save(prompt, runAt, repeat)
            }.onFailure { error = it.message }
        }, enabled = prompt.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (later) "Schedule task" else "Start task") }
        Text("Your computer needs to be online when the task runs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
