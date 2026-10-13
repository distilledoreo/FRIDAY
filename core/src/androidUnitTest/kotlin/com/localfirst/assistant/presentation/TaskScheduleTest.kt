package com.localfirst.assistant.presentation

import java.time.*
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskScheduleTest {
    @Test fun schedulesAcrossMidnightInLocalTimezone() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 10, 7, 23, 58, 0, 0, zone)
        val future = now.plusMinutes(5)
        assertEquals(future.toInstant().toEpochMilli(), TaskSchedule.resolve(future.toLocalDate(), future.hour, future.minute, zone, now.toInstant().toEpochMilli()))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPastTimes() {
        TaskSchedule.resolve(LocalDate.of(2026, 10, 7), 12, 0, ZoneOffset.UTC, Instant.parse("2026-10-07T12:00:00Z").toEpochMilli())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingDaylightSavingTime() {
        TaskSchedule.resolve(LocalDate.of(2027, 3, 14), 2, 30, ZoneId.of("America/New_York"), 0)
    }
}
