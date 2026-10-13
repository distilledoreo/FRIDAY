package com.localfirst.assistant.platform

/** A wall-clock time in some zone. [month] is 1–12 and [dayOfWeek] 1 (Monday) to 7 (Sunday). */
data class WallTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val dayOfWeek: Int) {
    /** Days since 1970-01-01 for this date, for counting whole days between dates. */
    val epochDay: Long get() {
        val y = if (month <= 2) year - 1L else year.toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
}

/** Times in a named IANA zone, for the dashboard's events and follow-up reminders on both clients. */
expect object ZoneTime {
    /** This device's zone. */
    fun system(): String
    fun valid(zone: String): Boolean
    fun wall(epochMillis: Long, zone: String): WallTime
    /** The instant of a wall-clock time in [zone]; a time skipped by a clock change moves forward to a real one. */
    fun epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: String): Long
    /** An offset-aware ISO time such as 2026-10-13T09:00:00-04:00. */
    fun iso(epochMillis: Long, zone: String): String
    /** An offset-aware ISO time, or null when [text] isn't one. */
    fun parseOffset(text: String): Long?
    /** A local ISO time without offset, read in [zone]. */
    fun parseLocal(text: String, zone: String): Long?
}
