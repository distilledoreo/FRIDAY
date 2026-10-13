@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.localfirst.assistant.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.client.*
import com.localfirst.assistant.platform.PlatformSystem
import com.localfirst.assistant.platform.ZoneTime
import com.localfirst.assistant.settings.ProactivityLevel
import com.localfirst.assistant.ui.FridayDialogWindow
import com.localfirst.assistant.ui.LineIcons
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import kotlinx.serialization.json.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull ?: Double.NaN
private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.rows(key: String) = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** Eligibility comes from the host's opt-ins/evidence/quiet controls; this only changes selection. */
fun dashboardProposalKinds(level: ProactivityLevel, kind: String) = kind in setOf("followup", "situation") && (level != ProactivityLevel.CONSERVATIVE || kind == "followup")

fun agentStatus(status: String): String = when (status) {
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

/** An event from a calendar the device itself provides, such as the phone's. */
class DeviceEvent(val time: String, val title: String, val detail: String?, val location: String?, val open: (() -> Unit)?)

/** Today from this device's own calendar, when no computer calendar is connected. Null events mean it hasn't been read. */
class DeviceCalendar(val events: List<DeviceEvent>?, val needsPermission: Boolean, val allow: () -> Unit)

/** Where the dashboard leads: brief settings, accounts, app settings, a chat about something, FRIDAY. */
class DashboardNavigation(
    val settings: () -> Unit, val accounts: () -> Unit, val appSettings: () -> Unit,
    val talk: (String) -> Unit, val planDay: () -> Unit, val openFriday: (String?) -> Unit, val askFriday: (String) -> Unit,
)

/** Today at a glance: calendar and weather, follow-ups, topics worth picking up, and FRIDAY's work. */
@Composable
fun PersonalDashboard(actions: DashboardActions, scope: String, incognito: Boolean, busy: Boolean, level: ProactivityLevel,
                      onClose: () -> Unit, go: DashboardNavigation, deviceCalendar: DeviceCalendar? = null) {
    val data by actions.dashboard.collectAsState()
    val brief by actions.brief.collectAsState()
    val uri = LocalUriHandler.current
    val accent = LocalFridayPalette.current.accent
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var allEvents by remember { mutableStateOf(false) }
    var openEvent by remember { mutableStateOf<String?>(null) }
    var weatherOpen by remember { mutableStateOf(false) }
    val enabled = !busy && !brief.busy
    fun talk(text: String) { onClose(); go.talk(text) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 720.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(BriefTimes.heading(PlatformSystem.currentTimeMillis(), BriefTimes.zone((data.snapshot)?.text("timezone"))), Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = muted)
            IconButton(onClick = { onClose(); go.settings() }) { Icon(LineIcons.Settings, "Dashboard settings", Modifier.size(20.dp), tint = muted) }
            IconButton(onClick = { actions.refreshDashboard(scope) }, enabled = !data.loading) { Icon(LineIcons.Refresh, "Refresh dashboard", Modifier.size(20.dp), tint = muted) }
            IconButton(onClick = onClose) { Icon(LineIcons.Close, "Close dashboard", Modifier.size(20.dp), tint = muted) }
        } }
        if (incognito) {
            item { DashCard { DashMessage("Your dashboard is unavailable in incognito.") } }
        } else if (data.scope != scope || data.loading) {
            item { DashCard { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)); DashMessage("Checking your sources…") } }
        } else if (data.error != null) {
            item { DashCard {
                DashMessage(data.error!!)
                DashRow(onClick = { actions.refreshDashboard(scope) }) { Text("Try again", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge) }
                DashRow(onClick = { onClose(); go.appSettings() }) { Text("Settings", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge) }
            } }
        } else if (data.loaded) {
            val snapshot = data.snapshot
            val zone = snapshot?.text("timezone")?.takeIf { it.isNotBlank() }
            val sections = snapshot?.rows("sections").orEmpty()
            val calendar = sections.firstOrNull { it.text("kind") == "calendar" }
            val weather = sections.firstOrNull { it.text("kind") == "weather" }?.takeIf { it.text("status") == "available" }
            val events = calendar?.rows("events").orEmpty()
            item { DashCard {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LineIcons.Calendar, null, Modifier.size(22.dp), tint = accent)
                    Spacer(Modifier.width(12.dp)); Text("Today", Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.titleMedium)
                    weather?.let { source ->
                        val high = source.number("temperature_2m_max")
                        Row(Modifier.clip(MaterialTheme.shapes.small).clickable { weatherOpen = !weatherOpen }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            WeatherGlyph(source.text("conditions")); Spacer(Modifier.width(8.dp))
                            Text(if (high.isFinite()) "${high.toInt()}°" else source.text("conditions"), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (weatherOpen) weather?.let { source -> WeatherDetail(source) { runCatching { uri.openUri(BriefTimes.forecastPage(source)) } } }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                val device = deviceCalendar?.events
                when {
                    calendar?.text("status") != "available" && device != null -> {
                        if (device.isEmpty()) DashMessage("Nothing on your calendar today.")
                        device.take(if (allEvents) 30 else 4).forEachIndexed { index, event ->
                            val key = "device-$index"
                            EventRow(event.time, event.title, event.detail, eventColor(index, accent), openEvent == key, { openEvent = if (openEvent == key) null else key }) {
                                EventActions(event.location, event.open) { talk("Help me prepare for “${event.title}” today${event.location?.takeIf(String::isNotBlank)?.let { " at $it" }.orEmpty()}.") }
                            }
                        }
                        if (device.size > 4) ShowAll(allEvents, device.size) { allEvents = !allEvents }
                    }
                    calendar?.text("status") != "available" && deviceCalendar?.needsPermission == true ->
                        DashRow(onClick = deviceCalendar.allow) { DashText("Show this device’s calendar", "Allow calendar access") }
                    calendar?.text("status") == "unavailable" -> DashRow(onClick = { onClose(); go.accounts() }) { DashText("Your calendar couldn’t be checked", "Open accounts") }
                    calendar?.text("status") != "available" -> DashRow(onClick = { onClose(); go.settings() }) { DashText("Connect a calendar to see your day", "Choose sources") }
                    events.isEmpty() -> DashMessage("Nothing on your calendar today.")
                    else -> {
                        events.take(if (allEvents) 30 else 4).forEachIndexed { index, event ->
                            val key = "pc-$index"
                            val title = event.text("title").ifBlank { event.text("summary").ifBlank { "Untitled event" } }
                            val link = event.text("htmlLink").takeIf { it.startsWith("https://") }
                            EventRow(BriefTimes.eventTime(event, zone), title, BriefTimes.eventDetail(event), eventColor(index, accent), openEvent == key, { openEvent = if (openEvent == key) null else key }) {
                                EventActions(event.text("location").takeIf(String::isNotBlank), link?.let { l -> { uri.openUri(l) } }) { talk("Help me prepare for “$title” today.") }
                            }
                        }
                        if (events.size > 4) ShowAll(allEvents, events.size) { allEvents = !allEvents }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { onClose(); go.planDay() }.heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SparkleGlyph(); Spacer(Modifier.width(12.dp))
                    Text("Plan my day with FRIDAY", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Chevron()
                }
            } }
            val tz = BriefTimes.zone(zone)
            fun shown(kind: String) = sections.firstOrNull { it.text("kind") == kind }?.text("status") != "disabled"
            if (shown("followups")) item { DashCard {
                Row(Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LineIcons.Checklist, null, Modifier.size(22.dp), tint = accent); Spacer(Modifier.width(12.dp))
                    Text("Follow-ups", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (data.followups.isNotEmpty()) Text("${data.followups.size}", style = MaterialTheme.typography.labelLarge, color = muted)
                }
                if (data.followups.isEmpty()) Text("Nothing waiting on you.", Modifier.padding(start = 50.dp, top = 2.dp, bottom = 4.dp), style = MaterialTheme.typography.bodyMedium, color = muted)
                data.followups.take(6).forEach { item -> key(item.text("id")) { FollowupRow(item, tz, enabled, actions, scope, ::talk) } }
                if (data.followups.size > 6) DashRow(onClick = { onClose(); go.settings() }) { Text("See all ${data.followups.size}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = muted) }
                AddFollowup(tz, enabled) { title, due -> actions.addFollowup(title, due, scope) }
                Spacer(Modifier.height(4.dp))
            } }
            val suggestions = if (!shown("situations")) emptyList() else data.proposals.filter { dashboardProposalKinds(level, it.text("kind")) && it.text("kind") != "followup" }.take(level.suggestionLimit)
            if (suggestions.isNotEmpty()) item { DashCard {
                Row(Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SparkleGlyph(); Spacer(Modifier.width(12.dp)); Text("Worth a look", style = MaterialTheme.typography.titleMedium)
                }
                suggestions.forEach { proposal ->
                    val source = proposal["source"] as? JsonObject ?: proposal
                    val topic = source.text("topic").ifBlank { source.text("title").ifBlank { source.text("summary") } }
                    Column(Modifier.fillMaxWidth().padding(start = 50.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
                        Text(topic.ifBlank { "Something from your conversations" }.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        source.text("summary").ifBlank { source.text("detail") }.takeIf { it.isNotBlank() && it != topic }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DashPill("Pick it up", primary = true, enabled = !busy) { talk("Let’s pick up where we left off on $topic.") }
                            DashPill("Not now", enabled = enabled) { actions.dismissProposal(proposal.text("id"), scope) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            } }
            val attention = data.agentTasks.filter { it.text("status") in FRIDAY_NEEDS_YOU }
            val working = data.agentTasks.filter { it.text("status") in FRIDAY_WORKING }
            item { DashCard {
                Row(Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LineIcons.Friday, null, Modifier.size(20.dp), tint = accent); Spacer(Modifier.width(14.dp))
                    Text("FRIDAY", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (attention.isNotEmpty() || working.isNotEmpty()) TextButton(onClick = { onClose(); go.openFriday(null) }, shape = MaterialTheme.shapes.small, colors = ButtonDefaults.textButtonColors(contentColor = muted)) { Text("All tasks") }
                }
                attention.take(3).forEach { task ->
                    Row(Modifier.fillMaxWidth().clickable { onClose(); go.openFriday(task.text("id")) }.padding(start = 50.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DashText((task["proposal"] as? JsonObject)?.text("prompt").orEmpty().ifBlank { "A task" }, agentStatus(task.text("status")))
                        DashPill("Review", primary = true) { onClose(); go.openFriday(task.text("id")) }
                    }
                }
                working.take(2).forEach { task ->
                    Row(Modifier.fillMaxWidth().clickable { onClose(); go.openFriday(task.text("id")) }.padding(start = 50.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DashText((task["proposal"] as? JsonObject)?.text("prompt").orEmpty().ifBlank { "A task" }, agentStatus(task.text("status")))
                        Chevron()
                    }
                }
                AskFriday(enabled = !busy) { request -> onClose(); go.askFriday(request) }
            } }
        }
    }
}

@Composable
private fun EventRow(time: String, title: String, detail: String?, dot: Color, open: Boolean, onToggle: () -> Unit, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(time, Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.padding(end = 14.dp).size(8.dp).clip(CircleShape).background(dot))
            DashText(title, detail)
        }
        if (open) actions()
    }
}

/** What you can do with an event: open it, get there, or get ready for it. */
@Composable
private fun EventActions(location: String?, onOpen: (() -> Unit)?, onPrep: () -> Unit) {
    val uri = LocalUriHandler.current
    FlowRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        onOpen?.let { DashPill("Open", icon = LineIcons.Calendar, onClick = it) }
        location?.takeIf(String::isNotBlank)?.let { place -> DashPill("Directions", icon = LineIcons.Place) { runCatching { uri.openUri(com.localfirst.assistant.ui.genui.mapUri(null, null, place)) } } }
        DashPill("Prep with FRIDAY", icon = LineIcons.Chat, onClick = onPrep)
    }
}

@Composable
private fun WeatherDetail(source: JsonObject, onSource: () -> Unit) {
    val low = source.number("temperature_2m_min"); val high = source.number("temperature_2m_max")
    val rain = source.number("precipitation_probability_max")
    val line = listOfNotNull(source.text("conditions").takeIf(String::isNotBlank)?.replaceFirstChar(Char::uppercase),
        if (high.isFinite() && low.isFinite()) "High ${high.toInt()}° · Low ${low.toInt()}°" else null,
        if (rain.isFinite()) "${rain.toInt()}% chance of rain" else null).joinToString(" · ")
    Row(Modifier.fillMaxWidth().clickable(onClick = onSource).padding(start = 50.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(source.text("location"), style = MaterialTheme.typography.bodyMedium)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Chevron()
    }
}

@Composable
private fun ShowAll(all: Boolean, count: Int, onClick: () -> Unit) =
    Text(if (all) "Show fewer" else "Show all $count events", Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 108.dp, top = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A small rounded action: the accent one is the likely next step, the others stay quiet. */
@Composable
fun DashPill(label: String, primary: Boolean = false, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalFridayPalette.current
    val fill = if (primary) palette.accent.copy(alpha = if (palette.dark) .18f else .14f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50), color = fill, contentColor = if (primary) palette.accent else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** "Ask FRIDAY to take something on": hands a request straight to her. */
@Composable
private fun AskFriday(enabled: Boolean, onAsk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun ask() { if (text.isNotBlank()) { onAsk(text); text = "" } }
    OutlinedTextField(text, { text = it.take(4000) }, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp), enabled = enabled,
        placeholder = { Text("Ask FRIDAY to take something on…") }, singleLine = true, shape = RoundedCornerShape(24.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { ask() }),
        trailingIcon = { IconButton(onClick = ::ask, enabled = enabled && text.isNotBlank()) { Icon(LineIcons.Send, "Ask FRIDAY", Modifier.size(20.dp)) } })
}

@Composable
fun DashCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val color = if (LocalFridayPalette.current.dark) Color(0xFF232326) else Color.White
    if (onClick != null) Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = color) { Column(content = content) }
    else Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = color) { Column(content = content) }
}

@Composable
fun DashRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        content(); Chevron()
    }
}

@Composable
fun RowScope.DashText(title: String, detail: String?, modifier: Modifier = Modifier.weight(1f), compact: Boolean = false) {
    Column(modifier) {
        Text(title, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        detail?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable fun DashMessage(text: String) = Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable fun DashHeader(text: String) = Text(text, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall)
@Composable fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))

/** Distinct, calm dot colors for consecutive events. */
private fun eventColor(index: Int, accent: Color) = listOf(accent, Color(0xFF6F9BDE), Color(0xFF6DBE8B), Color(0xFFD9B26A), Color(0xFFA48BDB))[index % 5]

@Composable
private fun WeatherGlyph(conditions: String) {
    val sunny = conditions.contains("clear", true) || conditions.contains("sun", true)
    val cloud = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(22.dp)) {
        val c = center; val r = size.minDimension * .2f
        if (sunny) {
            val sun = Color(0xFFF2B35B)
            drawCircle(sun, r, c)
            for (i in 0 until 8) {
                val a = i * PI / 4; val d1 = r * 1.5f; val d2 = r * 2.2f
                drawLine(sun, Offset(c.x + (d1 * cos(a)).toFloat(), c.y + (d1 * sin(a)).toFloat()), Offset(c.x + (d2 * cos(a)).toFloat(), c.y + (d2 * sin(a)).toFloat()), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
            }
        } else {
            drawCircle(cloud, r * 1.05f, c + Offset(-r * .6f, r * .2f))
            drawCircle(cloud, r * 1.35f, c + Offset(r * .5f, -r * .1f))
            drawRect(cloud, c + Offset(-r * 1.6f, r * .2f), Size(r * 3.4f, r * 1.05f))
        }
    }
}

@Composable
private fun SparkleGlyph() {
    val accent = LocalFridayPalette.current.accent
    Canvas(Modifier.size(22.dp)) {
        fun star(cx: Float, cy: Float, r: Float) = Path().apply {
            moveTo(cx, cy - r); quadraticTo(cx, cy, cx + r, cy); quadraticTo(cx, cy, cx, cy + r); quadraticTo(cx, cy, cx - r, cy); quadraticTo(cx, cy, cx, cy - r); close()
        }
        drawPath(star(size.width * .42f, size.height * .55f, size.width * .36f), accent, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
        drawPath(star(size.width * .8f, size.height * .2f, size.width * .15f), accent)
    }
}

private fun JsonObject.dueSeconds(): Double? = (this["due"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

/** One follow-up you can act on in place: tick it off, push it back, talk it through or delete it. */
@Composable
fun FollowupRow(item: JsonObject, zone: String, enabled: Boolean, actions: DashboardActions, scope: String, onTalk: ((String) -> Unit)? = null) {
    val id = item.text("id")
    val title = item.text("title")
    var done by remember(id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val now = PlatformSystem.currentTimeMillis()
    val due = item.dueSeconds()
    val overdue = due != null && due * 1000 < now
    val accent = LocalFridayPalette.current.accent
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { menu = true }.heightIn(min = 56.dp).padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { done = true; actions.completeFollowup(id, scope) }, enabled = enabled && !done) {
            Box(Modifier.size(22.dp).clip(CircleShape).border(1.5.dp, if (done) accent else MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                if (done) Box(Modifier.size(12.dp).clip(CircleShape).border(6.dp, accent, CircleShape))
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            due?.let { Text(BriefTimes.dueLabel(it, zone, now), style = MaterialTheme.typography.bodySmall, color = if (overdue && !done) accent else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled) { Icon(LineIcons.More, "Options for $title", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                Text(if (due == null) "Remind me" else "Move to", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BriefTimes.presets(zone, now).forEach { (label, time) ->
                    DropdownMenuItem(text = { Text(label) }, trailingIcon = { Text(BriefTimes.presetLabel(time, zone, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { menu = false; actions.snoozeFollowup(id, ZoneTime.iso(time, zone), scope) })
                }
                DropdownMenuItem(text = { Text("Pick a time…") }, onClick = { menu = false; picking = true })
                HorizontalDivider()
                onTalk?.let { talk -> DropdownMenuItem(text = { Text("Talk it through") }, leadingIcon = { Icon(LineIcons.Chat, null, Modifier.size(20.dp)) }, onClick = { menu = false; talk("Help me with this follow-up: $title") }) }
                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; actions.deleteFollowup(id, scope) })
            }
        }
    }
    if (picking) WhenPicker(zone, onDismiss = { picking = false }) { picked -> picking = false; actions.snoozeFollowup(id, ZoneTime.iso(picked, zone), scope) }
}

/** An inline "add a follow-up" field with quick reminder times. [add] receives an offset-aware due time, or null. */
@Composable
fun AddFollowup(zone: String, enabled: Boolean, startOpen: Boolean = false, add: (String, String?) -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    var text by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<Long?>(null) }
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
        add(text, due?.let { ZoneTime.iso(it, zone) })
        text = ""; due = null; open = startOpen
    }
    val now = PlatformSystem.currentTimeMillis()
    val presets = BriefTimes.presets(zone, now)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it.take(800) }, placeholder = { Text("What should I remind you about?") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(), enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }),
            trailingIcon = { IconButton(onClick = ::save, enabled = enabled && text.isNotBlank()) { Icon(LineIcons.Send, "Save follow-up", Modifier.size(20.dp)) } })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(due == null, { due = null }, label = { Text("No reminder") }, shape = MaterialTheme.shapes.small)
            presets.take(3).forEach { (label, time) -> FilterChip(due == time, { due = time }, label = { Text(label) }, shape = MaterialTheme.shapes.small) }
            val custom = due?.takeIf { d -> presets.none { it.second == d } }
            FilterChip(custom != null, { picking = true }, label = { Text(custom?.let { BriefTimes.dueLabel(it / 1000.0, zone, now) } ?: "Pick…") }, shape = MaterialTheme.shapes.small)
        }
    }
    if (picking) WhenPicker(zone, onDismiss = { picking = false }) { due = it; picking = false }
}

/** A date, then a time: Material pickers in FRIDAY's dialog style. Returns epoch millis in [zone]. */
@Composable
fun WhenPicker(zone: String, onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    var date by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    val today = ZoneTime.wall(PlatformSystem.currentTimeMillis(), zone)
    val chosen = date
    if (chosen == null) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = today.epochDay * 86_400_000L)
        DatePickerDialog(onDismissRequest = onDismiss,
            confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = {
                val millis = picker.selectedDateMillis ?: (today.epochDay * 86_400_000L)
                // The picker reports dates as UTC midnight.
                val d = ZoneTime.wall(millis, "UTC"); date = Triple(d.year, d.month, d.day)
            }) { Text("Next") } },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("Cancel") } }) { FridayDialogWindow(); DatePicker(picker) }
    } else ClockDialog("Remind me at", 9, 0, onDismiss) { hour, minute -> onPicked(ZoneTime.epoch(chosen.first, chosen.second, chosen.third, hour, minute, zone)) }
}

/** A time of day. */
@Composable
fun ClockDialog(title: String, hour: Int, minute: Int, onDismiss: () -> Unit, onPicked: (Int, Int) -> Unit) {
    val picker = rememberTimePickerState(hour, minute, is24Hour = false)
    AlertDialog(onDismissRequest = onDismiss, title = { FridayDialogWindow(); Text(title) }, text = { TimePicker(picker) },
        confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { onPicked(picker.hour, picker.minute) }) { Text("Done") } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = onDismiss) { Text("Cancel") } })
}
