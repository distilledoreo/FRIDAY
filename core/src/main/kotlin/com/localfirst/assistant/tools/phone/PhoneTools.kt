package com.localfirst.assistant.tools.phone

import com.localfirst.assistant.tools.Tool
import com.localfirst.assistant.tools.ToolExecutionResult
import java.net.URI
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.serialization.json.JsonObject

/** All phone tools, in the order the model sees them. */
fun phoneTools(phone: PhoneActions, now: () -> LocalDateTime = LocalDateTime::now): List<Tool> = listOf(
    OpenAppTool(phone),
    OpenUrlTool(phone),
    OpenMapsTool(phone),
    MediaControlTool(phone),
    PlayMusicTool(phone),
    NowPlayingTool(phone),
    SetAlarmTool(phone),
    SetTimerTool(phone),
    FlashlightTool(phone),
    BatteryStatusTool(phone),
    SearchContactsTool(phone),
    PlaceCallTool(phone),
    SendTextTool(phone),
    AddCalendarEventTool(phone),
    UpcomingEventsTool(phone, now),
) + (phone as? PhoneAccessibilityActions)?.let(::accessibilityTools).orEmpty()

private fun accessibilityTools(accessibility: PhoneAccessibilityActions): List<Tool> = listOf(
    ReadPhoneScreenTool(accessibility),
    ControlPhoneScreenTool(accessibility),
)

private class ReadPhoneScreenTool(private val accessibility: PhoneAccessibilityActions) : PhoneTool(
    name = "read_phone_screen",
    description = "Read visible, non-password text and clickable element bounds on the phone's current screen. Requires the user to enable FRIDAY's accessibility service in Android Settings. Screen content is returned to the current chat model.",
    inputSchema = objectSchema { },
) {
    override suspend fun run(args: JsonObject) = accessibility.readScreen()
}

private class ControlPhoneScreenTool(private val accessibility: PhoneAccessibilityActions) : PhoneTool(
    name = "control_phone_screen",
    description = "Control the phone screen using a tap, swipe, replacement text in the focused field, or Android global action. Coordinates are normalized from 0 (left/top) to 1000 (right/bottom); use clickable bounds from read_phone_screen. The first action requires user approval; approval covers subsequent phone-control actions in this conversation. Requires FRIDAY accessibility to be enabled.",
    inputSchema = objectSchema {
        string("action", "Action to perform.", required = true, enum = listOf("tap", "swipe", "enter_text", "back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen"))
        integer("x", "Tap x coordinate from 0 to 1000.", min = 0, max = 1000)
        integer("y", "Tap y coordinate from 0 to 1000.", min = 0, max = 1000)
        integer("from_x", "Swipe start x coordinate from 0 to 1000.", min = 0, max = 1000)
        integer("from_y", "Swipe start y coordinate from 0 to 1000.", min = 0, max = 1000)
        integer("to_x", "Swipe end x coordinate from 0 to 1000.", min = 0, max = 1000)
        integer("to_y", "Swipe end y coordinate from 0 to 1000.", min = 0, max = 1000)
        integer("duration_ms", "Swipe duration in milliseconds, 100 to 2000. Defaults to 400.", min = 100, max = 2000)
        string("text", "Text to enter into the currently focused field; max 1000 characters.")
    },
) {
    override val requiresConfirmation = true

    override suspend fun confirmationPrompt(arguments: JsonObject): String {
        val action = Args.string(arguments, "action", maxLength = 30)
        return when (action) {
            "tap" -> "Tap the phone screen at (${Args.int(arguments, "x", 0..1000)}, ${Args.int(arguments, "y", 0..1000)})"
            "swipe" -> {
                Args.optionalInt(arguments, "duration_ms", 100..2000)
                "Swipe on the phone screen from (${Args.int(arguments, "from_x", 0..1000)}, ${Args.int(arguments, "from_y", 0..1000)}) to (${Args.int(arguments, "to_x", 0..1000)}, ${Args.int(arguments, "to_y", 0..1000)})"
            }
            "enter_text" -> "Replace the focused phone field's text with: “${Args.string(arguments, "text", 1000)}”"
            else -> {
                if (GlobalPhoneAction.entries.none { it.wireName == action }) {
                    throw IllegalArgumentException("Unsupported phone action '$action'.")
                }
                "Perform the phone action “$action”"
            }
        }
    }

    override suspend fun run(args: JsonObject): String = when (val action = Args.string(args, "action", maxLength = 30)) {
        "tap" -> accessibility.tap(Args.int(args, "x", 0..1000), Args.int(args, "y", 0..1000))
        "swipe" -> accessibility.swipe(
            Args.int(args, "from_x", 0..1000), Args.int(args, "from_y", 0..1000),
            Args.int(args, "to_x", 0..1000), Args.int(args, "to_y", 0..1000),
            Args.optionalInt(args, "duration_ms", 100..2000) ?: 400,
        )
        "enter_text" -> accessibility.enterText(Args.string(args, "text", maxLength = 1000))
        else -> accessibility.globalAction(GlobalPhoneAction.entries.firstOrNull { it.wireName == action }
            ?: throw IllegalArgumentException("Unsupported phone action '$action'."))
    }
}

/** Turns bad arguments and [PhoneActionException]s into failed results the model can read. */
abstract class PhoneTool(
    final override val name: String,
    final override val description: String,
    final override val inputSchema: JsonObject,
) : Tool {
    final override suspend fun execute(arguments: JsonObject): ToolExecutionResult = try {
        ToolExecutionResult(success = true, content = run(arguments))
    } catch (e: IllegalArgumentException) {
        ToolExecutionResult(success = false, content = e.message ?: "Invalid arguments for $name.")
    } catch (e: PhoneActionException) {
        ToolExecutionResult(success = false, content = e.message ?: "The phone couldn't do that.")
    }

    protected abstract suspend fun run(args: JsonObject): String
}

// ---- apps, web, maps ---------------------------------------------------------

class OpenAppTool(private val phone: PhoneActions) : PhoneTool(
    name = "open_app",
    description = "Open an installed app on the phone by its name, e.g. \"Spotify\" or \"Camera\".",
    inputSchema = objectSchema { string("name", "The app's name as shown on the home screen.", required = true) },
) {
    override suspend fun run(args: JsonObject) = phone.openApp(Args.string(args, "name", maxLength = 100))
}

class OpenUrlTool(private val phone: PhoneActions) : PhoneTool(
    name = "open_url",
    description = "Open a web page in the phone's browser.",
    inputSchema = objectSchema { string("url", "Full http or https URL.", required = true) },
) {
    override suspend fun run(args: JsonObject): String {
        val url = Args.string(args, "url", maxLength = 2_000)
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") throw IllegalArgumentException("'url' must start with http:// or https://.")
        return phone.openUrl(url)
    }
}

class OpenMapsTool(private val phone: PhoneActions) : PhoneTool(
    name = "open_maps",
    description = "Show a place or search results in the maps app, or start turn-by-turn navigation to it.",
    inputSchema = objectSchema {
        string("query", "A place, address, or search like \"coffee near me\".", required = true)
        boolean("navigate", "True to start navigation to the place. Defaults to false (just show it).")
    },
) {
    override suspend fun run(args: JsonObject) =
        phone.openMaps(Args.string(args, "query", maxLength = 300), Args.optionalBoolean(args, "navigate") ?: false)
}

// ---- media & Spotify ---------------------------------------------------------

class MediaControlTool(private val phone: PhoneActions) : PhoneTool(
    name = "media_control",
    description = "Control whatever is playing on the phone (Spotify, YouTube, podcasts...): play, pause, skip, or go back.",
    inputSchema = objectSchema {
        string("action", "What to do.", required = true, enum = MediaAction.entries.map { it.wireName })
    },
) {
    override suspend fun run(args: JsonObject): String {
        Args.string(args, "action")
        val action = Args.choice(args, "action", MediaAction.entries.associateBy { it.wireName }, MediaAction.TOGGLE)
        return phone.mediaControl(action)
    }
}

class PlayMusicTool(private val phone: PhoneActions) : PhoneTool(
    name = "play_music",
    description = "Play music in Spotify: a song, artist, album, or playlist found by search.",
    inputSchema = objectSchema {
        string("query", "What to play, e.g. \"Bohemian Rhapsody Queen\" or \"Discover Weekly\".", required = true)
        string("kind", "What the query names. Defaults to any.", enum = MusicKind.entries.map { it.wireName })
    },
) {
    override suspend fun run(args: JsonObject) = phone.playMusic(
        MusicRequest(
            query = Args.string(args, "query", maxLength = 200),
            kind = Args.choice(args, "kind", MusicKind.entries.associateBy { it.wireName }, MusicKind.ANY),
        ),
    )
}

class NowPlayingTool(private val phone: PhoneActions) : PhoneTool(
    name = "now_playing",
    description = "Find out what song or media is currently playing or paused on the phone, and in which app.",
    inputSchema = objectSchema { },
) {
    override suspend fun run(args: JsonObject): String {
        val playing = phone.nowPlaying() ?: return "Nothing is playing on the phone right now."
        val what = listOfNotNull(
            playing.title,
            playing.artist?.let { "by $it" },
            playing.album?.let { "from $it" },
        ).joinToString(" ").ifEmpty { "something" }
        val state = if (playing.playing) "Playing" else "Paused"
        return "$state in ${playing.app}: $what."
    }
}

// ---- clock & device ----------------------------------------------------------

class SetAlarmTool(private val phone: PhoneActions) : PhoneTool(
    name = "set_alarm",
    description = "Set an alarm in the phone's Clock app.",
    inputSchema = objectSchema {
        integer("hour", "Hour in 24-hour time, 0 to 23.", min = 0, max = 23, required = true)
        integer("minute", "Minute, 0 to 59.", min = 0, max = 59, required = true)
        string("label", "Only if the user gave one; don't repeat the time.")
        stringArray("days", "Days to repeat on. Leave out for a one-time alarm.", DAY_NAMES.keys.toList())
    },
) {
    override suspend fun run(args: JsonObject): String {
        val days = Args.stringList(args, "days").map { day ->
            DAY_NAMES[day.lowercase().take(3)]
                ?: throw IllegalArgumentException("'days' entries must be one of: ${DAY_NAMES.keys.joinToString()}.")
        }.distinct()
        return phone.setAlarm(
            AlarmRequest(
                hour = Args.int(args, "hour", 0..23),
                minute = Args.int(args, "minute", 0..59),
                label = Args.optionalString(args, "label", maxLength = 100),
                days = days,
            ),
        )
    }

    private companion object {
        val DAY_NAMES = linkedMapOf(
            "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY,
            "sun" to DayOfWeek.SUNDAY,
        )
    }
}

class SetTimerTool(private val phone: PhoneActions) : PhoneTool(
    name = "set_timer",
    description = "Start a countdown timer in the phone's Clock app.",
    inputSchema = objectSchema {
        integer("hours", "Hours.", min = 0, max = 24)
        integer("minutes", "Minutes.", min = 0, max = 1_440)
        integer("seconds", "Seconds.", min = 0, max = 86_400)
        string("label", "Optional timer label.")
    },
) {
    override suspend fun run(args: JsonObject): String {
        val total = (Args.optionalInt(args, "hours", 0..24) ?: 0) * 3_600 +
            (Args.optionalInt(args, "minutes", 0..1_440) ?: 0) * 60 +
            (Args.optionalInt(args, "seconds", 0..86_400) ?: 0)
        if (total !in 1..86_400) throw IllegalArgumentException("The timer must be between 1 second and 24 hours.")
        return phone.setTimer(total, Args.optionalString(args, "label", maxLength = 100))
    }
}

class FlashlightTool(private val phone: PhoneActions) : PhoneTool(
    name = "flashlight",
    description = "Turn the phone's flashlight on or off.",
    inputSchema = objectSchema { boolean("on", "True for on, false for off.", required = true) },
) {
    override suspend fun run(args: JsonObject) = phone.setFlashlight(Args.boolean(args, "on"))
}

class BatteryStatusTool(private val phone: PhoneActions) : PhoneTool(
    name = "battery_status",
    description = "Get the phone's battery level and whether it is charging.",
    inputSchema = objectSchema { },
) {
    override suspend fun run(args: JsonObject): String {
        val battery = phone.batteryStatus()
        val charging = when {
            !battery.charging -> "not charging"
            battery.source != null -> "charging (${battery.source})"
            else -> "charging"
        }
        val eta = battery.minutesToFull?.takeIf { battery.charging && it > 0 }?.let { ", about ${formatMinutes(it)} to full" }.orEmpty()
        return "Battery is at ${battery.percent}%, $charging$eta."
    }
}

// ---- contacts, calls, texts --------------------------------------------------

class SearchContactsTool(private val phone: PhoneActions) : PhoneTool(
    name = "search_contacts",
    description = "Look up contacts by name and get their phone numbers.",
    inputSchema = objectSchema {
        string("query", "A name or part of one.", required = true)
        integer("limit", "Maximum matches, 1 to 10. Defaults to 5.", min = 1, max = 10)
    },
) {
    override suspend fun run(args: JsonObject): String {
        val query = Args.string(args, "query", maxLength = 100)
        val matches = phone.searchContacts(query, Args.optionalInt(args, "limit", 1..10) ?: 5)
        if (matches.isEmpty()) return "No contacts match \"$query\"."
        return "Contacts matching \"$query\":\n" + matches.joinToString("\n") { "- ${it.name}: ${it.number} (${it.type})" }
    }
}

class PlaceCallTool(private val phone: PhoneActions) : PhoneTool(
    name = "place_call",
    description = "Call a contact or phone number. The user approves the call on screen first; do not ask in text.",
    inputSchema = objectSchema { string("to", "A contact name or a phone number.", required = true) },
) {
    override val requiresConfirmation = true

    override suspend fun confirmationPrompt(arguments: JsonObject): String =
        "Call ${Recipients.resolve(phone, Args.string(arguments, "to", maxLength = 100)).describe()}"

    override suspend fun run(args: JsonObject): String {
        val recipient = Recipients.resolve(phone, Args.string(args, "to", maxLength = 100))
        return phone.placeCall(recipient.number)
    }
}

class SendTextTool(private val phone: PhoneActions) : PhoneTool(
    name = "send_text",
    description = "Send an SMS text message to a contact or phone number. The user approves it on screen first; do not ask in text.",
    inputSchema = objectSchema {
        string("to", "A contact name or a phone number.", required = true)
        string("message", "The text to send.", required = true)
    },
) {
    override val requiresConfirmation = true

    override suspend fun confirmationPrompt(arguments: JsonObject): String {
        val recipient = Recipients.resolve(phone, Args.string(arguments, "to", maxLength = 100))
        return "Text ${recipient.describe()}: “${Args.string(arguments, "message", maxLength = MAX_SMS)}”"
    }

    override suspend fun run(args: JsonObject): String {
        val recipient = Recipients.resolve(phone, Args.string(args, "to", maxLength = 100))
        return phone.sendText(recipient.number, Args.string(args, "message", maxLength = MAX_SMS))
    }

    private companion object {
        const val MAX_SMS = 1_000
    }
}

data class Recipient(val name: String?, val number: String, val type: String?) {
    fun describe(): String = if (name == null) number else "$name (${listOfNotNull(type, number).joinToString(", ")})"
}

/** Turns "Mom" or "+1 555 0100" into one phone number, or explains why it can't. */
object Recipients {
    private val numberLike = Regex("^[+]?[0-9 ()\\-.]+$")

    suspend fun resolve(phone: PhoneActions, to: String): Recipient {
        val text = to.trim()
        if (numberLike.matches(text) && text.count(Char::isDigit) >= 3) return Recipient(null, text, null)

        val matches = phone.searchContacts(text, limit = 10)
        if (matches.isEmpty()) throw PhoneActionException("No contact matches \"$text\". Ask the user for the number or another name.")
        val byName = matches.groupBy { it.name }
        val exact = byName.keys.filter { it.equals(text, ignoreCase = true) }
        val name = when {
            byName.size == 1 -> byName.keys.single()
            exact.size == 1 -> exact.single()
            else -> throw PhoneActionException(
                "Several contacts match \"$text\": ${byName.keys.joinToString()}. Ask the user which one.",
            )
        }
        val numbers = byName.getValue(name).distinctBy { it.number.filter(Char::isDigit) }
        val pick = when {
            numbers.size == 1 -> numbers.single()
            numbers.count { it.type == "mobile" } == 1 -> numbers.single { it.type == "mobile" }
            else -> throw PhoneActionException(
                "$name has several numbers: ${numbers.joinToString { "${it.type} ${it.number}" }}. Ask the user which one, then use the number.",
            )
        }
        return Recipient(pick.name, pick.number, pick.type)
    }
}

// ---- calendar ----------------------------------------------------------------

class AddCalendarEventTool(private val phone: PhoneActions) : PhoneTool(
    name = "add_calendar_event",
    description = "Add an event to the phone's calendar. Use the current date and time from the system prompt to resolve words like \"tomorrow\".",
    inputSchema = objectSchema {
        string("title", "Event title.", required = true)
        string("start", "Local start time as YYYY-MM-DDTHH:MM, or YYYY-MM-DD for an all-day event.", required = true)
        string("end", "Optional local end time as YYYY-MM-DDTHH:MM.")
        integer("duration_minutes", "Used when end is not given. Defaults to 60.", min = 1, max = 10_080)
        string("location", "Optional location.")
        string("notes", "Optional description.")
    },
) {
    override suspend fun run(args: JsonObject): String {
        val title = Args.string(args, "title", maxLength = 200)
        val startText = Args.string(args, "start", maxLength = 30)
        val location = Args.optionalString(args, "location", maxLength = 300)
        val notes = Args.optionalString(args, "notes", maxLength = 2_000)
        val request = if (startText.length == 10) {
            CalendarEventRequest(title, start = null, end = null, allDayDate = parseDate(startText), location = location, notes = notes)
        } else {
            val start = parseDateTime(startText, "start")
            val end = Args.optionalString(args, "end", maxLength = 30)?.let { parseDateTime(it, "end") }
                ?: start.plusMinutes((Args.optionalInt(args, "duration_minutes", 1..10_080) ?: 60).toLong())
            if (!end.isAfter(start)) throw IllegalArgumentException("'end' must be after 'start'.")
            CalendarEventRequest(title, start = start, end = end, allDayDate = null, location = location, notes = notes)
        }
        return phone.addCalendarEvent(request)
    }

    private fun parseDate(text: String): LocalDate = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("'start' must look like 2026-10-08 or 2026-10-08T15:30.")
    }

    private fun parseDateTime(text: String, field: String): LocalDateTime = try {
        LocalDateTime.parse(text.removeSuffix("Z").take(19))
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("'$field' must look like 2026-10-08T15:30.")
    }
}

class UpcomingEventsTool(
    private val phone: PhoneActions,
    private val now: () -> LocalDateTime,
) : PhoneTool(
    name = "upcoming_events",
    description = "List calendar events for one or more days. For \"tomorrow\", pass tomorrow's date as start_date.",
    inputSchema = objectSchema {
        string("start_date", "First day to list, YYYY-MM-DD. Defaults to today (from now on).")
        integer("days", "How many days to list, starting with start_date, 1 to 31. Defaults to 1.", min = 1, max = 31)
    },
) {
    override suspend fun run(args: JsonObject): String {
        val days = Args.optionalInt(args, "days", 1..31) ?: 1
        val current = now()
        val startDate = Args.optionalString(args, "start_date", maxLength = 10)?.let {
            try {
                LocalDate.parse(it)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("'start_date' must look like 2026-10-08.")
            }
        } ?: current.toLocalDate()
        val from = if (startDate == current.toLocalDate()) current else startDate.atStartOfDay()
        val to = startDate.plusDays(days.toLong()).atStartOfDay()
        if (!to.isAfter(from)) return "That date range is already over."
        val range = if (days == 1) "on ${startDate.format(dayFormat)}" else "from ${startDate.format(dayFormat)} for $days days"
        val events = phone.upcomingEvents(from, to)
        if (events.isEmpty()) return "No events $range."
        return "Events $range:\n" + events.joinToString("\n") { "- ${formatEvent(it)}" }
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE MMM d", Locale.ENGLISH)
private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

internal fun formatEvent(event: CalendarEntry): String {
    val day = event.start.format(dayFormat)
    val time = when {
        event.allDay -> "all day"
        event.end == null -> event.start.format(timeFormat)
        else -> "${event.start.format(timeFormat)}–${event.end.format(timeFormat)}"
    }
    val place = event.location?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    return "$day, $time: ${event.title}$place"
}

internal fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}
