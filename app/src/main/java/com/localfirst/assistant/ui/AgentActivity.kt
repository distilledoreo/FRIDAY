package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

/** Approval controls are UI-only. Model tools can propose, never approve. */
@Composable
internal fun AgentActivity(state: ChatUiState, vm: ChatViewModel) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var plan by rememberSaveable { mutableStateOf("Read public sources\nPrepare a cited report") }
    var confirming by remember { mutableStateOf<JSONObject?>(null) }
    var action by remember { mutableStateOf<JSONObject?>(null) }
    var scheduled by rememberSaveable { mutableStateOf(false) }
    var schedule by remember { mutableStateOf<JSONObject?>(null) }
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
                Row { Text("Schedule", Modifier.weight(1f)); Switch(scheduled, { scheduled = it }) }
                if (scheduled) AgentScheduleEditor { schedule = it }
                Button(onClick = { vm.proposeAgentTask(prompt.trim(), plan.lines().map(String::trim).filter(String::isNotBlank), if (scheduled) schedule else null); prompt = "" }, enabled = prompt.isNotBlank() && plan.isNotBlank() && (!scheduled || schedule != null) && !state.workspaceBusy) { Text("Save plan for review") }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "In progress", "Scheduled", "Done").forEach { label ->
                        FilterChip(selected = category == label, onClick = { category = label }, label = { Text(label) })
                    }
                }
            }
            val visible = state.agentTasks.filter {
                when (category) {
                    "In progress" -> it.optString("status") in listOf("approved", "running")
                    "Scheduled" -> it.optString("status") == "scheduled"
                    "Done" -> it.optString("status") in listOf("done", "failed", "cancelled", "interrupted")
                    else -> true
                }
            }
            if (visible.isEmpty()) item { Text("No tasks here yet.") }
            items(visible, key = { it.getString("id") }) { entry ->
                OutlinedCard(onClick = { selected = entry.getString("id") }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(entry.getJSONObject("proposal").optString("prompt"), style = MaterialTheme.typography.titleSmall)
                        Text(agentStatus(entry.optString("status")), style = MaterialTheme.typography.bodySmall)
                        if (entry.getJSONObject("proposal").has("schedule_origin")) Text("Scheduled run · ${agentTime(entry.optDouble("created"))}", style = MaterialTheme.typography.bodySmall)
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
                    Text(agentStatus(current.optString("status")))
                    proposal.optJSONObject("schedule")?.let { Text(scheduleDescription(it), style = MaterialTheme.typography.bodySmall) }
                    current.optJSONObject("schedule_state")?.let {
                        if (it.optBoolean("blocked")) Text("Paused by an interrupted run or an action needing review. Cancel this schedule before creating a revised plan.", color = MaterialTheme.colorScheme.error)
                        Text("${it.optInt("remaining")} runs left · " + if (it.optInt("remaining") > 0) "Next: ${agentTime(it.optDouble("next_run"))}" else "Waiting for the final run to finish", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (current.optString("status") == "proposed") Button(onClick = { confirming = current }, enabled = state.agentReady && !state.workspaceBusy) { Text("Review and approve") }
                        if (current.optString("status") in listOf("proposed", "approved", "running", "awaiting_setup", "scheduled")) OutlinedButton(onClick = { vm.cancelAgentTask(current.getString("id")) }, enabled = !state.workspaceBusy) { Text(if (proposal.has("schedule")) "Cancel schedule and active runs" else "Cancel task") }
                    }
                }
                val runs = current.optJSONArray("runs")?.let { values -> (0 until values.length()).map { values.getJSONObject(it) } }.orEmpty()
                items(runs, key = { "run-${it.getString("id")}" }) { run ->
                    TextButton(onClick = { selected = run.getString("id") }) { Text("${agentTime(run.optDouble("created"))} · ${agentStatus(run.optString("status"))}") }
                }
                current.optJSONObject("result")?.optString("text")?.takeIf(String::isNotBlank)?.let { report ->
                    item {
                        Text("Report", style = MaterialTheme.typography.titleMedium)
                        Text(report, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.discussAgentReport(current.getString("id")) }, enabled = !state.busy) { Text("Discuss in chat") }
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
                        val data = event.getJSONObject("data")
                        val summary = agentEventSummary(event)
                        Text(summary, style = MaterialTheme.typography.bodySmall)
                        var expanded by remember(event.getLong("seq")) { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Show details") }
                        if (expanded) Text(data.toString(2), style = MaterialTheme.typography.bodySmall)
                        if (event.optString("kind") == "screenshot") AgentScreenshot(current.getString("id"), event.getJSONObject("data"), vm)
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
            text = { Text("${exact.getJSONObject("proposal").optJSONObject("schedule")?.let(::scheduleDescription).orEmpty()}\n\n${exact.getJSONObject("proposal").toString(2)}\n\nThis approved task and gathered public sources go to a free cloud model via OpenRouter. This permits public browsing and reading within the plan. Other actions require another approval.", Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { vm.approveAgentTask(exact.getString("id"), exact.getString("fingerprint")); confirming = null }) { Text("Approve") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Back") } })
    }
    action?.let { exact ->
        AlertDialog(onDismissRequest = { action = null }, title = { Text("Approve this exact action?") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                exact.optJSONObject("review")?.let { review ->
                    Text(review.optString("reviewer"), style = MaterialTheme.typography.labelLarge)
                    Text(if (review.optBoolean("allowed")) "Payload checks passed; executor still unavailable." else "Payload needs changes before it can run.")
                    review.optJSONArray("issues")?.let { for (i in 0 until it.length()) Text("• ${it.getString(i)}", color = MaterialTheme.colorScheme.error) }
                    review.optJSONObject("account")?.let { Text("Account: ${it.optString("label")} · ${it.optString("provider")}") }
                    Text(review.optString("side_effect"))
                }
                Text(exact.getJSONObject("payload").toString(2))
            } },
            confirmButton = { if (state.agentOutgoingReady) TextButton(onClick = { task?.let { vm.approveAgentAction(it.getString("id"), exact.getString("id"), exact.getString("fingerprint")) }; action = null }) { Text("Approve action") } else Text("Account actions are still being connected.", style = MaterialTheme.typography.bodySmall) },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Back") } })
    }
}

private fun agentStatus(status: String): String = when (status) {
    "proposed" -> "Needs approval"
    "approved" -> "Queued"
    "running" -> "In progress"
    "done" -> "Done"
    "failed" -> "Failed"
    "cancelled" -> "Cancelled"
    "interrupted" -> "Interrupted · review needed"
    "awaiting_setup" -> "Account setup needed"
    "scheduled" -> "Scheduled"
    else -> status.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

@Composable
private fun AgentScreenshot(taskId: String, data: JSONObject, vm: ChatViewModel) {
    val id = data.optString("id")
    var bitmap by remember(taskId, id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(taskId, id) {
        bitmap = runCatching { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(vm.loadAgentScreenshot(taskId, id).absolutePath) } }.getOrNull()
    }
    bitmap?.let { Image(it.asImageBitmap(), "Page preview: ${data.optString("url")}", Modifier.fillMaxWidth().heightIn(max = 350.dp)) }
        ?: Text("Page preview loading or unavailable", style = MaterialTheme.typography.bodySmall)
}

private fun agentTime(seconds: Double): String = Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a z"))

private fun scheduleDescription(schedule: JSONObject): String {
    val interval = schedule.optInt("interval_seconds")
    val cadence = if (interval == 0) "One run" else "Every ${interval / 3600} hours · ${schedule.optInt("max_runs")} runs"
    return "$cadence · starts ${agentTime(schedule.optDouble("run_at"))}\nRepeats use elapsed time; daylight-saving changes can shift the local hour. Missed intervals do not run in a burst. Cancel the schedule to stop future and active runs."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentScheduleEditor(changed: (JSONObject?) -> Unit) {
    val future = remember { ZonedDateTime.now().plusMinutes(10) }
    var date by rememberSaveable { mutableStateOf(future.toLocalDate().toString()) }
    var hour by rememberSaveable { mutableIntStateOf(future.hour) }
    var minute by rememberSaveable { mutableIntStateOf(future.minute) }
    var interval by rememberSaveable { mutableIntStateOf(0) }
    var count by rememberSaveable { mutableStateOf("7") }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val start = LocalDate.parse(date).atTime(hour, minute).atZone(ZoneId.systemDefault())
    val valid = start.isAfter(ZonedDateTime.now()) && (interval == 0 || count.toIntOrNull() in 1..100)
    LaunchedEffect(date, hour, minute, interval, count, valid) {
        changed(if (valid) JSONObject().put("run_at", start.toEpochSecond()).put("interval_seconds", interval)
            .put("max_runs", if (interval == 0) 1 else count.toInt()).put("timezone", ZoneId.systemDefault().id) else null)
    }
    Row {
        TextButton(onClick = { showDate = true }) { Text(date) }
        TextButton(onClick = { showTime = true }) { Text(start.format(DateTimeFormatter.ofPattern("h:mm a z"))) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Once" to 0, "Hourly" to 3600, "Daily" to 86400, "Weekly" to 604800).forEach { (label, seconds) ->
            FilterChip(selected = interval == seconds, onClick = { interval = seconds }, label = { Text(label) })
        }
    }
    if (interval > 0) OutlinedTextField(count, { count = it.filter(Char::isDigit).take(3) }, label = { Text("Number of runs · 1–100") }, modifier = Modifier.fillMaxWidth())
    if (!valid) Text("Choose a future time and 1–100 runs.", color = MaterialTheme.colorScheme.error)
    Text("Plan and schedule both need approval. The PC must be online. Repeat intervals use elapsed time.", style = MaterialTheme.typography.bodySmall)
    if (showDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = { TextButton(onClick = {
            picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }; showDate = false
        }) { Text("Done") } }, dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } }) { DatePicker(picker) }
    }
    if (showTime) {
        val picker = rememberTimePickerState(hour, minute)
        AlertDialog(onDismissRequest = { showTime = false }, title = { Text("Start time") }, text = { TimeInput(picker) },
            confirmButton = { TextButton(onClick = { hour = picker.hour; minute = picker.minute; showTime = false }) { Text("Done") } }, dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } })
    }
}

private fun agentEventSummary(event: JSONObject): String {
    val data = event.getJSONObject("data")
    return when (event.optString("kind")) {
        "event_fragment" -> "Complete ${data.optString("kind").replace('_', ' ')} record · part ${data.optInt("index") + 1} of ${data.optInt("count")}. Open details to read this part."
        "broker_model" -> "Cloud model request ${data.optInt("request")} · ${data.optString("model")}"
        "broker_read_page" -> "Read ${data.optString("url")} · ${data.optInt("chars")} characters"
        "opencode_event" -> data.optJSONObject("part")?.let { part ->
            part.optString("text").ifBlank { part.optString("tool").ifBlank { data.optString("type").replace('_', ' ') } }
        } ?: data.optString("type").replace('_', ' ')
        "result" -> "Report saved. Read it above or discuss it in chat."
        else -> data.optString("text").ifBlank { data.optString("url").ifBlank { data.optString("message").ifBlank { "Details recorded" } } }
    }.take(600)
}
