package com.localfirst.assistant.client

import com.localfirst.assistant.platform.ZoneTime
import kotlinx.serialization.json.*
import kotlin.test.*

class BriefTimesTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private val tuesdayMorning = ZoneTime.epoch(2026, 10, 13, 8, 15, "America/New_York")

    @Test fun eventsAndFollowupsShowOnTheBriefsWallClock() {
        assertEquals("10:00 AM", BriefTimes.eventTime(json("""{"start":{"dateTime":"2026-10-08T14:00:00Z","timeZone":"UTC"}}"""), "America/New_York"))
        assertEquals("2:00 PM", BriefTimes.eventTime(json("""{"start":{"dateTime":"2026-10-08T14:00:00","timeZone":"UTC"}}"""), "UTC"))
        assertEquals("All day", BriefTimes.eventTime(json("""{"start":{"date":"2026-10-08"}}"""), "UTC"))
        assertEquals("Time unavailable", BriefTimes.eventTime(json("""{"start":{}}"""), "UTC"))
        assertEquals("1 hr 30 min · Room 4", BriefTimes.eventDetail(json("""{"start":{"dateTime":"2026-10-08T14:00:00Z"},"end":{"dateTime":"2026-10-08T15:30:00Z"},"location":"Room 4"}""")))
        assertEquals("Tuesday, October 13", BriefTimes.heading(tuesdayMorning, "America/New_York"))
        val due = ZoneTime.epoch(2026, 10, 14, 9, 0, "America/New_York") / 1000.0
        assertEquals("Tomorrow · 9:00 AM", BriefTimes.dueLabel(due, "America/New_York", tuesdayMorning))
        assertTrue(BriefTimes.dueLabel(due, "America/New_York", tuesdayMorning + 2 * 86_400_000L).startsWith("Overdue · Yesterday"))
    }

    @Test fun reminderPresetsLandOnTheBriefsZoneAcrossDaylightSavingChanges() {
        val presets = BriefTimes.presets("America/New_York", tuesdayMorning).toMap()
        assertEquals(listOf("Later today", "This evening", "Tomorrow morning", "Next week"), BriefTimes.presets("America/New_York", tuesdayMorning).map { it.first })
        assertEquals(ZoneTime.epoch(2026, 10, 13, 11, 0, "America/New_York"), presets["Later today"])
        assertEquals(ZoneTime.epoch(2026, 10, 13, 18, 0, "America/New_York"), presets["This evening"])
        assertEquals(ZoneTime.epoch(2026, 10, 19, 9, 0, "America/New_York"), presets["Next week"])
        // Saturday before the November clock change: next Monday 9:00 is still 9:00 on the wall.
        val saturday = ZoneTime.epoch(2026, 10, 31, 20, 0, "America/New_York")
        val monday = BriefTimes.presets("America/New_York", saturday).toMap().getValue("Next week")
        assertEquals("2026-11-02T09:00:00-05:00", ZoneTime.iso(monday, "America/New_York"))
        assertEquals("2026-10-13T08:15:00-04:00", ZoneTime.iso(tuesdayMorning, "America/New_York"))
        assertEquals(tuesdayMorning, ZoneTime.parseOffset("2026-10-13T08:15:00-04:00"))
        assertNull(ZoneTime.parseOffset("2026-10-13T08:15:00"))
        assertFalse(ZoneTime.valid("Not/AZone")); assertTrue(ZoneTime.valid("Asia/Kolkata"))
        assertEquals("Mon 9:00 AM", BriefTimes.presetLabel(monday, "America/New_York", saturday))
        assertEquals(7 to 30, BriefTimes.parseHhmm("07:30")); assertEquals("07:05", BriefTimes.hhmm(7, 5)); assertNull(BriefTimes.parseHhmm("25:00"))
    }
}
