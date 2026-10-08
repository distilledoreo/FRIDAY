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
import org.json.JSONArray
import java.time.*
import java.time.format.DateTimeFormatter

/** Approval controls are UI-only. Model tools can propose, never approve. */
@Composable
internal fun AgentActivity(state: ChatUiState, vm: ChatViewModel) {
    var selected by rememberSaveable(state.fridayTaskId) { mutableStateOf(state.fridayTaskId) }
    var writingPlan by rememberSaveable { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var plan by rememberSaveable { mutableStateOf("Read public sources\nPrepare a cited report") }
    var confirming by remember { mutableStateOf<JSONObject?>(null) }
    var action by remember { mutableStateOf<JSONObject?>(null) }
    var scheduled by rememberSaveable { mutableStateOf(false) }
    var schedule by remember { mutableStateOf<JSONObject?>(null) }
    var category by rememberSaveable { mutableStateOf("All") }
    var cursors by remember { mutableStateOf(listOf(0L)) }
    var mailScopes by remember { mutableStateOf(setOf<String>()) }
    var calendarScopes by remember { mutableStateOf(setOf<String>()) }
    val task = state.agentTask?.takeIf { it.optString("id") == selected }
    LaunchedEffect(selected) { cursors = listOf(0L); vm.refreshAgentActivity(selected, 0) }
    LaunchedEffect(selected, state.agentOutgoingBusy, state.agentTasks.any { it.optString("status") in listOf("running", "approved") }) {
        while (state.agentOutgoingBusy || state.agentTasks.any { it.optString("status") in listOf("running", "approved") }) {
            delay(3000)
            vm.refreshAgentActivity(selected)
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selected == null) {
            item {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FridayComputerCard(state, vm)
                    if (state.privacy.incognito) Text("FRIDAY isn’t available in incognito.", style = MaterialTheme.typography.bodySmall)
                    else FridayAskBox(state, vm)
                }
            }
            val groups = listOf(
                "Needs your OK" to state.agentTasks.filter { it.optString("status") in FRIDAY_NEEDS_YOU },
                "Working now" to state.agentTasks.filter { it.optString("status") in FRIDAY_WORKING },
                "Scheduled" to state.agentTasks.filter { it.optString("status") == "scheduled" },
                "Done" to state.agentTasks.filter { it.optString("status") in listOf("done", "failed", "cancelled") }.take(10),
            )
            if (groups.all { it.second.isEmpty() }) item {
                Text("Nothing yet. Ask FRIDAY to look something up, compare prices, or keep an eye on something for you.", style = MaterialTheme.typography.bodyMedium)
            }
            groups.filter { it.second.isNotEmpty() }.forEach { (title, tasks) ->
                item(key = "group-$title") { Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(tasks, key = { "$title-${it.getString("id")}" }) { entry -> FridayTaskRow(entry, state) { selected = entry.getString("id") } }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.openWorkspace(WorkspaceDestination.TASKS) }) { Text("Scheduled chats") }
                    TextButton(onClick = { writingPlan = !writingPlan }) { Text(if (writingPlan) "Hide plan editor" else "Write a plan yourself") }
                }
            }
            if (writingPlan) item {
                OutlinedTextField(prompt, { prompt = it.take(8000) }, label = { Text("What should FRIDAY work on?") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(plan, { plan = it.take(12000) }, label = { Text("Plan · one step per line") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row { Text("Schedule", Modifier.weight(1f)); Switch(scheduled, { scheduled = it }) }
                if (scheduled) AgentScheduleEditor { schedule = it }
                if (state.connectedAccounts.isNotEmpty()) {
                    Text("Account data for this cloud task", style = MaterialTheme.typography.titleSmall)
                    Text("Choose exact reads to review. Only selected data goes to OpenRouter and a free cloud model. Private task reports are saved in Activity. Public web tools are disabled for account data tasks.", style = MaterialTheme.typography.bodySmall)
                    state.connectedAccounts.forEach { account ->
                        val id = account.getString("id")
                        Text(account.optString("label"))
                        Row { Checkbox(id in mailScopes, { mailScopes = if (it) mailScopes + id else mailScopes - id }); Text("Share up to 10 inbox headers", Modifier.weight(1f)) }
                        if (account.optString("provider") != "imap") Row { Checkbox(id in calendarScopes, { calendarScopes = if (it) calendarScopes + id else calendarScopes - id }); Text("Share up to 30 events in next 7 days", Modifier.weight(1f)) }
                    }
                    if (scheduled && calendarScopes.isNotEmpty()) Text("Calendar dates are fixed when this plan is saved; repeated runs keep that same window.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    val reads = JSONArray()
                    val now = OffsetDateTime.now()
                    state.connectedAccounts.forEach { account ->
                        val id = account.getString("id")
                        if (id in mailScopes) reads.put(JSONObject().put("account_id", id).put("kind", "inbox").put("limit", 10))
                        if (id in calendarScopes) reads.put(JSONObject().put("account_id", id).put("kind", "calendar").put("start", now.toString()).put("end", now.plusDays(7).toString()).put("limit", 30))
                    }
                    vm.proposeAgentTask(prompt.trim(), plan.lines().map(String::trim).filter(String::isNotBlank), if (scheduled) schedule else null, reads)
                    prompt = ""; mailScopes = emptySet(); calendarScopes = emptySet()
                }, enabled = prompt.isNotBlank() && plan.isNotBlank() && (!scheduled || schedule != null) && mailScopes.size + calendarScopes.size <= 5 && !state.workspaceBusy) { Text("Save plan for review") }
            }
        } else {
            item { TextButton(onClick = { selected = null }, modifier = Modifier.padding(top = 8.dp)) { Text("All of FRIDAY’s work") } }
            task?.takeIf { it.optString("status") in FRIDAY_WORKING }?.let { item { FridayComputerCard(state, vm, it.getString("id")) } }
            task?.let { current ->
                item {
                    val proposal = current.getJSONObject("proposal")
                    Text(proposal.optString("prompt"), style = MaterialTheme.typography.titleMedium)
                    val steps = proposal.optJSONArray("plan")
                    steps?.let { for (i in 0 until it.length()) Text("${i + 1}. ${it.getString(i)}") }
                    Text(agentStatus(current.optString("status")))
                    proposal.optJSONObject("schedule")?.let { Text(scheduleDescription(it), style = MaterialTheme.typography.bodySmall) }
                    Text(accountScopeDescription(proposal), style = MaterialTheme.typography.bodySmall)
                    current.optJSONObject("schedule_state")?.let {
                        if (it.optBoolean("blocked")) Text("Paused by an interrupted run or an action needing review. Cancel this schedule before creating a revised plan.", color = MaterialTheme.colorScheme.error)
                        Text("${it.optInt("remaining")} runs left · " + if (it.optInt("remaining") > 0) "Next: ${agentTime(it.optDouble("next_run"))}" else "Waiting for the final run to finish", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (current.optString("status") == "proposed") Button(onClick = { confirming = current }, enabled = state.agentReady && !state.workspaceBusy) { Text("Review and start") }
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
                val outgoing = current.optJSONArray("actions")?.let { values -> (0 until values.length()).map { values.getJSONObject(it) } }.orEmpty()
                items(outgoing, key = { it.getString("id") }) { pendingAction ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("${pendingAction.getJSONObject("payload").optString("kind")} · ${outgoingStatus(pendingAction.optString("status"))}")
                            if (pendingAction.optString("status") == "proposed") TextButton(onClick = { action = pendingAction }, enabled = !state.workspaceBusy) { Text("Review exact action") }
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
        AlertDialog(onDismissRequest = { confirming = null }, title = { Text("Start this task?") },
            text = { Text(plainApproval(exact.getJSONObject("proposal")), Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { vm.approveAgentTask(exact.getString("id"), exact.getString("fingerprint")); confirming = null }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Back") } })
    }
    action?.let { exact ->
        AlertDialog(onDismissRequest = { action = null }, title = { Text("Approve this exact action?") },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                exact.optJSONObject("review")?.let { review ->
                    Text(review.optString("reviewer"), style = MaterialTheme.typography.labelLarge)
                    Text(if (review.optBoolean("allowed")) review.optString("detail") else "Payload needs changes before it can run.")
                    review.optJSONArray("issues")?.let { for (i in 0 until it.length()) Text("• ${it.getString(i)}", color = MaterialTheme.colorScheme.error) }
                    review.optJSONObject("account")?.let { Text("Account: ${it.optString("label")} · ${it.optString("provider")}") }
                    review.optJSONObject("identity")?.let { identity ->
                        Text("Sender: ${identity.optString("sender")}")
                        if (identity.has("server")) Text("SMTP: ${identity.optString("server")}:${identity.optInt("port")} · login ${identity.optString("login")}")
                    }
                    Text(review.optString("side_effect"))
                }
                Text(exact.getJSONObject("payload").toString(2))
                Text("Submit once. Cancellation cannot recall an action already submitted. A timeout or interrupted receipt requires checking provider records before another proposal.", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { if (state.agentOutgoingReady && exact.optJSONObject("review")?.optBoolean("executable") == true) TextButton(onClick = { task?.let { vm.approveAgentAction(it.getString("id"), exact.getString("id"), exact.getString("fingerprint"), exact.getJSONObject("review").getString("review_fingerprint")) }; action = null }) { Text("Approve exact action once") } else Text("Outgoing activation or account permission is unavailable.", style = MaterialTheme.typography.bodySmall) },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Back") } })
    }
}

private fun outgoingStatus(status: String): String = when (status) {
    "proposed" -> "Needs exact approval"
    "approved", "claimed" -> "Submitting once · receipt pending"
    "accepted" -> "Provider accepted · delivery not confirmed"
    "rejected" -> "Rejected · create a new reviewed proposal"
    "uncertain" -> "Outcome uncertain · check provider records before another proposal"
    "cancelled" -> "Canceled before submission"
    else -> status
}

internal fun agentStatus(status: String): String = when (status) {
    "proposed" -> "Needs your OK"
    "approved" -> "Queued"
    "running" -> "In progress"
    "done" -> "Done"
    "failed" -> "Failed"
    "cancelled" -> "Cancelled"
    "interrupted" -> "Interrupted · review needed"
    "awaiting_setup" -> "Outgoing review or setup needed"
    "scheduled" -> "Scheduled"
    else -> status.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

@Composable
internal fun AgentScreenshot(taskId: String, data: JSONObject, vm: ChatViewModel) {
    val id = data.optString("id")
    var bitmap by remember(taskId, id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(taskId, id) {
        bitmap = runCatching { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(vm.loadAgentScreenshot(taskId, id).absolutePath) } }.getOrNull()
    }
    bitmap?.let { Image(it.asImageBitmap(), "Page preview: ${data.optString("url")}", Modifier.fillMaxWidth().heightIn(max = 350.dp)) }
        ?: Text("Page preview loading or unavailable", style = MaterialTheme.typography.bodySmall)
}

internal fun agentTime(seconds: Double): String = Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a z"))

internal fun scheduleDescription(schedule: JSONObject): String {
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
        "account_scopes_read" -> "Read ${data.optInt("reads")} approved account scopes. Public web tools disabled."
        "broker_model" -> "Cloud model request ${data.optInt("request")} · ${data.optString("model")}"
        "broker_read_page" -> "Read ${data.optString("url")} · ${data.optInt("chars")} characters"
        "opencode_event" -> data.optJSONObject("part")?.let { part ->
            part.optString("text").ifBlank { part.optString("tool").ifBlank { data.optString("type").replace('_', ' ') } }
        } ?: data.optString("type").replace('_', ' ')
        "result" -> "Report saved. Read it above or discuss it in chat."
        else -> data.optString("text").ifBlank { data.optString("url").ifBlank { data.optString("message").ifBlank { "Details recorded" } } }
    }.take(600)
}

internal fun accountScopeDescription(proposal: JSONObject): String {
    val scopes = proposal.optJSONArray("data_scopes")
    if (scopes == null || scopes.length() == 0) return "Account data: none. This task uses public sources."
    return "Private account data shared with OpenRouter/free cloud after approval:\n" + (0 until scopes.length()).joinToString("\n") { index ->
        val scope = scopes.getJSONObject(index)
        val label = scope.optJSONObject("account")?.optString("label").orEmpty()
        when (scope.optString("kind")) {
            "inbox" -> "$label · up to ${scope.optInt("limit")} inbox headers · query: ${scope.optString("query").ifBlank { "recent inbox" }}"
            "message" -> "$label · bounded text of message ${scope.optString("message_id")}"
            "calendar" -> "$label · up to ${scope.optInt("limit")} events · ${scope.optString("start")} to ${scope.optString("end") }"
            else -> "$label · unsupported scope; review a new plan"
        }
    } + "\nPublic web tools disabled. The saved report may contain private data."
}
