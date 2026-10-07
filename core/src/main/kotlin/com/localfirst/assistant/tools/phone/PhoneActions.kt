package com.localfirst.assistant.tools.phone

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Everything the phone tools can ask Android to do. The app implements this;
 * tests use a fake. Methods that just act return a short, user-facing sentence
 * describing what happened ("Opened Spotify."). Throw [PhoneActionException]
 * with a readable message when the action can't be done (missing permission,
 * app not installed, and so on).
 */
interface PhoneActions {
    suspend fun openApp(name: String): String
    suspend fun openUrl(url: String): String
    suspend fun openMaps(query: String, navigate: Boolean): String

    suspend fun mediaControl(action: MediaAction): String
    suspend fun playMusic(request: MusicRequest): String
    suspend fun nowPlaying(): NowPlaying?

    suspend fun setAlarm(alarm: AlarmRequest): String
    suspend fun setTimer(seconds: Int, label: String?): String
    suspend fun setFlashlight(on: Boolean): String
    suspend fun batteryStatus(): BatteryStatus

    suspend fun searchContacts(query: String, limit: Int): List<ContactMatch>
    suspend fun placeCall(number: String): String
    suspend fun sendText(number: String, message: String): String

    suspend fun addCalendarEvent(event: CalendarEventRequest): String
    suspend fun upcomingEvents(from: LocalDateTime, to: LocalDateTime): List<CalendarEntry>
}

class PhoneActionException(message: String) : Exception(message)

enum class MediaAction(val wireName: String) {
    PLAY("play"),
    PAUSE("pause"),
    TOGGLE("play_pause"),
    NEXT("next"),
    PREVIOUS("previous"),
}

enum class MusicKind(val wireName: String) {
    ANY("any"),
    TRACK("track"),
    ARTIST("artist"),
    ALBUM("album"),
    PLAYLIST("playlist"),
}

data class MusicRequest(
    val query: String,
    val kind: MusicKind = MusicKind.ANY,
)

data class NowPlaying(
    val app: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val playing: Boolean,
)

data class AlarmRequest(
    val hour: Int,
    val minute: Int,
    val label: String? = null,
    /** Empty for a one-time alarm. */
    val days: List<DayOfWeek> = emptyList(),
)

data class BatteryStatus(
    val percent: Int,
    val charging: Boolean,
    /** "AC", "USB", "wireless", or null when unplugged. */
    val source: String?,
    val minutesToFull: Int? = null,
)

data class ContactMatch(
    val name: String,
    val number: String,
    /** "mobile", "home", "work", ... */
    val type: String,
)

data class CalendarEventRequest(
    val title: String,
    /** Exactly one of [start] or [allDayDate] is set. */
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val allDayDate: LocalDate?,
    val location: String? = null,
    val notes: String? = null,
)

data class CalendarEntry(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime?,
    val allDay: Boolean,
    val location: String?,
)
