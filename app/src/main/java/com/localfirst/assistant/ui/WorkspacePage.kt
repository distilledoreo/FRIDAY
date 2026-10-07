package com.localfirst.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localfirst.assistant.conversation.AssistantProject
import com.mikepenz.markdown.m3.Markdown

@Composable
internal fun WorkspacePage(state: ChatUiState, vm: ChatViewModel) {
    var tab by remember { mutableStateOf(0) }
    var deletingFile by remember { mutableStateOf<com.localfirst.assistant.workspace.WorkspaceFile?>(null) }
    var memory by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AssistantProject?>(null) }
    var projectName by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var projectContext by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var delay by remember { mutableStateOf("0") }
    var interval by remember { mutableStateOf("0") }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::exportBackup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restoreBackup) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { it?.let(vm::exportChat) }
    deletingFile?.let { f -> AlertDialog(onDismissRequest = { deletingFile = null }, title = { Text("Delete ${f.name}?") },
        text = { Text("The computer copy will be deleted. Chats or tasks that reference it may no longer be able to open it.") },
        confirmButton = { TextButton(onClick = { vm.deleteWorkspaceFile(f.id); deletingFile = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deletingFile = null }) { Text("Cancel") } }) }
    Dialog(onDismissRequest = vm::dismissWorkspace, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Workspace", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = vm::dismissWorkspace) { Text("Done") }
                }
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    listOf("Memory", "Projects", "Tasks", "Files", "Sync & backup").forEachIndexed { i, name ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) })
                    }
                }
                if (state.workspaceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.workspaceStatus?.let { Text(it, Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (tab) {
                        0 -> {
                            Text("Saved facts and preferences are available across chats. Save only what you want the assistant to retain.")
                            OutlinedTextField(memory, { memory = it }, label = { Text("Remember…") }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { vm.saveMemory(memory); memory = "" }, enabled = memory.isNotBlank() && !state.workspaceBusy) { Text("Save memory") }
                            state.knowledge.memories.forEach { m ->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Text(m.text)
                                    TextButton(onClick = { vm.forgetMemory(m.id) }, enabled = !state.workspaceBusy) { Text("Forget") }
                                } }
                            }
                        }
                        1 -> {
                            Text("Projects add instructions and reference text to chats. Select one for the current chat.")
                            TextButton(onClick = { vm.selectProject(null) }) { Text(if (state.projectId == null) "✓ General chat" else "Use general chat") }
                            state.knowledge.projects.forEach { p ->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Text((if (state.projectId == p.id) "✓ " else "") + p.name, style = MaterialTheme.typography.titleMedium)
                                    Text(p.instructions.take(240))
                                    Row {
                                        TextButton(onClick = { vm.selectProject(p.id) }) { Text("Use") }
                                        TextButton(onClick = { editing = p; projectName = p.name; instructions = p.instructions; projectContext = p.context }) { Text("Edit") }
                                        TextButton(onClick = { vm.addProjectFiles(p.id) }, enabled = !state.workspaceBusy) { Text("Add chat files") }
                                        TextButton(onClick = { vm.deleteProject(p.id) }, enabled = !state.workspaceBusy) { Text("Delete") }
                                    }
                                } }
                            }
                            Text(if (editing == null) "New project" else "Edit project", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(projectName, { projectName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                            OutlinedTextField(projectContext, { projectContext = it }, label = { Text("Reference text") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                            Row {
                                Button(onClick = { vm.saveProject(editing?.id, projectName, instructions, projectContext); editing = null; projectName = ""; instructions = ""; projectContext = "" },
                                    enabled = projectName.isNotBlank() && !state.workspaceBusy) { Text("Save project") }
                                TextButton(onClick = { editing = null; projectName = ""; instructions = ""; projectContext = "" }) { Text("Clear") }
                            }
                        }
                        2 -> {
                            Text("Research and file work run on your computer while it is online. Results survive restarts. Phone actions require an interactive chat.")
                            OutlinedTextField(prompt, { prompt = it }, label = { Text("Task") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(delay, { delay = it }, label = { Text("Start after minutes (0 = now)") })
                            OutlinedTextField(interval, { interval = it }, label = { Text("Repeat minutes (0 = once; minimum 15)") })
                            Button(onClick = { vm.scheduleTask(prompt, delay.toIntOrNull() ?: 0, interval.toIntOrNull() ?: 0) }, enabled = prompt.isNotBlank() && !state.workspaceBusy) { Text("Schedule") }
                            TextButton(onClick = vm::refreshWorkspace, enabled = !state.workspaceBusy) { Text("Refresh tasks") }
                            state.tasks.forEach { t -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text(t.prompt, style = MaterialTheme.typography.titleMedium)
                                Text(t.status, style = MaterialTheme.typography.labelMedium)
                                if (t.result.isNotBlank()) Markdown(t.result)
                                if (t.error.isNotBlank()) Text(t.error, color = MaterialTheme.colorScheme.error)
                                Row {
                                    if (t.status in listOf("scheduled", "running")) TextButton(onClick = { vm.manageTask(t.id, "pause") }, enabled = !state.workspaceBusy) { Text("Pause") }
                                    if (t.status in listOf("paused", "failed")) TextButton(onClick = { vm.manageTask(t.id, "resume") }, enabled = !state.workspaceBusy) { Text("Resume") }
                                    if (t.status in listOf("cancelled", "completed", "failed", "paused")) TextButton(onClick = { vm.removeTask(t.id) }, enabled = !state.workspaceBusy) { Text("Remove") }
                                    if (t.status !in listOf("cancelled", "completed")) TextButton(onClick = { vm.manageTask(t.id, "cancel") }, enabled = !state.workspaceBusy) { Text("Cancel") }
                                }
                            } } }
                        }
                        3 -> {
                            Text("Uploaded and generated files on your computer. Delete files you no longer need to reclaim storage.")
                            TextButton(onClick = vm::refreshWorkspace, enabled = !state.workspaceBusy) { Text("Refresh files") }
                            state.workspaceFiles.forEach { f -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text(f.name, style = MaterialTheme.typography.titleMedium)
                                Text("${f.size / 1024} KB", style = MaterialTheme.typography.labelMedium)
                                Row {
                                    TextButton(onClick = { vm.openArtifact("assistant://artifact/${f.id}") }, enabled = !state.workspaceBusy) { Text("Open") }
                                    TextButton(onClick = { deletingFile = f }, enabled = !state.workspaceBusy) { Text("Delete") }
                                }
                            } } }
                        }
                        4 -> {
                            Text("Sync to the computer configured in Settings. It stores chats, attachments, memory, and projects. Conflicting edits require a choice.")
                            Button(onClick = vm::syncChats, enabled = !state.workspaceBusy && !state.busy) { Text("Sync now") }
                            state.syncConflicts.forEach { c -> Card { Column(Modifier.padding(12.dp)) {
                                Text("Conflict: ${c.label}")
                                Text(c.remote.getJSONObject("payload").toString().take(400), style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { vm.resolveSync(c, true) }, enabled = !state.workspaceBusy) { Text("Keep phone") }
                                    TextButton(onClick = { vm.resolveSync(c, false) }, enabled = !state.workspaceBusy) { Text("Keep computer") }
                                }
                            } } }
                            HorizontalDivider()
                            Text("Backups include chats, attachments, memory, and projects. Credentials are excluded. Restore creates new chat copies.")
                            Button(onClick = { backup.launch("local-assistant-backup.zip") }, enabled = !state.workspaceBusy) { Text("Export backup") }
                            OutlinedButton(onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !state.workspaceBusy) { Text("Restore backup") }
                            OutlinedButton(onClick = { export.launch("chat.md") }, enabled = state.conversationId != null && !state.workspaceBusy) { Text("Export current chat") }
                            TextButton(onClick = vm::refreshWorkspace, enabled = !state.workspaceBusy) { Text("Check computer health") }
                            TextButton(onClick = vm::checkUpdate, enabled = !state.workspaceBusy) { Text("Check app update") }
                            OutlinedButton(onClick = vm::installUpdate, enabled = !state.workspaceBusy) { Text("Download verified update") }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}
