package com.localfirst.assistant.client

import com.localfirst.assistant.platform.PlatformNet
import com.localfirst.assistant.platform.WallTime
import com.localfirst.assistant.platform.ZoneTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** The personal dashboard: today's sources, follow-ups, open topics and FRIDAY's tasks, for one project scope. */
data class DashboardState(
    val scope: String = "", val loading: Boolean = false, val loaded: Boolean = false, val error: String? = null,
    val snapshot: JsonObject? = null, val followups: List<JsonObject> = emptyList(), val proposals: List<JsonObject> = emptyList(),
    val agentTasks: List<JsonObject> = emptyList(),
)

/** Dashboard and daily brief settings, with the follow-ups and accounts they refer to. */
data class BriefState(
    val preferences: JsonObject? = null, val followups: List<JsonObject> = emptyList(), val accounts: List<JsonObject> = emptyList(),
    val weatherLocations: List<JsonObject> = emptyList(), val busy: Boolean = false, val error: String? = null, val notice: String? = null,
)

/** What the shared dashboard and brief screens do; the browser uses [BriefingController], Android its native orchestration. */
interface DashboardActions {
    val dashboard: StateFlow<DashboardState>
    val brief: StateFlow<BriefState>
    fun refreshDashboard(scope: String): Job?
    fun clearDashboard()
    fun loadBrief(scope: String): Job?
    fun saveBriefPreferences(preferences: JsonObject, scope: String): Job?
    fun findWeatherCity(query: String): Job?
    /** [due] is an offset-aware ISO time, or null for no reminder. */
    fun addFollowup(title: String, due: String?, scope: String): Job?
    fun completeFollowup(id: String, scope: String): Job?
    fun deleteFollowup(id: String, scope: String): Job?
    fun snoozeFollowup(id: String, due: String, scope: String): Job?
    fun dismissProposal(id: String, scope: String): Job?
}

/** FRIDAY task states the dashboard groups by. */
val FRIDAY_WORKING = setOf("approved", "running")
val FRIDAY_NEEDS_YOU = setOf("proposed", "awaiting_setup", "interrupted")

/** Dashboard orchestration over the gateway's briefing, task and account routes. */
class BriefingController(private val gateway: WorkspaceGateway, private val scope: CoroutineScope) : DashboardActions {
    private val dashboardState = MutableStateFlow(DashboardState())
    private val briefState = MutableStateFlow(BriefState())
    override val dashboard = dashboardState.asStateFlow()
    override val brief = briefState.asStateFlow()
    private var loading: Job? = null
    private var generation = 0
    private fun scoped(path: String, project: String) = "$path?scope=${PlatformNet.encodeUrl(project)}"
    private suspend fun proposals(project: String) = (gateway.request(scoped("/workspace/agent/briefing/proposals", project)) as? JsonObject)?.get("items")?.rows().orEmpty()

    override fun refreshDashboard(scope: String): Job? {
        val current = dashboardState.value
        if (current.loading && current.scope == scope) return loading
        loading?.cancel()
        val identity = ++generation
        dashboardState.value = DashboardState(scope = scope, loading = true)
        return this.scope.launch {
            try {
                val snapshot = gateway.request("/workspace/agent/briefing/build", "POST", buildJsonObject { put("scope", scope) }).jsonObject
                val followups = gateway.request(scoped("/workspace/agent/briefing/followups", scope)).rows()
                val proposals = proposals(scope)
                val tasks = gateway.request("/workspace/agent/tasks?limit=100").rows()
                if (identity == generation) dashboardState.value = DashboardState(scope, loaded = true, snapshot = snapshot, followups = followups, proposals = proposals, agentTasks = tasks)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (identity == generation) dashboardState.value = DashboardState(scope, error = "Your sources could not be refreshed. Check the computer connection and selected account permissions.")
            }
        }.also { loading = it }
    }
    override fun clearDashboard() { generation++; loading?.cancel(); loading = null; dashboardState.value = DashboardState() }

    private fun act(notice: String? = null, block: suspend () -> Unit): Job? {
        if (briefState.value.busy) return null
        briefState.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try { block(); if (notice != null) briefState.update { it.copy(notice = notice) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { briefState.update { it.copy(error = e.message ?: "Computer unavailable.") } }
            finally { briefState.update { it.copy(busy = false) } }
        }
    }
    fun dismissNotice() { briefState.update { it.copy(notice = null, error = null) } }

    override fun loadBrief(scope: String) = act {
        val preferences = gateway.request("/workspace/agent/briefing/settings").jsonObject
        val followups = gateway.request(scoped("/workspace/agent/briefing/followups", scope)).rows()
        val accounts = try { (gateway.request("/workspace/agent/accounts") as? JsonObject)?.get("accounts")?.rows().orEmpty() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        briefState.update { it.copy(preferences = preferences, followups = followups, accounts = accounts) }
    }
    override fun saveBriefPreferences(preferences: JsonObject, scope: String) = act {
        gateway.request("/workspace/agent/briefing/settings", "PUT", preferences)
        briefState.update { it.copy(preferences = gateway.request("/workspace/agent/briefing/settings").jsonObject) }
    }
    override fun findWeatherCity(query: String) = act {
        briefState.update { it.copy(weatherLocations = emptyList()) }
        val result = gateway.request("/workspace/agent/briefing/locations", "POST", buildJsonObject { put("query", query) }).jsonObject
        briefState.update { it.copy(weatherLocations = result["locations"]?.rows().orEmpty()) }
    }
    private suspend fun syncFollowups(scope: String) {
        val followups = gateway.request(scoped("/workspace/agent/briefing/followups", scope)).rows()
        val proposals = try { proposals(scope) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        briefState.update { it.copy(followups = followups) }
        dashboardState.update { if (it.loaded && it.scope == scope) it.copy(followups = followups, proposals = proposals ?: it.proposals) else it }
    }
    override fun addFollowup(title: String, due: String?, scope: String) = act("Follow-up saved.") {
        require(title.isNotBlank() && title.length <= 800) { "Write a follow-up of up to 800 characters." }
        gateway.request("/workspace/agent/briefing/followups", "POST", buildJsonObject { put("title", title.trim()); put("scope", scope); put("due", due?.let(::JsonPrimitive) ?: JsonNull) })
        syncFollowups(scope)
    }
    override fun completeFollowup(id: String, scope: String) = act("Follow-up marked done.") {
        gateway.request("/workspace/agent/briefing/followups/$id", "PATCH", buildJsonObject { put("status", "done") }); syncFollowups(scope)
    }
    override fun deleteFollowup(id: String, scope: String) = act("Follow-up deleted.") {
        gateway.request("/workspace/agent/briefing/followups/$id", "DELETE"); syncFollowups(scope)
    }
    override fun snoozeFollowup(id: String, due: String, scope: String) = act("Follow-up moved.") {
        gateway.request("/workspace/agent/briefing/followups/$id", "PATCH", buildJsonObject { put("status", "open"); put("due", due) }); syncFollowups(scope)
    }
    override fun dismissProposal(id: String, scope: String) = act("Suggestion dismissed.") {
        gateway.request("/workspace/agent/briefing/proposals/$id/dismiss", "POST")
        dashboardState.update { it.copy(proposals = it.proposals.filterNot { row -> row.string("id") == id }) }
    }
    fun reset() { clearDashboard(); briefState.value = BriefState() }
}

/** Dates and times as the dashboard shows them, in the brief's zone (else this device's). */
object BriefTimes {
    private val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    fun zone(timezone: String?): String = timezone?.takeIf { it.isNotBlank() && ZoneTime.valid(it) } ?: ZoneTime.system()
    fun clock(hour: Int, minute: Int) = "${(hour + 11) % 12 + 1}:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
    fun clock(t: WallTime) = clock(t.hour, t.minute)
    /** "Tuesday, October 13". */
    fun heading(now: Long, zone: String) = ZoneTime.wall(now, zone).let { "${days[it.dayOfWeek - 1]}, ${months[it.month - 1]} ${it.day}" }
    /** "Tue, Oct 13", with the year when it isn't this year's. */
    private fun shortDate(t: WallTime, now: WallTime) = if (t.year == now.year) "${days[t.dayOfWeek - 1].take(3)}, ${months[t.month - 1].take(3)} ${t.day}" else "${months[t.month - 1].take(3)} ${t.day}, ${t.year}"

    /** "Today · 3:00 PM", "Tomorrow · 9:00 AM", "Mon, Oct 12 · 9:00 AM", or "Overdue · …". */
    fun dueLabel(seconds: Double, zone: String, now: Long): String {
        val millis = (seconds * 1000).toLong()
        val due = ZoneTime.wall(millis, zone); val today = ZoneTime.wall(now, zone)
        val day = when (due.epochDay - today.epochDay) { 0L -> "Today"; 1L -> "Tomorrow"; -1L -> "Yesterday"; else -> shortDate(due, today) }
        return (if (millis < now) "Overdue · " else "") + "$day · ${clock(due)}"
    }

    /** A preset's time: "3:00 PM" today, else "Mon 9:00 AM". */
    fun presetLabel(millis: Long, zone: String, now: Long): String {
        val t = ZoneTime.wall(millis, zone)
        return if (t.epochDay == ZoneTime.wall(now, zone).epochDay) clock(t) else "${days[t.dayOfWeek - 1].take(3)} ${clock(t)}"
    }

    /** Quick times for "remind me": later today, this evening, tomorrow morning, next week. */
    fun presets(zone: String, now: Long): List<Pair<String, Long>> {
        val today = ZoneTime.wall(now, zone)
        fun at(dayOffset: Long, hour: Int): Long {
            val millis = ZoneTime.epoch(today.year, today.month, today.day, 12, 0, zone) + dayOffset * 86_400_000L
            val d = ZoneTime.wall(millis, zone)
            return ZoneTime.epoch(d.year, d.month, d.day, hour, 0, zone)
        }
        val laterWall = ZoneTime.wall(now + 3 * 3_600_000L, zone)
        val later = ZoneTime.epoch(laterWall.year, laterWall.month, laterWall.day, laterWall.hour, 0, zone)
        val evening = at(0, 18)
        val tomorrow = at(1, 9)
        val nextWeek = at((8 - today.dayOfWeek).toLong(), 9)
        return buildList {
            if (laterWall.epochDay == today.epochDay) add("Later today" to later)
            if (evening > now + 30 * 60_000L && evening != later) add("This evening" to evening)
            add("Tomorrow morning" to tomorrow)
            add("Next week" to nextWeek)
        }
    }

    /** An event's start on the wall clock, or "All day". */
    fun eventTime(event: JsonObject, timezone: String?): String {
        val start = event["start"] as? JsonObject
        if ((event["all_day"] as? JsonPrimitive)?.booleanOrNull == true || !start?.string("date").isNullOrBlank()) return "All day"
        val raw = start?.string("dateTime")?.takeIf { it.isNotBlank() } ?: event.string("start")
        val zone = zone(timezone)
        val millis = ZoneTime.parseOffset(raw) ?: ZoneTime.parseLocal(raw, start?.string("timeZone")?.takeIf { ZoneTime.valid(it) } ?: zone) ?: return "Time unavailable"
        return clock(ZoneTime.wall(millis, zone))
    }

    /** "30 min · Google Meet": length and place, when the event has them. */
    fun eventDetail(event: JsonObject): String? {
        val start = (event["start"] as? JsonObject)?.string("dateTime")?.let(ZoneTime::parseOffset)
        val end = (event["end"] as? JsonObject)?.string("dateTime")?.let(ZoneTime::parseOffset)
        val length = if (start != null && end != null) length((end - start) / 60_000) else null
        val place = event.string("location").ifBlank { event.string("conference").ifBlank { if (event.string("hangoutLink").isNotBlank()) "Video call" else "" } }
        return listOfNotNull(length, place.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null }
    }

    fun length(minutes: Long): String? = when { minutes <= 0 -> null; minutes < 60 -> "$minutes min"; minutes % 60 == 0L -> "${minutes / 60} hr"; else -> "${minutes / 60} hr ${minutes % 60} min" }

    /** "07:30" as the brief stores times of day. */
    fun hhmm(hour: Int, minute: Int) = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    fun parseHhmm(text: String): Pair<Int, Int>? = Regex("^(\\d{1,2}):(\\d{2})(?::\\d{2})?$").matchEntire(text.trim())?.let { m ->
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt(); if (h in 0..23 && min in 0..59) h to min else null
    }

    /** A forecast page for people: the brief's weather page, else a search for the place. Never the API URL. */
    fun forecastPage(source: JsonObject): String = source.string("page").takeIf { it.startsWith("https://weather.com/") }
        ?: ("https://duckduckgo.com/?q=" + PlatformNet.encodeUrl("weather " + source.string("location")))
}
