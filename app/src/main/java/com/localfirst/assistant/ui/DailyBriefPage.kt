package com.localfirst.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How often FRIDAY may offer a check-in, in hours, as the brief stores it. */
private val CADENCES = listOf(72 to "Every few days", 168 to "About once a week", 336 to "Every two weeks", 720 to "About once a month")

/**
 * Dashboard and daily brief settings: where today comes from, what's included, and when FRIDAY may
 * reach out. Every change saves as it's made.
 */
@Composable
internal fun DailyBriefPage(state: ChatUiState, vm: ChatViewModel, onOpen: (WorkspaceDestination) -> Unit = {}) {
    val preferences = state.briefPreferences
    val context = LocalContext.current
    var form by remember(preferences?.optString("revision")) { mutableStateOf(preferences?.let { JSONObject(it.toString()).apply { remove("revision"); remove("calendar_account") } }) }
    val enabled = !state.workspaceBusy && !state.busy && !state.privacy.incognito
    var dialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshBrief() }
    fun change(vararg pairs: Pair<String, Any>) {
        val next = form?.let { current -> JSONObject(current.toString()).apply { pairs.forEach { (k, v) -> put(k, v) } } } ?: return
        form = next
        vm.saveBriefPreferences(JSONObject(next.toString()))
    }
    val zone = briefZone(form?.optString("timezone"))

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("What shows on your dashboard when you swipe up, and when FRIDAY may check in.", Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.privacy.incognito) { item { DashCard { DashMessage("Leave incognito to change these settings.") } }; return@LazyColumn }
        val current = form
        if (current == null) { item { DashCard { if (state.workspaceBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)); DashMessage(if (state.workspaceBusy) "Loading…" else "Connect to your computer to change these settings.") } }; return@LazyColumn }

        item { BriefLabel("Sources") }
        item {
            DashCard {
                val account = state.connectedAccounts.firstOrNull { it.optString("id") == current.optString("calendar_account_id") }
                val phoneAllowed = com.localfirst.assistant.phone.PermissionBroker.isGranted(context, android.Manifest.permission.READ_CALENDAR)
                SettingRow(LineIcons.Calendar, "Calendar", account?.optString("label") ?: if (phoneAllowed) "This phone’s calendar" else "Not connected", enabled) { dialog = "calendar" }
                SettingDivider()
                SettingRow(LineIcons.Weather, "Weather", current.optJSONObject("weather")?.optString("label") ?: "Off", enabled) { dialog = "weather" }
                if (current.optJSONObject("weather") != null) {
                    SettingDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 52.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Units", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        SingleChoiceSegmentedButtonRow {
                            listOf("fahrenheit" to "°F", "celsius" to "°C").forEachIndexed { i, (value, label) ->
                                SegmentedButton(current.optString("temperature_unit", "celsius") == value, { change("temperature_unit" to value) }, SegmentedButtonDefaults.itemShape(i, 2), enabled = enabled, icon = {}) { Text(label) }
                            }
                        }
                    }
                }
                SettingDivider()
                val phoneZone = ZoneId.systemDefault().id
                val zoneText = current.optString("timezone").ifBlank { phoneZone }
                SettingRow(LineIcons.Globe, "Time zone", if (zoneText == phoneZone) "${zoneText.replace('_', ' ')} · same as phone" else zoneText.replace('_', ' '), enabled) { dialog = "timezone" }
            }
        }

        item { BriefLabel("Show") }
        item {
            DashCard {
                SwitchRow(LineIcons.Checklist, "Follow-ups", "Things you asked to be reminded about", current.optBoolean("include_followups"), enabled) { change("include_followups" to it) }
                SettingDivider()
                SwitchRow(LineIcons.Chat, "Open topics", "Conversations FRIDAY thinks you may want to pick back up", current.optBoolean("include_situations"), enabled) { change("include_situations" to it) }
            }
        }

        item { BriefLabel("Notifications") }
        item {
            DashCard {
                val morning = current.optString("morning_time").let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(7, 30)
                SwitchRow(LineIcons.Bell, "Morning brief", "A nudge to look at your day", current.optBoolean("morning_enabled"), enabled) { change("morning_enabled" to it) }
                if (current.optBoolean("morning_enabled")) TimeRow("Around", morning, enabled) { dialog = "morning" }
                SettingDivider()
                SwitchRow(LineIcons.Friday, "Check-ins", "FRIDAY offers to revisit a follow-up or open topic", current.optBoolean("proactive_enabled"), enabled) { change("proactive_enabled" to it) }
                if (current.optBoolean("proactive_enabled")) {
                    var open by remember { mutableStateOf(false) }
                    val hours = current.optInt("cadence_hours", 72)
                    Box {
                        ValueRow("How often", CADENCES.firstOrNull { it.first == hours }?.second ?: "Every $hours hours", enabled) { open = true }
                        DropdownMenu(open, { open = false }) {
                            CADENCES.forEach { (h, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; change("cadence_hours" to h) }, trailingIcon = { if (h == hours) Text("✓", color = LocalFridayPalette.current.accent) }) }
                        }
                    }
                }
                SettingDivider()
                val start = runCatching { LocalTime.parse(current.optString("quiet_start")) }.getOrNull() ?: LocalTime.of(22, 0)
                val end = runCatching { LocalTime.parse(current.optString("quiet_end")) }.getOrNull() ?: LocalTime.of(7, 0)
                SettingRow(LineIcons.Moon, "Quiet hours", if (start == end) "No notifications at all" else "No notifications between", enabled, chevron = false) {}
                TimeRow("From", start, enabled) { dialog = "quiet_start" }
                TimeRow("Until", end, enabled) { dialog = "quiet_end" }
                Spacer(Modifier.height(6.dp))
            }
        }

        item { BriefLabel("Follow-ups") }
        item {
            DashCard {
                if (state.briefFollowups.isEmpty()) Text("Nothing waiting on you.", Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.briefFollowups.forEach { item -> key(item.optString("id")) { FollowupRow(item, zone, enabled, vm) } }
                AddFollowup(zone, enabled, vm)
            }
        }

        item {
            Text("Weather looks up only the city you choose. Notifications never include your calendar or notes, and Android may deliver them a few minutes late.",
                Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val current = form ?: return
    when (dialog) {
        "morning" -> ClockDialog("Morning brief", runCatching { LocalTime.parse(current.optString("morning_time")) }.getOrDefault(LocalTime.of(7, 30)), { dialog = null }) { dialog = null; change("morning_time" to hhmm(it)) }
        "quiet_start" -> ClockDialog("Quiet from", runCatching { LocalTime.parse(current.optString("quiet_start")) }.getOrDefault(LocalTime.of(22, 0)), { dialog = null }) { dialog = null; change("quiet_start" to hhmm(it)) }
        "quiet_end" -> ClockDialog("Quiet until", runCatching { LocalTime.parse(current.optString("quiet_end")) }.getOrDefault(LocalTime.of(7, 0)), { dialog = null }) { dialog = null; change("quiet_end" to hhmm(it)) }
        "calendar" -> ChoiceDialog("Calendar", { dialog = null }) {
            val selected = current.optString("calendar_account_id")
            state.connectedAccounts.filter { it.optString("provider") in listOf("google", "microsoft") }.forEach { account ->
                ChoiceRow(account.optString("label"), account.optString("provider").replaceFirstChar(Char::uppercase), selected == account.optString("id")) { dialog = null; change("calendar_account_id" to account.getString("id")) }
            }
            ChoiceRow("This phone’s calendar", "Whatever calendars are synced to this phone", current.isNull("calendar_account_id") || selected.isBlank()) {
                dialog = null
                if (!current.isNull("calendar_account_id") && selected.isNotBlank()) change("calendar_account_id" to JSONObject.NULL)
                vm.allowPhoneCalendar()
            }
            ChoiceRow("Connect an account…", "Google or Microsoft, through your computer", false) { dialog = null; onOpen(WorkspaceDestination.ACCOUNTS) }
        }
        "weather" -> WeatherDialog(state, vm, enabled, { dialog = null }) { location -> dialog = null; change("weather" to (location ?: JSONObject.NULL)) }
        "timezone" -> TimeZoneDialog(current.optString("timezone"), { dialog = null }) { dialog = null; change("timezone" to it) }
    }
}

private fun hhmm(time: LocalTime) = time.format(DateTimeFormatter.ofPattern("HH:mm"))
private fun clock(time: LocalTime) = time.format(DateTimeFormatter.ofPattern("h:mm a"))

@Composable private fun BriefLabel(text: String) =
    Text(text, Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable private fun SettingDivider() = HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))

@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String?, enabled: Boolean, chevron: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled && chevron, onClick = onClick).heightIn(min = 60.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        DashText(title, value)
        if (chevron) Chevron()
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.heightIn(min = 64.dp).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

/** An indented "label ........ value ›" row under a setting. */
@Composable
private fun ValueRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 48.dp).padding(start = 52.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
        Chevron()
    }
}

@Composable private fun TimeRow(label: String, time: LocalTime, enabled: Boolean, onClick: () -> Unit) = ValueRow(label, clock(time), enabled, onClick)

@Composable
private fun ChoiceDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { FridayDialogWindow(); Text(title) }, text = { Column(content = content) },
        confirmButton = {}, dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ChoiceRow(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            detail?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun WeatherDialog(state: ChatUiState, vm: ChatViewModel, enabled: Boolean, onDismiss: () -> Unit, onPick: (JSONObject?) -> Unit) {
    var city by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { FridayDialogWindow(); Text("Weather") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(city, { city = it.take(100) }, placeholder = { Text("City, region") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (city.length >= 2) vm.findWeatherCity(city) }),
                    trailingIcon = { IconButton(onClick = { vm.findWeatherCity(city) }, enabled = enabled && city.length >= 2) { Icon(LineIcons.Search, "Find city", Modifier.size(20.dp)) } })
                if (state.workspaceBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                state.weatherLocations.forEach { location ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(location) }.heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(LineIcons.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp)); Text(location.optString("label"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { onPick(null) }) { Text("Turn off weather") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun TimeZoneDialog(value: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    val valid = runCatching { ZoneId.of(text.trim()) }.isSuccess
    AlertDialog(onDismissRequest = onDismiss, title = { FridayDialogWindow(); Text("Time zone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceRow("Same as phone", ZoneId.systemDefault().id.replace('_', ' '), value.isBlank() || value == ZoneId.systemDefault().id) { onPick(ZoneId.systemDefault().id) }
                OutlinedTextField(text, { text = it.take(100) }, label = { Text("Other, like America/Chicago") }, singleLine = true, isError = text.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { onPick(text.trim()) }, enabled = valid) { Text("Use this") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("Cancel") } })
}
