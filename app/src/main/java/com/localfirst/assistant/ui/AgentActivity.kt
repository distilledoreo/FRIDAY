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

/** Android owns the PC connection and native chat orchestration; the activity screen is shared. */
@Composable
internal fun AgentActivity(state: ChatUiState, vm: ChatViewModel) {
    if (state.privacy.incognito) { Text("FRIDAY isn't available in incognito."); return }
    com.localfirst.assistant.ui.workspace.ActivityScreen(vm.features, state.fridayTaskId, state.pcSessionId) { report ->
        val id = vm.features.state.value.task?.get("id")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        if (id != null) vm.discussAgentReport(id) else { vm.dismissWorkspace(); vm.onDraftChange("Discuss this report:\n$report") }
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
        TextButton(shape = MaterialTheme.shapes.small, onClick = { showDate = true }) { Text(date) }
        TextButton(shape = MaterialTheme.shapes.small, onClick = { showTime = true }) { Text(start.format(DateTimeFormatter.ofPattern("h:mm a z"))) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Once" to 0, "Hourly" to 3600, "Daily" to 86400, "Weekly" to 604800).forEach { (label, seconds) ->
            FilterChip(shape = MaterialTheme.shapes.small, selected = interval == seconds, onClick = { interval = seconds }, label = { Text(label) })
        }
    }
    if (interval > 0) OutlinedTextField(count, { count = it.filter(Char::isDigit).take(3) }, label = { Text("Number of runs · 1–100") }, modifier = Modifier.fillMaxWidth())
    if (!valid) Text("Choose a future time and 1–100 runs.", color = MaterialTheme.colorScheme.error)
    Text("Plan and schedule both need approval. The PC must be online. Repeat intervals use elapsed time.", style = MaterialTheme.typography.bodySmall)
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
