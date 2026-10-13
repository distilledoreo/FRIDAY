package com.localfirst.assistant.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.platform.PlatformSystem
import com.localfirst.assistant.platform.PlatformTime
internal expect fun resolveTaskStart(date: String, hour: Int, minute: Int, now: Long): Long
private fun localDate(time: Long): String = listOf(
    PlatformTime.field(time.toDouble(), false, "FullYear").toString().padStart(4, '0'),
    (PlatformTime.field(time.toDouble(), false, "Month") + 1).toString().padStart(2, '0'),
    PlatformTime.field(time.toDouble(), false, "Date").toString().padStart(2, '0'),
).joinToString("-")
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TaskEditor(busy: Boolean, save: (String, Long, Int) -> Unit) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var later by rememberSaveable { mutableStateOf(false) }
    val future = remember { PlatformSystem.currentTimeMillis() + 300000 }
    var date by rememberSaveable { mutableStateOf(localDate(future)) }
    var hour by rememberSaveable { mutableIntStateOf(PlatformTime.field(future.toDouble(), false, "Hours")) }
    var minute by rememberSaveable { mutableIntStateOf(PlatformTime.field(future.toDouble(), false, "Minutes")) }
    var repeat by rememberSaveable { mutableIntStateOf(0) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (showDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = com.localfirst.assistant.ui.genui.isoDateMillis(date))
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = {
            picker.selectedDateMillis?.let { date = PlatformTime.isoString(it.toDouble()).take(10) }; showDate = false
        }) { Text("Done") } }, dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { showDate = false }) { Text("Cancel") } }) { DatePicker(picker) }
    }
    if (showTime) {
        val picker = rememberTimePickerState(hour, minute)
        AlertDialog(onDismissRequest = { showTime = false }, title = { FridayDialogWindow(); Text("Start time") }, text = { TimeInput(picker) },
            confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { hour = picker.hour; minute = picker.minute; showTime = false }) { Text("Done") } }, dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { showTime = false }) { Text("Cancel") } })
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(prompt, { prompt = it }, label = { Text("What would you like me to do?") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Schedule for later", Modifier.weight(1f)); Switch(later, { later = it }) }
        if (later) {
            TextButton({ showDate = true }) { Text("Date: $date") }
            TextButton({ showTime = true }) { Text("Time: ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}") }
        }
        Text("Repeat", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.fillMaxWidth()) {
            listOf("Once" to 0, "Hourly" to 3600, "Daily" to 86400, "Weekly" to 604800).forEach { (label, seconds) -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { repeat = seconds }) { RadioButton(selected = repeat == seconds, onClick = { repeat = seconds }); Text(label) } }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(shape = MaterialTheme.shapes.small, onClick = {
            runCatching {
                val now = PlatformSystem.currentTimeMillis()
                val runAt = if (later) resolveTaskStart(date, hour, minute, now) else now
                error = null; save(prompt, runAt, repeat)
            }.onFailure { error = it.message }
        }, enabled = prompt.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (later) "Schedule task" else "Start task") }
        Text("Your computer needs to be online when the task runs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
