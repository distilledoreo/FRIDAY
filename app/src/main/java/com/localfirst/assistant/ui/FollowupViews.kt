@file:OptIn(ExperimentalMaterial3Api::class)
package com.localfirst.assistant.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** The zone follow-up times are shown and entered in: the brief's, else the phone's. */
internal fun briefZone(timezone: String?): ZoneId =
    timezone?.takeIf(String::isNotBlank)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

/** Quick times for "remind me": later today, this evening, tomorrow morning, next week. */
internal fun followupPresets(zone: ZoneId, now: ZonedDateTime = ZonedDateTime.now(zone)): List<Pair<String, ZonedDateTime>> {
    val later = now.plusHours(3).truncatedTo(ChronoUnit.HOURS)
    val evening = now.withHour(18).truncatedTo(ChronoUnit.HOURS)
    val tomorrow = now.plusDays(1).withHour(9).truncatedTo(ChronoUnit.HOURS)
    val nextWeek = now.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).withHour(9).truncatedTo(ChronoUnit.HOURS)
    return buildList {
        if (later.toLocalDate() == now.toLocalDate()) add("Later today" to later)
        if (evening.isAfter(now.plusMinutes(30)) && evening != later) add("This evening" to evening)
        add("Tomorrow morning" to tomorrow)
        add("Next week" to nextWeek)
    }
}

/** "Today · 3:00 PM", "Tomorrow · 9:00 AM", "Mon, Oct 12 · 9:00 AM", or "Overdue · …". */
internal fun dueLabel(seconds: Double, zone: ZoneId, now: ZonedDateTime = ZonedDateTime.now(zone)): String {
    val due = Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(zone)
    val time = due.format(DateTimeFormatter.ofPattern("h:mm a"))
    val day = when (ChronoUnit.DAYS.between(now.toLocalDate(), due.toLocalDate())) {
        0L -> "Today"; 1L -> "Tomorrow"; -1L -> "Yesterday"
        else -> due.format(DateTimeFormatter.ofPattern(if (due.year == now.year) "EEE, MMM d" else "MMM d, yyyy"))
    }
    return (if (due.isBefore(now)) "Overdue · " else "") + "$day · $time"
}

private fun JSONObject.dueSeconds(): Double? = if (isNull("due")) null else optDouble("due").takeIf { it.isFinite() }

/**
 * One follow-up you can act on in place: tick it off, push it back, talk it through or delete it.
 * [onTalk] starts a chat about it, when the host can show one.
 */
@Composable
internal fun FollowupRow(item: JSONObject, zone: ZoneId, enabled: Boolean, vm: ChatViewModel, onTalk: ((String) -> Unit)? = null) {
    val id = item.getString("id")
    val title = item.optString("title")
    var done by remember(id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val due = item.dueSeconds()
    val overdue = due != null && due * 1000 < System.currentTimeMillis()
    val accent = LocalFridayPalette.current.accent
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { menu = true }.heightIn(min = 56.dp).padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { done = true; vm.changeFollowup(id) }, enabled = enabled && !done) {
            Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, if (done) accent else MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                if (done) Box(Modifier.size(12.dp).clip(CircleShape).border(6.dp, accent, CircleShape))
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            due?.let { Text(dueLabel(it, zone), style = MaterialTheme.typography.bodySmall, color = if (overdue && !done) accent else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled) { Icon(LineIcons.More, "Options for $title", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                Text(if (due == null) "Remind me" else "Move to", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                followupPresets(zone).forEach { (label, time) ->
                    DropdownMenuItem(text = { Text(label) }, trailingIcon = { Text(time.format(DateTimeFormatter.ofPattern(if (time.toLocalDate() == LocalDate.now(zone)) "h:mm a" else "EEE h:mm a")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { menu = false; vm.snoozeFollowup(id, time.toOffsetDateTime().toString()) })
                }
                DropdownMenuItem(text = { Text("Pick a time…") }, leadingIcon = null, onClick = { menu = false; picking = true })
                HorizontalDivider()
                onTalk?.let { talk -> DropdownMenuItem(text = { Text("Talk it through") }, leadingIcon = { Icon(LineIcons.Chat, null, Modifier.size(20.dp)) }, onClick = { menu = false; talk("Help me with this follow-up: $title") }) }
                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; vm.changeFollowup(id, delete = true) })
            }
        }
    }
    if (picking) WhenPicker(zone, onDismiss = { picking = false }) { picked -> picking = false; vm.snoozeFollowup(id, picked.toOffsetDateTime().toString()) }
}

/** An inline "add a follow-up" field with quick reminder times. */
@Composable
internal fun AddFollowup(zone: ZoneId, enabled: Boolean, vm: ChatViewModel, startOpen: Boolean = false) {
    var open by remember { mutableStateOf(startOpen) }
    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<ZonedDateTime?>(null) }
    var picking by remember { mutableStateOf(false) }
    if (!open) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.heightIn(min = 52.dp).padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(LineIcons.Add, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Text("Add a follow-up", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    fun save() {
        if (text.isBlank()) return
        vm.addFollowup(text, due?.toOffsetDateTime()?.toString())
        text = ""; due = null; open = startOpen
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it.take(800) }, placeholder = { Text("What should I remind you about?") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(), enabled = enabled,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
            trailingIcon = { IconButton(onClick = ::save, enabled = enabled && text.isNotBlank()) { Icon(LineIcons.Send, "Save follow-up", Modifier.size(20.dp)) } })
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(due == null, { due = null }, label = { Text("No reminder") }, shape = MaterialTheme.shapes.small)
            followupPresets(zone).take(3).forEach { (label, time) -> FilterChip(due == time, { due = time }, label = { Text(label) }, shape = MaterialTheme.shapes.small) }
            val custom = due?.takeIf { d -> followupPresets(zone).none { it.second == d } }
            FilterChip(custom != null, { picking = true }, label = { Text(custom?.let { dueLabel(it.toEpochSecond().toDouble(), zone) } ?: "Pick…") }, shape = MaterialTheme.shapes.small)
        }
    }
    if (picking) WhenPicker(zone, onDismiss = { picking = false }) { due = it; picking = false }
}

/** A date, then a time: Material pickers in FRIDAY's dialog style. */
@Composable
internal fun WhenPicker(zone: ZoneId, onDismiss: () -> Unit, onPicked: (ZonedDateTime) -> Unit) {
    var date by remember { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now(zone)
    if (date == null) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = onDismiss,
            confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { date = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: today }) { Text("Next") } },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("Cancel") } }) { FridayDialogWindow(); DatePicker(picker) }
    } else ClockDialog("Remind me at", LocalTime.of(9, 0), onDismiss) { time -> onPicked(date!!.atTime(time).atZone(zone)) }
}

/** A time of day. */
@Composable
internal fun ClockDialog(title: String, initial: LocalTime, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    val picker = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)
    AlertDialog(onDismissRequest = onDismiss, title = { FridayDialogWindow(); Text(title) }, text = { TimePicker(picker) },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { onPicked(LocalTime.of(picker.hour, picker.minute)) }) { Text("Done") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("Cancel") } })
}
