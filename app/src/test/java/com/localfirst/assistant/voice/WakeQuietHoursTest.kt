package com.localfirst.assistant.voice

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeQuietHoursTest {
    private fun at(hour: Int, minute: Int) = LocalTime.of(hour, minute)

    @Test fun quietHoursAcrossMidnightLastUntilTheirEnd() {
        val (start, end) = 22 * 60 to 6 * 60
        assertEquals(8 * 3_600_000L, WakeQuietHours.remaining(start, end, at(22, 0)))
        assertEquals(30 * 60_000L, WakeQuietHours.remaining(start, end, at(5, 30)))
        assertEquals(0L, WakeQuietHours.remaining(start, end, at(6, 0)))
        assertEquals(0L, WakeQuietHours.remaining(start, end, at(14, 0)))
    }

    @Test fun daytimeQuietHoursAndNoneAtAll() {
        assertEquals(60 * 60_000L, WakeQuietHours.remaining(13 * 60, 15 * 60, at(14, 0)))
        assertEquals(0L, WakeQuietHours.remaining(13 * 60, 15 * 60, at(15, 0)))
        assertEquals(0L, WakeQuietHours.remaining(9 * 60, 9 * 60, at(9, 0)))
    }
}
