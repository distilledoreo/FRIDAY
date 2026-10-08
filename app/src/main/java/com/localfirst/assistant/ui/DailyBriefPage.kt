package com.localfirst.assistant.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun DailyBriefPage(state: ChatUiState, vm: ChatViewModel) {
    val preferences = state.briefPreferences
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var draft by remember(preferences?.optString("revision")) { mutableStateOf(preferences?.let { JSONObject(it.toString()).apply { remove("revision"); remove("calendar_account") } }) }
    var city by remember { mutableStateOf("") }
    var followup by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<String?>(null) }
    val enabled = !state.workspaceBusy && !state.busy && !state.privacy.incognito
    fun change(key: String, value: Any) { draft = draft?.let { JSONObject(it.toString()).put(key, value) } }
    LaunchedEffect(Unit) { vm.refreshBrief() }
    LaunchedEffect(state.workspaceStatus) {
        if (state.workspaceStatus == "Follow-up saved. No task or outgoing action was started.") { followup = ""; due = null }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Your daily brief", style = MaterialTheme.typography.titleLarge)
            Text("Build a source snapshot for today. Selected calendar and weather sources are read only when you tap Build or confirm a chat read. Notices invite you to open the brief without fetching those sources.")
            Text("Situations and follow-ups include global context and the current project. Nothing runs or sends automatically.", style = MaterialTheme.typography.bodySmall)
            Row { Button(shape = MaterialTheme.shapes.small, onClick = vm::buildDailyBrief, enabled = enabled && preferences != null) { Text("Build today's brief") }; TextButton(shape = MaterialTheme.shapes.small, onClick = vm::refreshBrief, enabled = enabled) { Text("Refresh") } }
        }
        state.dailyBrief?.let { brief ->
            item { Text("${brief.optString("date")} · ${brief.optString("timezone")}", style = MaterialTheme.typography.titleMedium); Text("Snapshot checked at ${briefTime(brief.optDouble("checked_at"), brief.optString("timezone"))}. Sources may change afterward.", style = MaterialTheme.typography.bodySmall) }
            val sections = brief.optJSONArray("sections")
            if (sections != null) for (i in 0 until sections.length()) {
                val section = sections.getJSONObject(i)
                item(key = "brief-${section.optString("kind")}") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(section.optString("kind").replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleSmall)
                        if (section.optString("status") != "available") Text(section.optString("detail").ifBlank { "Disabled in source settings." })
                        else when (section.optString("kind")) {
                            "calendar" -> {
                                Text(section.optJSONObject("account")?.optString("label").orEmpty(), style = MaterialTheme.typography.labelMedium)
                                val events = section.optJSONArray("events")
                                if (events == null || events.length() == 0) Text("No events returned in today's primary-calendar window.")
                                else for (n in 0 until events.length()) {
                                    val event = events.getJSONObject(n)
                                    Text(event.optString("title").ifBlank { "Untitled event" })
                                    if (event.optBoolean("truncated")) Text("Event preview shortened; open the provider for complete details.", style = MaterialTheme.typography.bodySmall)
                                    Text("${calendarTime(event.optJSONObject("start"), brief.optString("timezone"))} → ${calendarTime(event.optJSONObject("end"), brief.optString("timezone"))}", style = MaterialTheme.typography.bodySmall)
                                    event.optString("location").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                                if (section.optBoolean("possibly_truncated")) Text("Calendar preview limit reached; more events may exist.")
                            }
                            "weather" -> {
                                Text("${section.optString("location")}: ${section.optString("conditions")}")
                                Text("Low ${section.optDouble("temperature_2m_min")} / high ${section.optDouble("temperature_2m_max")} ${section.optString("temperature_unit")} · precipitation chance ${section.optDouble("precipitation_probability_max").toInt()}%")
                                Text("Forecast for today; conditions are not guaranteed. ${section.optString("attribution")}", style = MaterialTheme.typography.bodySmall)
                                TextButton(shape = MaterialTheme.shapes.small, onClick = { uriHandler.openUri(section.getString("source")) }) { Text("Open forecast source") }
                            }
                            else -> {
                                val rows = section.optJSONArray("items")
                                if (rows == null || rows.length() == 0) Text("No items returned.")
                                else for (n in 0 until rows.length()) {
                                    val row = rows.getJSONObject(n)
                                    Text(row.optString(if (section.optString("kind") == "situations") "summary" else "title"))
                                    if (row.optBoolean("tentative")) { Text("Tentative · ${row.optString("source_title")}", style = MaterialTheme.typography.labelSmall); Text("User evidence: “${row.optString("quote")}”", style = MaterialTheme.typography.bodySmall) }
                                    if (row.has("due") && !row.isNull("due")) Text("Due ${briefTime(row.optDouble("due"), brief.optString("timezone"))}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (section.optBoolean("possibly_truncated")) Text("Source preview limit reached; more items may exist.", style = MaterialTheme.typography.bodySmall)
                                Text(section.optString("detail"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item { OutlinedButton(shape = MaterialTheme.shapes.small, onClick = vm::discussDailyBrief, enabled = enabled) { Text("Discuss in chat") }; Text("Prepares a draft. Sending it requests a confirmed read before sharing private sources with the selected model; chat may save the result.", style = MaterialTheme.typography.bodySmall) }
        }
        item { HorizontalDivider(); Text("Follow-ups", style = MaterialTheme.typography.titleMedium) }
        items(state.briefFollowups, key = { it.getString("id") }) { row ->
            Column {
                Text(row.getString("title"))
                if (!row.isNull("due")) Text("Due ${briefTime(row.optDouble("due"), preferences?.optString("timezone") ?: "UTC")}", style = MaterialTheme.typography.bodySmall)
                Row { TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.changeFollowup(row.getString("id")) }, enabled = enabled) { Text("Done") }; TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.changeFollowup(row.getString("id"), true) }, enabled = enabled) { Text("Delete") } }
            }
        }
        item {
            OutlinedTextField(followup, { followup = it.take(800) }, label = { Text("New explicit follow-up") }, modifier = Modifier.fillMaxWidth())
            Text(due?.let { "Due $it" } ?: "No due time: shown in brief only.", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(shape = MaterialTheme.shapes.small, onClick = {
                    val zone = runCatching { ZoneId.of(preferences?.optString("timezone") ?: "UTC") }.getOrDefault(ZoneOffset.UTC)
                    val now = ZonedDateTime.now(zone)
                    DatePickerDialog(context, { _, year, month, day ->
                        TimePickerDialog(context, { _, hour, minute -> due = LocalDate.of(year, month + 1, day).atTime(hour, minute).atZone(zone).toOffsetDateTime().toString() }, now.hour, now.minute, true).show()
                    }, now.year, now.monthValue - 1, now.dayOfMonth).show()
                }, enabled = enabled) { Text("Choose due time") }
                TextButton(shape = MaterialTheme.shapes.small, onClick = { due = null }, enabled = enabled) { Text("Clear due") }
            }
            Button(shape = MaterialTheme.shapes.small, onClick = { vm.addFollowup(followup, due) }, enabled = enabled && followup.isNotBlank()) { Text("Save follow-up") }
        }
        item { HorizontalDivider(); Text("Check-in proposals", style = MaterialTheme.typography.titleMedium); TextButton(shape = MaterialTheme.shapes.small, onClick = vm::refreshCheckins, enabled = enabled) { Text("Check eligible proposals") } }
        if (state.briefProposals.isEmpty()) item { Text("No pending proposals. Quiet hours, source opt-outs and cadence are respected.") }
        items(state.briefProposals, key = { "proposal-${it.getString("id")}" }) { proposal ->
            Column {
                val source = proposal.getJSONObject("source")
                Text(if (proposal.optString("kind") == "situation") "Would you like to revisit ${source.optString("topic")}?" else source.optString("title"))
                Text(source.optString("summary", source.optString("detail")), style = MaterialTheme.typography.bodySmall)
                if (proposal.optString("kind") == "morning") TextButton(shape = MaterialTheme.shapes.small, onClick = vm::buildDailyBrief, enabled = enabled) { Text("Build brief") }
                TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.dismissCheckin(proposal.getString("id")) }, enabled = enabled) { Text("Dismiss") }
            }
        }
        draft?.let { form ->
            item { HorizontalDivider(); Text("Source and notice settings", style = MaterialTheme.typography.titleMedium); Text("Changes take effect only when saved. No GPS or inferred location. Weather searches send the city name to Open-Meteo/GeoNames; forecasts send selected coordinates. The free service is for personal noncommercial use.", style = MaterialTheme.typography.bodySmall) }
            item {
                OutlinedTextField(form.optString("timezone"), { change("timezone", it.take(100)) }, label = { Text("Display timezone (for example America/New_York)") }, modifier = Modifier.fillMaxWidth())
                TextButton(shape = MaterialTheme.shapes.small, onClick = { change("timezone", ZoneId.systemDefault().id) }, enabled = enabled) { Text("Use phone timezone") }
                Text("Primary calendar: ${state.connectedAccounts.firstOrNull { it.optString("id") == form.optString("calendar_account_id") }?.optString("label") ?: "None"}")
                TextButton(shape = MaterialTheme.shapes.small, onClick = { change("calendar_account_id", JSONObject.NULL) }, enabled = enabled) { Text("No calendar") }
            }
            items(state.connectedAccounts.filter { it.optString("provider") in listOf("google", "microsoft") }, key = { "brief-account-${it.getString("id")}" }) { account ->
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { change("calendar_account_id", account.getString("id")) }, enabled = enabled) { Text("Use ${account.optString("label")}") }
            }
            item {
                Text("Weather: ${form.optJSONObject("weather")?.optString("label") ?: "None"}")
                OutlinedTextField(city, { city = it.take(100) }, label = { Text("Weather city and region") }, modifier = Modifier.fillMaxWidth())
                Row { TextButton(shape = MaterialTheme.shapes.small, onClick = { vm.findWeatherCity(city) }, enabled = enabled && city.length >= 2) { Text("Find city") }; TextButton(shape = MaterialTheme.shapes.small, onClick = { change("weather", JSONObject.NULL) }, enabled = enabled) { Text("No weather") } }
            }
            items(state.weatherLocations, key = { "city-${it.optDouble("latitude")}-${it.optDouble("longitude")}" }) { location -> OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { change("weather", location) }, enabled = enabled) { Text(location.getString("label")) } }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Fahrenheit", Modifier.weight(1f)); Switch(form.optString("temperature_unit") == "fahrenheit", { change("temperature_unit", if (it) "fahrenheit" else "celsius") }, enabled = enabled) } }
            items(listOf("include_situations" to "Include tentative situations", "include_followups" to "Include explicit follow-ups", "morning_enabled" to "Morning invitation", "proactive_enabled" to "Occasional check-in proposals")) { (key, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(form.optBoolean(key), { change(key, it) }, enabled = enabled) }
            }
            item {
                Text("Background notices are off by default, contain no source text, and use Android's roughly 15-minute polling; timing is not exact. Morning invitation is offered only within two hours of its time, with no catch-up burst. Check-ins respect Memory's master opt-out and stay proposals. Dismissed follow-up/situation IDs stay dismissed.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(form.optString("morning_time"), { change("morning_time", it.take(5)) }, label = { Text("Morning time (HH:MM)") })
                OutlinedTextField(form.optString("quiet_start"), { change("quiet_start", it.take(5)) }, label = { Text("Quiet hours start (HH:MM)") })
                OutlinedTextField(form.optString("quiet_end"), { change("quiet_end", it.take(5)) }, label = { Text("Quiet hours end (HH:MM)") })
                Text("Equal quiet start/end means quiet all day. Quiet hours and a 72-hour minimum cooldown also apply to inline chat check-in offers.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(form.optInt("cadence_hours", 72).toString(), { value -> value.toIntOrNull()?.let { change("cadence_hours", it) } }, label = { Text("Check-in cadence in hours (72–720)") })
                Button(shape = MaterialTheme.shapes.small, onClick = { vm.saveBriefPreferences(JSONObject(form.toString())) }, enabled = enabled) { Text("Save source and notice settings") }
            }
        }
    }
}

private fun briefTime(seconds: Double, zone: String): String = runCatching { Instant.ofEpochSecond(seconds.toLong()).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("EEE, MMM d 'at' HH:mm z")) }.getOrDefault("Time unavailable")
private fun calendarTime(value: JSONObject?, zone: String): String {
    if (value == null) return "Time unavailable"
    value.optString("date").takeIf(String::isNotBlank)?.let { return "$it (all day; end date is exclusive)" }
    val text = value.optString("dateTime")
    return runCatching { OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("MMM d HH:mm z")) }.getOrElse { "$text ${value.optString("timeZone")}" }
}
