package com.localfirst.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Statuses where FRIDAY is busy, and where she's waiting on the user. */
internal val FRIDAY_WORKING = setOf("approved", "running")
internal val FRIDAY_NEEDS_YOU = setOf("proposed", "awaiting_setup", "interrupted")

private const val COMPUTER_SPEC = "4 cores · 8 GB · no access to your files"
private const val ASKS_AGAIN = "She asks again before sending, submitting, logging in, buying or deleting anything."

/** Quiet entry point preserves computer status, with attention only when backed by task data. */
@Composable
internal fun FridayStatusButton(state: ChatUiState, onClick: () -> Unit) {
    val needsYou=(state.pcStatus?.optInt("pending_count") ?: 0)>0||state.agentTasks.any { it.optString("status") in FRIDAY_NEEDS_YOU }
    val working=(state.pcStatus?.optInt("active_count") ?: 0)>0||state.agentTasks.any { it.optString("status") in FRIDAY_WORKING }
    val status=when { needsYou -> "Needs your attention"; working -> "Working"; else -> "Computer and activity" }
    IconButton(onClick=onClick,modifier=Modifier.size(48.dp)) {
        Box {
            Icon(AppIcons.Computer,"FRIDAY. $status",tint=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(20.dp))
            if(needsYou||working)Box(Modifier.align(Alignment.TopEnd).size(5.dp).clip(CircleShape).background(if(needsYou)com.localfirst.assistant.ui.theme.LocalFridayPalette.current.warning else MaterialTheme.colorScheme.primary))
        }
    }
}

/** FRIDAY's plan in the chat that asked for it: start it here and watch it run. */
@Composable
internal fun FridayTaskCard(json: String, state: ChatUiState, vm: ChatViewModel) {
    val proposed = remember(json) { runCatching { JSONObject(json) }.getOrNull() } ?: return
    val id = proposed.optString("id").takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: return
    val task = state.agentTasks.firstOrNull { it.optString("id") == id } ?: proposed
    val proposal = task.optJSONObject("proposal") ?: return
    val status = task.optString("status")
    val sharesAccountData = (proposal.optJSONArray("data_scopes")?.length() ?: 0) > 0
    var reviewing by remember(id) { mutableStateOf(false) }
    FridayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Computer, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("FRIDAY’s plan", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(agentStatus(status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(proposal.optString("prompt"), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            proposal.optJSONArray("plan")?.let { steps ->
                for (i in 0 until minOf(steps.length(), 6)) Text("${i + 1}. ${steps.getString(i)}", style = MaterialTheme.typography.bodySmall)
            }
            proposal.optJSONObject("schedule")?.let { Text(scheduleDescription(it).lineSequence().first(), style = MaterialTheme.typography.bodySmall) }
            if (sharesAccountData) Text("Shares selected account data with the cloud model.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            if (status in FRIDAY_WORKING) {
                val live = state.fridayLive?.takeIf { it.taskId == id }
                Text(if (status == "approved") "Starting on her computer…" else live?.activity ?: "Working on her computer…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                when (status) {
                    "proposed" -> {
                        Button(shape = MaterialTheme.shapes.small,
                            onClick = { if (sharesAccountData) reviewing = true else vm.approveAgentTask(id, task.optString("fingerprint")) },
                            enabled = state.agentReady && !state.workspaceBusy,
                        ) { Text(if (proposal.has("schedule")) "Schedule it" else "Start") }
                        TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.openFriday(id) }) { Text("Details") }
                        TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.cancelAgentTask(id) }, enabled = !state.workspaceBusy) { Text("Dismiss") }
                    }
                    "approved", "running" -> TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.openFriday(id) }) { Text("Watch") }
                    "done" -> {
                        TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.openFriday(id) }) { Text("See report") }
                        TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.discussAgentReport(id) }, enabled = !state.busy) { Text("Discuss") }
                    }
                    else -> TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.openFriday(id) }) { Text("Details") }
                }
            }
            if (status == "proposed") {
                Text(if (state.agentReady) ASKS_AGAIN else state.agentDetail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (reviewing) {
        AlertDialog(
            onDismissRequest = { reviewing = false },
            title = { FridayDialogWindow(); Text("Start this task?") },
            text = { Text(plainApproval(proposal), Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.approveAgentTask(id, task.optString("fingerprint")); reviewing = false }) { Text("Start") } },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { reviewing = false }) { Text("Back") } },
        )
    }
}

/** FRIDAY's computer: what's on her screen and whether she's busy. */
@Composable
internal fun FridayComputerCard(state: ChatUiState, vm: ChatViewModel, taskId: String? = null) {
    val live = state.fridayLive?.takeIf { taskId == null || it.taskId == taskId }
    val working = live != null || state.agentTasks.any { it.optString("status") in FRIDAY_WORKING && (taskId == null || it.optString("id") == taskId) }
    FridayCard(Modifier.fillMaxWidth()) {
        Column {
            val shot = live?.screenshotId?.takeIf(String::isNotBlank)
            if (shot != null) {
                AgentScreenshot(live.taskId, JSONObject().put("id", shot).put("url", live.screenshotUrl), vm)
            } else {
                // Her screen when there's no page to show: dark like a monitor, with what she's doing.
                val accent = com.localfirst.assistant.ui.theme.LocalFridayPalette.current.accent
                Box(
                    Modifier.fillMaxWidth().height(150.dp).padding(8.dp).clip(RoundedCornerShape(10.dp))
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF17171A), Color(0xFF221C1B)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(AppIcons.Computer, contentDescription = null, tint = accent.copy(alpha = if (working) 1f else .6f), modifier = Modifier.size(28.dp))
                        Text(
                            when { !state.agentReady -> "Offline"; working -> "Working · her screen appears when she opens a page"; else -> "Idle · ready for something to do" },
                            style = MaterialTheme.typography.labelMedium, color = Color(0xFFB9B4AE),
                        )
                    }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val ready = state.agentReady
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (!ready) MaterialTheme.colorScheme.error else if (working) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            !ready -> "Her computer is unavailable"
                            working -> live?.activity ?: "Working"
                            else -> "Her computer · idle"
                        },
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(if (ready) COMPUTER_SPEC else state.agentDetail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One plain sentence starts a chat where FRIDAY drafts the plan. */
@Composable
internal fun FridayAskBox(state: ChatUiState, vm: ChatViewModel) {
    var request by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = request,
        onValueChange = { request = it.take(4000) },
        placeholder = { Text("Give FRIDAY a task on your PC") },
        supportingText = { Text("Starts on your computer. Free cloud models may see relevant PC output; action approvals follow your access settings.") },
        trailingIcon = {
            IconButton(onClick = { vm.askFriday(request); request = "" }, enabled = request.isNotBlank() && !state.busy && !state.workspaceBusy && !state.pcBusy && state.pcStatus?.optBoolean("enabled")==true) {
                Icon(AppIcons.ArrowUpward, contentDescription = "Ask FRIDAY")
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
    )
}

/** A task row in FRIDAY's lists. */
@Composable
internal fun FridayTaskRow(task: JSONObject, state: ChatUiState, onClick: () -> Unit) {
    val id = task.optString("id")
    val live = state.fridayLive?.takeIf { it.taskId == id }
    FridayCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(task.optJSONObject("proposal")?.optString("prompt").orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                live?.activity ?: "${agentStatus(task.optString("status"))} · ${agentTime(task.optDouble("updated", task.optDouble("created")))}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What an approval means, in plain words. */
internal fun plainApproval(proposal: JSONObject): String = buildString {
    append(proposal.optString("prompt"))
    proposal.optJSONArray("plan")?.let { steps ->
        append("\n\nPlan:")
        for (i in 0 until steps.length()) append("\n${i + 1}. ${steps.getString(i)}")
    }
    proposal.optJSONObject("schedule")?.let { append("\n\n").append(scheduleDescription(it)) }
    append("\n\n").append(accountScopeDescription(proposal))
    append("\n\nFRIDAY works on her own computer, which has no access to your files. Her thinking runs on a free cloud model through OpenRouter, so this request and the pages she reads go to that service. ")
    append(ASKS_AGAIN)
}

/** A short "what she's doing now" line from one task event, or null to keep the previous one. */
internal fun fridayActivity(event: JSONObject): String? {
    val data = event.optJSONObject("data") ?: return null
    fun host(url: String) = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()?.takeIf(String::isNotBlank)
    return when (event.optString("kind")) {
        "broker_read_page" -> host(data.optString("url"))?.let { "Reading $it" } ?: "Reading a page"
        "screenshot" -> host(data.optString("url"))?.let { "Looking at $it" }
        "broker_model" -> "Thinking it through"
        "account_scopes_read" -> "Reading the account data you approved"
        "result" -> "Writing up the report"
        "opencode_event" -> data.optJSONObject("part")?.optString("tool")?.takeIf(String::isNotBlank)?.let { "Using ${it.replace('_', ' ')}" }
        "error" -> "Ran into a problem"
        else -> null
    }
}
