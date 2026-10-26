package com.localfirst.assistant.voice

import android.content.Context
import java.time.LocalTime

/**
 * The Dashboard's quiet hours, kept on the phone so the wake phrase listener rests through them even offline.
 * Saved whenever the app reads the brief settings from the computer.
 */
object WakeQuietHours {
    private const val PREFS = "wake_word"
    private const val START = "quiet_start"
    private const val END = "quiet_end"

    /** Keeps "HH:MM" [start] and [end]; anything unreadable clears them, so the listener never rests by mistake. */
    fun save(context: Context, start: String?, end: String?) {
        val from = minutes(start); val until = minutes(end)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (from == null || until == null) { remove(START); remove(END) } else { putInt(START, from); putInt(END, until) }
        }.apply()
    }

    /** Milliseconds until the saved quiet hours end, or 0 outside them (or when none are saved). */
    fun remaining(context: Context, now: LocalTime = LocalTime.now()): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(START) || !prefs.contains(END)) return 0
        return remaining(prefs.getInt(START, 0), prefs.getInt(END, 0), now)
    }

    /** Milliseconds from [now] until [end] (minutes of the day) when [now] is within [start]–[end], which may wrap midnight; else 0. */
    fun remaining(start: Int, end: Int, now: LocalTime): Long {
        if (start == end) return 0
        val minute = now.hour * 60 + now.minute
        val inside = if (start < end) minute in start until end else minute >= start || minute < end
        if (!inside) return 0
        val untilEnd = ((end - minute + 1440) % 1440).toLong() * 60_000
        return (untilEnd - now.second * 1000L - now.nano / 1_000_000).coerceAtLeast(1)
    }

    private fun minutes(clock: String?): Int? {
        val (hour, minute) = clock?.split(':')?.takeIf { it.size == 2 }?.map { it.toIntOrNull() ?: return null } ?: return null
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }
}
