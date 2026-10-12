package com.localfirst.assistant.presentation

import java.time.*

object TaskSchedule {
    fun resolve(date: LocalDate, hour: Int, minute: Int, zone: ZoneId, now: Long): Long {
        val local = date.atTime(hour, minute)
        require(zone.rules.getValidOffsets(local).isNotEmpty()) { "This time does not exist because the clocks change. Choose another time." }
        val result = local.atZone(zone).toInstant().toEpochMilli()
        require(result > now) { "Choose a future date and time." }
        return result
    }
}
