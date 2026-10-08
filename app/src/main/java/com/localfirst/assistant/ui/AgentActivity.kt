package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Approval controls are UI-only. Model tools can propose, never approve. */
@Composable
internal fun AgentActivity(state: ChatUiState, vm: ChatViewModel) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var plan by rememberSaveable { mutableStateOf("Read public sources\nPrepare a cited report") }
    var confirming by remember { mutableStateOf<JSONObject?>(null) }
    var action by remember { mutableStateOf<JSONObject?>(null) }
    var category by rememberSaveable { mutableStateOf("All") }
    var cursors by remember { mutableStateOf(listOf(0L)) }
    val task = state.agentTask?.takeIf { it.optString("id") == selected }
    LaunchedEffect(selected) { cursors = listOf(0L); vm.refreshAgentActivity(selected, 0) }
    LaunchedEffect(selected, state.agentTasks.any { it.optString("status") in listOf("running", "approved") }) {
        while (state.agentTasks.any { it.optString("status") in listOf("running", "approved") }) {
            delay(3000)
            vm.refreshAgentActivity(selected)
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(state.agentDetail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
            Text("Tasks run after you approve their plan. Sending, submitting, logging in, buying and deleting need a separate approval.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.refreshAgentActivity(selected) }, enabled = !state.workspaceBusy) { Text("Refresh") }
        }
        if (selected == null) {
            item {
                OutlinedTextField(prompt, { prompt = it.take(8000) }, label = { Text("What should FRIDAY work on?") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(plan, { plan = it.take(12000) }, label = { Text("Plan · one step per line") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.proposeAgentTask(prompt.trim(), plan.lines().map(String::trim).filter(String::isNotBlank)); prompt = "" }, enabled = prompt.isNotBlank() && plan.isNotBlank() && !state.workspaceBusy) { Text("Save plan for review") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "In progress", "Done").forEach { label ->
                        FilterChip(selected = category == label, onClick = { category = label }, label = { Text(label) })
                    }
                }
            }
            val visible = state.agentTasks.filter {
                when (category) {
                    "In progress" -> it.optString("status") in listOf("approved", "running")
                    "Done" -> it.optString("status") in listOf("done", "failed", "cancelled", "interrupted")
                    else -> true
                }
            }
            if (visible.isEmpty()) item { Text("No tasks here yet.") }
            items(visible, key = { it.getString("id") }) { entry ->
                OutlinedCard(onClick = { selected = entry.getString("id") }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(entry.getJSONObject("proposal").optString("prompt"), style = MaterialTheme.typography.titleSmall)
                        Text(entry.optString("status").replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            item { TextButton(onClick = { selected = null }) { Text("All activity") } }
            task?.let { current ->
                item {
                    val proposal = current.getJSONObject("proposal")
                    Text(proposal.optString("prompt"), style = MaterialTheme.typography.titleMedium)
                    val steps = proposal.optJSONArray("plan")
                    steps?.let { for (i in 0 until it.length()) Text("${i + 1}. ${it.getString(i)}") }
                    Text("Status: ${current.optString("status")}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (current.optString("status") == "proposed") Button(onClick = { confirming = current }, enabled = state.agentReady && !state.workspaceBusy) { Text("Review and approve") }
                        if (current.optString("status") in listOf("proposed", "approved", "running")) OutlinedButton(onClick = { vm.cancelAgentTask(current.getString("id")) }, enabled = !state.workspaceBusy) { Text("Cancel task") }
                    }
                }
                val pending = current.optJSONArray("actions")?.let { values -> (0 until values.length()).map { values.getJSONObject(it) }.filter { it.optString("status") == "proposed" } }.orEmpty()
                items(pending, key = { it.getString("id") }) { pendingAction ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Approval needed: ${pendingAction.getJSONObject("payload").optString("kind")}")
                            TextButton(onClick = { action = pendingAction }, enabled = !state.workspaceBusy) { Text("Review exact action") }
                        }
                    }
                }
                items(state.agentEvents, key = { it.getLong("seq") }) { event ->
                    Column {
                        Text(event.optString("kind").replace('_', ' '), style = MaterialTheme.typography.labelLarge)
                        Text(event.getJSONObject("data").toString(2), style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1); vm.refreshAgentActivity(selected, cursors.last()) }, enabled = !state.workspaceBusy) { Text("Earlier activity") }
                        if (state.agentEvents.size == 200) TextButton(onClick = { val next = state.agentEvents.last().getLong("seq"); cursors = cursors + next; vm.refreshAgentActivity(selected, next) }, enabled = !state.workspaceBusy) { Text("More activity") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    confirming?.let { exact ->
        AlertDialog(onDismissRequest = { confirming = null }, title = { Text("Approve this task?") },
            text = { Text("${exact.getJSONObject("proposal").toString(2)}\n\nThis permits public browsing and reading within the plan. Other actions require another approval.", Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { vm.approveAgentTask(exact.getString("id"), exact.getString("fingerprint")); confirming = null }) { Text("Approve") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Back") } })
    }
    action?.let { exact ->
        AlertDialog(onDismissRequest = { action = null }, title = { Text("Approve this exact action?") },
            text = { Text(exact.getJSONObject("payload").toString(2), Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { task?.let { vm.approveAgentAction(it.getString("id"), exact.getString("id"), exact.getString("fingerprint")) }; action = null }) { Text("Approve action") } },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Back") } })
    }
}
