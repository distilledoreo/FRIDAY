package com.localfirst.assistant.platform

import java.time.*

actual object ZoneTime {
    actual fun system(): String = ZoneId.systemDefault().id
    actual fun valid(zone: String) = runCatching { ZoneId.of(zone) }.isSuccess
    private fun id(zone: String) = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())
    actual fun wall(epochMillis: Long, zone: String): WallTime = Instant.ofEpochMilli(epochMillis).atZone(id(zone)).let {
        WallTime(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute, it.dayOfWeek.value)
    }
    actual fun epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: String): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(id(zone)).toInstant().toEpochMilli()
    actual fun iso(epochMillis: Long, zone: String): String = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(id(zone)).toOffsetDateTime().withNano(0))
    actual fun parseOffset(text: String): Long? = runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    actual fun parseLocal(text: String, zone: String): Long? = runCatching { LocalDateTime.parse(text).atZone(id(zone)).toInstant().toEpochMilli() }.getOrNull()
}
