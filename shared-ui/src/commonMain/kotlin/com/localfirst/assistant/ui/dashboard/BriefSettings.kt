@file:OptIn(ExperimentalMaterial3Api::class)
package com.localfirst.assistant.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.client.*
import com.localfirst.assistant.platform.ZoneTime
import com.localfirst.assistant.ui.FridayDialogWindow
import com.localfirst.assistant.ui.LineIcons
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import kotlinx.serialization.json.*

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.number(key: String, fallback: Int) = (this[key] as? JsonPrimitive)?.intOrNull ?: fallback

/** How often FRIDAY may offer a check-in, in hours, as the brief stores it. */
private val CADENCES = listOf(72 to "Every few days", 168 to "About once a week", 336 to "Every two weeks", 720 to "About once a month")

/** This device's own calendar, offered as a dashboard source where the platform has one. */
class DeviceCalendarChoice(val label: String, val detail: String, val selected: Boolean, val choose: () -> Unit)

/**
 * Dashboard and daily brief settings: where today comes from, what's included, and when FRIDAY may
 * reach out. Every change saves as it's made. [device] names this client ("phone", "browser").
 */
@Composable
fun BriefSettingsScreen(actions: DashboardActions, scope: String, incognito: Boolean, busy: Boolean, device: String,
                        onAccounts: () -> Unit, deviceCalendar: DeviceCalendarChoice? = null, notificationNote: String) {
    val state by actions.brief.collectAsState()
    val preferences = state.preferences
    var form by remember(preferences?.text("revision")) { mutableStateOf(preferences?.let { JsonObject(it - "revision" - "calendar_account") }) }
    val enabled = !state.busy && !busy && !incognito
    var dialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scope) { actions.loadBrief(scope) }
    fun change(vararg pairs: Pair<String, JsonElement>) {
        val next = form?.let { JsonObject(it + pairs) } ?: return
        form = next
        actions.saveBriefPreferences(next, scope)
    }
    val zone = BriefTimes.zone(form?.text("timezone"))
    LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Dashboard and daily brief", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.headlineSmall)
            Text("What shows on your dashboard, and when FRIDAY may check in. Settings are shared with your other clients.", Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { Text(it, Modifier.padding(4.dp), color = MaterialTheme.colorScheme.error) }
        }
        if (incognito) { item { DashCard { DashMessage("Leave incognito to change these settings.") } }; return@LazyColumn }
        val current = form
        if (current == null) { item { DashCard { if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)); DashMessage(if (state.busy) "Loading…" else "Connect to your computer to change these settings.") } }; return@LazyColumn }

        item { BriefLabel("Sources") }
        item {
            DashCard {
                val account = state.accounts.firstOrNull { it.text("id") == current.text("calendar_account_id") }
                SettingRow(LineIcons.Calendar, "Calendar", account?.text("label") ?: deviceCalendar?.takeIf { it.selected }?.label ?: "Not connected", enabled) { dialog = "calendar" }
                SettingDivider()
                SettingRow(LineIcons.Weather, "Weather", (current["weather"] as? JsonObject)?.text("label") ?: "Off", enabled) { dialog = "weather" }
                if (current["weather"] is JsonObject) {
                    SettingDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 52.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Units", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        SingleChoiceSegmentedButtonRow {
                            listOf("fahrenheit" to "°F", "celsius" to "°C").forEachIndexed { i, (value, label) ->
                                SegmentedButton(current.text("temperature_unit").ifBlank { "celsius" } == value, { change("temperature_unit" to JsonPrimitive(value)) }, SegmentedButtonDefaults.itemShape(i, 2), enabled = enabled, icon = {}) { Text(label) }
                            }
                        }
                    }
                }
                SettingDivider()
                val deviceZone = ZoneTime.system()
                val zoneText = current.text("timezone").ifBlank { deviceZone }
                SettingRow(LineIcons.Globe, "Time zone", if (zoneText == deviceZone) "${zoneText.replace('_', ' ')} · same as this $device" else zoneText.replace('_', ' '), enabled) { dialog = "timezone" }
            }
        }

        item { BriefLabel("Show") }
        item {
            DashCard {
                SwitchRow(LineIcons.Checklist, "Follow-ups", "Things you asked to be reminded about", current.flag("include_followups"), enabled) { change("include_followups" to JsonPrimitive(it)) }
                SettingDivider()
                SwitchRow(LineIcons.Chat, "Open topics", "Conversations FRIDAY thinks you may want to pick back up", current.flag("include_situations"), enabled) { change("include_situations" to JsonPrimitive(it)) }
            }
        }

        item { BriefLabel("Notifications") }
        item {
            DashCard {
                val morning = BriefTimes.parseHhmm(current.text("morning_time")) ?: (7 to 30)
                SwitchRow(LineIcons.Bell, "Morning brief", "A nudge to look at your day", current.flag("morning_enabled"), enabled) { change("morning_enabled" to JsonPrimitive(it)) }
                if (current.flag("morning_enabled")) ValueRow("Around", BriefTimes.clock(morning.first, morning.second), enabled) { dialog = "morning" }
                SettingDivider()
                SwitchRow(LineIcons.Friday, "Check-ins", "FRIDAY offers to revisit a follow-up or open topic", current.flag("proactive_enabled"), enabled) { change("proactive_enabled" to JsonPrimitive(it)) }
                if (current.flag("proactive_enabled")) {
                    var open by remember { mutableStateOf(false) }
                    val hours = current.number("cadence_hours", 72)
                    Box {
                        ValueRow("How often", CADENCES.firstOrNull { it.first == hours }?.second ?: "Every $hours hours", enabled) { open = true }
                        DropdownMenu(open, { open = false }) {
                            CADENCES.forEach { (h, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; change("cadence_hours" to JsonPrimitive(h)) }, trailingIcon = { if (h == hours) Text("✓", color = LocalFridayPalette.current.accent) }) }
                        }
                    }
                }
                SettingDivider()
                val start = BriefTimes.parseHhmm(current.text("quiet_start")) ?: (22 to 0)
                val end = BriefTimes.parseHhmm(current.text("quiet_end")) ?: (7 to 0)
                SettingRow(LineIcons.Moon, "Quiet hours", if (start == end) "No notifications at all" else "No notifications between", enabled, chevron = false) {}
                ValueRow("From", BriefTimes.clock(start.first, start.second), enabled) { dialog = "quiet_start" }
                ValueRow("Until", BriefTimes.clock(end.first, end.second), enabled) { dialog = "quiet_end" }
                Spacer(Modifier.height(6.dp))
            }
        }

        item { BriefLabel("Follow-ups") }
        item {
            DashCard {
                if (state.followups.isEmpty()) Text("Nothing waiting on you.", Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.followups.forEach { item -> key(item.text("id")) { FollowupRow(item, zone, enabled, actions, scope) } }
                AddFollowup(zone, enabled) { title, due -> actions.addFollowup(title, due, scope) }
            }
        }
        item {
            Text("Weather looks up only the city you choose. Notifications never include your calendar or notes. $notificationNote",
                Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val current = form ?: return
    fun time(key: String, fallback: Pair<Int, Int>) = BriefTimes.parseHhmm(current.text(key)) ?: fallback
    when (dialog) {
        "morning" -> time("morning_time", 7 to 30).let { (h, m) -> ClockDialog("Morning brief", h, m, { dialog = null }) { hour, minute -> dialog = null; change("morning_time" to JsonPrimitive(BriefTimes.hhmm(hour, minute))) } }
        "quiet_start" -> time("quiet_start", 22 to 0).let { (h, m) -> ClockDialog("Quiet from", h, m, { dialog = null }) { hour, minute -> dialog = null; change("quiet_start" to JsonPrimitive(BriefTimes.hhmm(hour, minute))) } }
        "quiet_end" -> time("quiet_end", 7 to 0).let { (h, m) -> ClockDialog("Quiet until", h, m, { dialog = null }) { hour, minute -> dialog = null; change("quiet_end" to JsonPrimitive(BriefTimes.hhmm(hour, minute))) } }
        "calendar" -> ChoiceDialog("Calendar", { dialog = null }) {
            val selected = current.text("calendar_account_id")
            state.accounts.filter { it.text("provider") in listOf("google", "microsoft") }.forEach { account ->
                ChoiceRow(account.text("label"), account.text("provider").replaceFirstChar(Char::uppercase), selected == account.text("id")) { dialog = null; change("calendar_account_id" to JsonPrimitive(account.text("id"))) }
            }
            if (deviceCalendar != null) ChoiceRow(deviceCalendar.label, deviceCalendar.detail, selected.isBlank()) {
                dialog = null
                if (selected.isNotBlank()) change("calendar_account_id" to JsonNull)
                deviceCalendar.choose()
            } else if (selected.isNotBlank()) ChoiceRow("No calendar", "Show the dashboard without one", false) { dialog = null; change("calendar_account_id" to JsonNull) }
            ChoiceRow("Connect an account…", "Google or Microsoft, through your computer", false) { dialog = null; onAccounts() }
        }
        "weather" -> WeatherDialog(state, actions, enabled, { dialog = null }) { location -> dialog = null; change("weather" to (location ?: JsonNull)) }
        "timezone" -> TimeZoneDialog(current.text("timezone"), device, { dialog = null }) { dialog = null; change("timezone" to JsonPrimitive(it)) }
    }
}

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
private fun WeatherDialog(state: BriefState, actions: DashboardActions, enabled: Boolean, onDismiss: () -> Unit, onPick: (JsonObject?) -> Unit) {
    var city by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { FridayDialogWindow(); Text("Weather") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(city, { city = it.take(100) }, placeholder = { Text("City, region") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (city.length >= 2) actions.findWeatherCity(city) }),
                    trailingIcon = { IconButton(onClick = { actions.findWeatherCity(city) }, enabled = enabled && city.length >= 2) { Icon(LineIcons.Search, "Find city", Modifier.size(20.dp)) } })
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                state.weatherLocations.forEach { location ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(location) }.heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(LineIcons.Place, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp)); Text(location.text("label"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { onPick(null) }) { Text("Turn off weather") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun TimeZoneDialog(value: String, device: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    val valid = ZoneTime.valid(text.trim())
    val system = ZoneTime.system()
    AlertDialog(onDismissRequest = onDismiss, title = { FridayDialogWindow(); Text("Time zone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceRow("Same as this $device", system.replace('_', ' '), value.isBlank() || value == system) { onPick(system) }
                OutlinedTextField(text, { text = it.take(100) }, label = { Text("Other, like America/Chicago") }, singleLine = true, isError = text.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { onPick(text.trim()) }, enabled = valid) { Text("Use this") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("Cancel") } })
}
