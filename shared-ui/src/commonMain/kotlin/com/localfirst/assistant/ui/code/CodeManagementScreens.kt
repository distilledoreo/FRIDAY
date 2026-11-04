@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.localfirst.assistant.ui.code

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.localfirst.assistant.ui.wire.JSONObject
import com.localfirst.assistant.ui.wire.JSONArray
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localfirst.assistant.ui.FridayDialogWindow

@Composable
private fun ManagementError(state: CodeState) {
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
fun CodeHistoryDialog(task: String, state: CodeState, code: CodeWorkspace, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Earlier conversation") }, text = {
        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                ManagementError(state)
                Text("Read-only source history. Commands and paths here belong to the earlier task.", style = MaterialTheme.typography.bodySmall)
                state.historyBefore?.let { before -> TextButton(onClick = { code.loadHistory(task, before) }, enabled = !state.busy) { Text("Load earlier messages") } }
            }
            items(state.history, key = { it.id }) { item ->
                Column {
                    Text(if (item.kind == "tool") "${item.tool} · ${item.status}" else item.role, style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(if (item.kind == "tool") item.output else item.text) }
                    HorizontalDivider()
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

@Composable
fun NewCodeProjectDialog(state: CodeState, code: CodeWorkspace, remote: Boolean, close: () -> Unit) {
    var target by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(if (remote) "gitlab" else "python") }
    AlertDialog(onDismissRequest = close, title = { Text(if (remote) "Other Git host" else "New local project") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ManagementError(state)
            Text(if (remote) "Uses your PC's Git credentials. GitLab review/CI actions also require glab signed in on the PC. Generic Git supports branches and push."
                else "Creates a new folder, Git repository and initial commit. Python and Node templates include tests and require no downloaded dependencies.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (if (remote) listOf("gitlab", "github", "git") else listOf("python", "node", "empty")).forEach { value ->
                    FilterChip(kind == value, { kind = value }, label = { Text(value) })
                }
            }
            OutlinedTextField(target, { target = it.take(4096) }, label = { Text(if (remote) "HTTPS or SSH clone URL" else "New folder on PC (absolute path)") }, modifier = Modifier.fillMaxWidth())
            if (!remote) Text("The parent folder must exist. Existing folders are never overwritten.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { if (remote) code.addRemote(target, kind, close) else code.createProject(target, kind, close) },
        enabled = !state.busy && target.isNotBlank()) { Text(if (remote) "Clone repository" else "Create project") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
fun CodeEnvironmentDialog(project: CodeProject, state: CodeState, code: CodeWorkspace, close: () -> Unit) {
    var steps by remember(project.id) { mutableStateOf<List<CodeRequiredCheck>>(emptyList()) }
    var environment by remember(project.id) { mutableStateOf("{}") }
    var validation by remember { mutableStateOf("") }
    LaunchedEffect(project.id) { code.loadEnvironment(project.id) }
    LaunchedEffect(state.environmentRecipe) {
        state.environmentRecipe?.let { recipe ->
            steps = rows(recipe.optJSONArray("steps")).map(CodeRequiredCheck::from)
            environment = recipe.optJSONObject("environment")?.toString(2) ?: "{}"
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Project environment") }, text = {
        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                ManagementError(state)
                Text("Setup runs through FRIDAY's normal command approvals. Use project-local environments such as .venv; installation is an explicit task action. Environment values stay private and are not copied into the model brief.")
                if (validation.isNotBlank()) Text(validation, color = MaterialTheme.colorScheme.error)
            }
            items(steps.size) { index ->
                val step = steps[index]
                fun update(value: CodeRequiredCheck) { steps = steps.mapIndexed { i, old -> if (i == index) value else old } }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(step.name, { update(step.copy(name = it)) }, label = { Text("Step name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(step.command, { update(step.copy(command = it)) }, label = { Text("Exact setup command") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(step.cwd, { update(step.copy(cwd = it)) }, label = { Text("Folder within task") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { steps = steps.filterIndexed { i, _ -> i != index } }) { Text("Remove step") }
                }
            }
            if (steps.size < 12) item { TextButton(onClick = { steps = steps + CodeRequiredCheck() }) { Text("Add setup step") } }
            item {
                Text("Environment variables (JSON object). Values support \${WORKTREE} and \${PATH}. Changing them invalidates recorded checks.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(environment, { environment = it.take(90000) }, minLines = 3, maxLines = 8, label = { Text("Private environment") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }, confirmButton = { TextButton(onClick = {
        val variables = runCatching { JSONObject(environment) }.getOrNull()
        if (variables == null) validation = "Environment must be a JSON object with string values."
        else {
            val array = JSONArray()
            steps.forEach { array.put(JSONObject().put("name", it.name).put("command", it.command).put("cwd", it.cwd)) }
            code.saveEnvironment(project.id, JSONObject().put("steps", array).put("environment", variables), close)
        }
    }, enabled = !state.busy && state.environmentRecipe != null) { Text("Save recipe") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
fun CodeQueueDialog(view: CodeTaskView, state: CodeState, code: CodeWorkspace, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Follow-up queue") }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                ManagementError(state)
                Text("Messages run in order after the current turn and approvals finish. Stop pauses the queue. An uncertain delivery needs transcript review before dismissing or queuing it again.")
                TextButton(onClick = { code.pauseQueue(!view.queue.paused) }, enabled = !state.busy) { Text(if (view.queue.paused) "Resume queue" else "Pause queue") }
            }
            if (view.queue.messages.isEmpty()) item { Text("No queued follow-ups.") }
            items(view.queue.messages, key = { it.id }) { message ->
                Column {
                    Text(message.state, style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(message.prompt, style = MaterialTheme.typography.bodyMedium) }
                    if (message.error.isNotBlank()) Text(message.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (message.state in setOf("queued", "uncertain", "failed")) TextButton(onClick = { code.removeQueued(message.id) }, enabled = !state.busy) {
                        Text(if (message.state == "queued") "Remove" else "Dismiss after reviewing transcript")
                    }
                    HorizontalDivider()
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

@Composable
fun CodeMergeDialog(view: CodeTaskView, state: CodeState, code: CodeWorkspace, close: () -> Unit) {
    var action by remember { mutableStateOf<Pair<String, String?>?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("Base branch and conflicts") }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ManagementError(state)
                Text("Merge operations save a working-file checkpoint first. Commit ordinary edits before merging the base. Resolve every conflicted file, then complete the local merge. Publishing remains a separate action.")
                if (!view.conflicts.merging) TextButton(onClick = { action = "sync" to null }, enabled = !state.busy && !view.busy) { Text("Merge ${view.task.base} into task") }
                else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { action = "continue" to null }, enabled = !state.busy && !view.busy && view.conflicts.files.isEmpty()) { Text("Complete merge") }
                    TextButton(onClick = { action = "abort" to null }, enabled = !state.busy && !view.busy) { Text("Abort merge") }
                }
                if (view.conflicts.files.isNotEmpty()) TextButton(onClick = { code.merge("ask"); close() }, enabled = !state.busy && !view.busy) { Text("Ask FRIDAY to resolve and test") }
            }
            items(view.conflicts.files) { path ->
                Column {
                    Text(path, fontFamily = FontFamily.Monospace)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { action = "ours" to path }, enabled = !state.busy) { Text("Use task version") }
                        TextButton(onClick = { action = "theirs" to path }, enabled = !state.busy) { Text("Use base version") }
                        TextButton(onClick = { action = "stage" to path }, enabled = !state.busy) { Text("Stage manual resolution") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
    action?.let { pending -> AlertDialog(onDismissRequest = { action = null }, title = { Text("Confirm merge action") },
        text = { Text("${pending.first}${pending.second?.let { ": $it" }.orEmpty()}. Choosing a version can remove the other side's edits or accept a deletion. Current working files will be checkpointed first.") },
        confirmButton = { TextButton(onClick = { action = null; code.merge(pending.first, pending.second) }, enabled = !state.busy) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } }) }
}

@Composable
fun CodeCollaborationDialog(state: CodeState, code: CodeWorkspace, close: () -> Unit) {
    val details = state.collaboration
    val uri = LocalUriHandler.current
    var replying by remember { mutableStateOf<CodeReviewThread?>(null) }
    var reply by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf<Pair<CodeReviewThread, String>?>(null) }
    LaunchedEffect(Unit) { code.loadCollaboration() }
    AlertDialog(onDismissRequest = close, title = { Text("Review and CI") }, text = {
        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                ManagementError(state)
                TextButton(onClick = code::loadCollaboration, enabled = !state.busy) { Text("Refresh review snapshot") }
                details?.let { Text("Request head: ${it.head.take(12)}", style = MaterialTheme.typography.bodySmall) }
                if (details?.truncated == true) Text("This is a bounded preview; more discussions are available on the code host.")
                if (details != null) TextButton(onClick = { code.repairCi(); close() }, enabled = !state.busy && state.view?.busy == false) { Text("Ask FRIDAY to address review and CI") }
            }
            items(details?.jobs.orEmpty()) { job ->
                Text("${job.name}: ${job.state}", modifier = Modifier.clickable(enabled = job.url.startsWith("https://")) { uri.openUri(job.url) })
            }
            items(details?.threads.orEmpty(), key = { it.id }) { thread ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${thread.path}${thread.line.takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()} · ${if (thread.resolved) "Resolved" else "Open"}", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(thread.body) }
                    Row {
                        TextButton(onClick = { replying = thread; reply = "" }, enabled = !state.busy) { Text("Reply") }
                        if (thread.canResolve) TextButton(onClick = { posting = thread to if (thread.resolved) "reopen" else "resolve" }, enabled = !state.busy) { Text(if (thread.resolved) "Reopen" else "Resolve") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
    replying?.let { thread -> AlertDialog(onDismissRequest = { replying = null }, title = { Text("Reply on code host") },
        text = { Column { Text(thread.path); OutlinedTextField(reply, { reply = it.take(10000) }, label = { Text("Exact reply to post") }, minLines = 3, maxLines = 8) } },
        confirmButton = { TextButton(onClick = { code.reviewAction(thread.id, "reply", reply); replying = null }, enabled = !state.busy && reply.isNotBlank()) { Text("Post reply") } },
        dismissButton = { TextButton(onClick = { replying = null }) { Text("Cancel") } }) }
    posting?.let { (thread, action) -> AlertDialog(onDismissRequest = { posting = null }, title = { Text("$action review thread?") },
        text = { Text("This changes the discussion on the code host. FRIDAY checks the request head before posting and asks you to refresh if it changed.") },
        confirmButton = { TextButton(onClick = { code.reviewAction(thread.id, action); posting = null }, enabled = !state.busy) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { posting = null }) { Text("Cancel") } }) }
}

@Composable
fun CodeExtensionsDialog(state: CodeState, code: CodeWorkspace, close: () -> Unit) {
    var editor by remember { mutableStateOf<JSONObject?>(null) }
    var discovering by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf("") }
    var connection by remember { mutableStateOf<JSONObject?>(null) }
    var authCode by remember { mutableStateOf("") }
    var authName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { code.loadExtensions() }
    LaunchedEffect(state.extensionDraft) { state.extensionDraft?.let { editor = it } }
    // Keep one management dialog mounted at a time. Concurrent modal windows
    // compete for focus on Android, especially when an editor opens immediately.
    editor?.let { CodeExtensionEditor(it, state, code) { editor = null; code.clearExtensionDraft() }; return }
    connection?.let { ConnectionEditor(it, state, code) { connection = null }; return }
    if (state.oauthUrl == null) AlertDialog(onDismissRequest = close, title = { Text("Skills, plugins and connections") }, text = {
        LazyColumn(Modifier.heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ManagementError(state)
                Text("Imports stay disabled until you review and enable their exact revision. Plugins/hooks run as your PC user outside model tool approvals. Changes apply when the helper next reloads while idle; disabling an already loaded plugin cannot revoke its running code.")
                Row { TextButton(onClick = { code.clearExtensionDraft(); editor = JSONObject().put("kind", "skill") }) { Text("Add extension") }
                    TextButton(onClick = { discovering = !discovering }) { Text("Discover local") } }
            }
            if (discovering) item {
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder on PC") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { code.discoverExtensions(folder) }, enabled = !state.busy && folder.startsWith('/')) { Text("Scan without executing") }
            }
            items(state.discoveredExtensions) { draft ->
                TextButton(onClick = { code.useDiscoveredExtension(draft) }) { Text("Import preview: ${draft.optString("name")}") }
            }
            items(rows(state.extensions?.optJSONArray("extensions")), key = { it.getString("id") }) { item ->
                Column {
                    Text("${item.optString("name")} · ${item.optString("kind")} · v${item.optString("version")}", style = MaterialTheme.typography.labelLarge)
                    Text(if (item.optBoolean("enabled")) "Enabled revision" else "Disabled", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { code.loadExtension(item.getString("id")) }, enabled = !state.busy) { Text("Review / edit") }
                        if (item.optBoolean("enabled")) TextButton(onClick = { code.extensionAction(item.getString("id"), "disable") }, enabled = !state.busy) { Text("Disable") }
                        TextButton(onClick = { code.extensionAction(item.getString("id"), "remove") }, enabled = !state.busy) { Text("Remove") }
                    }
                }
            }
            item { HorizontalDivider(); Text("MCP connections", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { connection = JSONObject().put("entry", JSONObject().put("type", "remote").put("url", "https://")) }) { Text("Add connection") } }
            items(rows(state.extensions?.optJSONArray("mcp")), key = { it.getString("name") }) { item ->
                Column {
                    Text(item.getString("name") + if (item.optBoolean("confirmed")) " · ${item.optString("state", "not loaded")}" else " · Needs review", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { connection = item }, enabled = !state.busy) { Text("Review / configure") }
                    if (item.optBoolean("confirmed")) Row(Modifier.horizontalScroll(rememberScrollState())) {
                        if (item.optJSONObject("entry")?.optString("type") == "remote" && item.optJSONObject("entry")?.opt("oauth") != false)
                            TextButton(onClick = { authName = item.getString("name"); code.connectionAction(authName, "auth") }, enabled = !state.busy) { Text("Sign in") }
                        TextButton(onClick = { code.connectionAction(item.getString("name"), "connect") }, enabled = !state.busy) { Text("Connect") }
                        TextButton(onClick = { code.connectionAction(item.getString("name"), "logout") }, enabled = !state.busy) { Text("Sign out") }
                        TextButton(onClick = { code.connectionAction(item.getString("name"), "disable") }, enabled = !state.busy) { Text("Disable / disconnect") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
    state.oauthUrl?.let { url ->
        val uri = LocalUriHandler.current
        AlertDialog(onDismissRequest = code::clearOAuthUrl, title = { Text("MCP sign-in on PC") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Open this link in a browser on the PC running FRIDAY so a loopback OAuth callback can reach it. If the provider supplies a code, submit it below. Then close this panel and use Connect to refresh Code sessions. Authentication state is kept by OpenCode.")
                SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { uri.openUri(url) }) { Text("Open sign-in link") }
                OutlinedTextField(authCode, { authCode = it }, label = { Text("Authorization code, if supplied") })
                ManagementError(state)
            }
        }, confirmButton = { TextButton(onClick = { code.connectionAction(authName, "callback", JSONObject().put("code", authCode)); code.clearOAuthUrl() }, enabled = authCode.isNotBlank() && !state.busy) { Text("Submit code") } },
            dismissButton = { TextButton(onClick = code::clearOAuthUrl) { Text("Close") } })
    }
}

@Composable
fun CodeExtensionEditor(original: JSONObject, state: CodeState, code: CodeWorkspace, close: () -> Unit) {
    val id = original.optString("id").takeIf { it.isNotBlank() }
    var name by remember(original) { mutableStateOf(original.optString("name")) }
    var description by remember(original) { mutableStateOf(original.optString("description")) }
    var version by remember(original) { mutableStateOf(original.optString("version", "1")) }
    var kind by remember(original) { mutableStateOf(original.optString("kind", "skill")) }
    var event by remember(original) { mutableStateOf(original.optString("event", "tool.execute.after")) }
    var content by remember(original) { mutableStateOf(original.optString("content")) }
    var trusted by remember(original) { mutableStateOf(false) }
    val unchanged = name == original.optString("name") && description == original.optString("description") && version == original.optString("version", "1") &&
        kind == original.optString("kind", "skill") && event == original.optString("event", "tool.execute.after") && content == original.optString("content")
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FridayDialogWindow(fullScreen = true)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Review extension revision", style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { ManagementError(state)
                        Row { listOf("skill", "plugin", "hook").forEach { value -> FilterChip(kind == value, { kind = value }, label = { Text(value) }) } }
                        OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name (lowercase-hyphenated)") })
                        OutlinedTextField(version, { version = it.take(60) }, label = { Text("Version") })
                        OutlinedTextField(description, { description = it.take(500) }, label = { Text("Description") })
                    }
                    if (kind == "hook") item {
                        Text("Export a default async function(input, output). The event callback runs for coding sessions; module initialization has full host access.")
                        listOf("tool.execute.before", "tool.execute.after", "experimental.session.compacting").forEach { value ->
                            FilterChip(event == value, { event = value }, label = { Text(value) })
                        }
                    }
                    if (id != null) items(rows(original.optJSONArray("versions"))) { saved ->
                        if (saved.optString("digest") != original.optString("digest")) TextButton(onClick = { code.restoreExtension(id, saved.getString("digest")) }, enabled = !state.busy) {
                            Text("Restore v${saved.optString("version")} as a disabled revision")
                        }
                    }
                    item {
                        OutlinedTextField(content, { content = it.take(32000) }, minLines = 6, maxLines = 14, label = { Text(if (kind == "skill") "Instructions" else "JavaScript module source") }, modifier = Modifier.fillMaxWidth())
                        Text("Saving and reviewing local source does not fetch packages or execute it. Once enabled, trusted code and its imports have host access.", style = MaterialTheme.typography.bodySmall)
                        original.optString("digest").takeIf { it.isNotBlank() }?.let { SelectionContainer { Text("SHA-256: $it", style = MaterialTheme.typography.bodySmall) } }
                        Row {
                            Checkbox(trusted, { trusted = it }, Modifier.semantics { contentDescription = "Trust this extension revision" })
                            Text(if (kind == "skill") "I reviewed these instructions and trust this revision." else "I reviewed this source and grant it host-user access outside model approvals.", modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { code.saveExtension(id, JSONObject().put("name", name).put("description", description).put("version", version).put("kind", kind).put("event", event).put("content", content), close) },
                        enabled = !state.busy && name.isNotBlank() && content.isNotBlank()) { Text("Save disabled revision") }
                    if (id != null && unchanged) TextButton(onClick = { code.extensionAction(id, "approve", original.getString("digest")); close() }, enabled = !state.busy && trusted) { Text("Enable reviewed revision") }
                    TextButton(onClick = close) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun ConnectionEditor(original: JSONObject, state: CodeState, code: CodeWorkspace, close: () -> Unit) {
    var name by remember(original) { mutableStateOf(original.optString("name")) }
    var entry by remember(original) { mutableStateOf(original.optJSONObject("entry")?.toString(2) ?: "{}") }
    var validation by remember { mutableStateOf("") }
    var trusted by remember { mutableStateOf(false) }
    val unchanged = name == original.optString("name") && entry == original.optJSONObject("entry")?.toString(2)
    AlertDialog(onDismissRequest = close, title = { Text("Review MCP connection") }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ManagementError(state)
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name (changing creates another connection)") })
                OutlinedTextField(entry, { entry = it.take(100000) }, minLines = 6, maxLines = 14, label = { Text("OpenCode MCP configuration (JSON)") })
                Text("Local server commands execute as the PC user. Remote connections may exchange data with that service. Stored secret values are hidden; unchanged private placeholders preserve them. Saving revokes prior approval.")
                if (validation.isNotBlank()) Text(validation, color = MaterialTheme.colorScheme.error)
                original.optString("digest").takeIf { it.isNotBlank() }?.let { SelectionContainer { Text("SHA-256: $it", style = MaterialTheme.typography.bodySmall) } }
                Row { Checkbox(trusted, { trusted = it }, Modifier.semantics { contentDescription = "Trust this MCP configuration" }); Text("I reviewed this exact server configuration and trust it.", Modifier.padding(top = 10.dp)) }
            }
        }
    }, confirmButton = { Row {
        TextButton(onClick = {
            val value = runCatching { JSONObject(entry) }.getOrNull()
            if (value == null) validation = "Enter a JSON object." else code.saveConnection(name, value, close)
        }, enabled = !state.busy && name.isNotBlank()) { Text("Save disabled") }
        if (unchanged && original.has("digest")) TextButton(onClick = { code.connectionAction(name, "approve", JSONObject().put("digest", original.getString("digest"))); close() }, enabled = !state.busy && trusted) { Text("Enable") }
    } }, dismissButton = { Row {
        if (original.has("name")) TextButton(onClick = { code.connectionAction(original.getString("name"), "remove"); close() }, enabled = !state.busy) { Text("Remove") }
        TextButton(onClick = close) { Text("Cancel") }
    } })
}
