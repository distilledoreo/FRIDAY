package com.localfirst.assistant.ui.genui
import com.localfirst.assistant.platform.PlatformTime
internal expect fun mapUri(lat: Double?, lng: Double?, query: String): String
/** DatePicker represents calendar dates at UTC midnight on every platform. */
internal fun isoDateMillis(value: String): Long? {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) return null
    val parts = value.split('-').map(String::toDouble)
    val time = PlatformTime.fromParts(listOf(parts[0], parts[1] - 1, parts[2]), utc = true)
    return time.takeIf { it.isFinite() && PlatformTime.isoString(it).take(10) == value }?.toLong()
}

/** Stable display numbers on both clients; Java Formatter flags are not portable to JavaScript. */
internal fun formatUiDecimal(value: Double, digits: Int, grouped: Boolean = true): String =
    com.localfirst.assistant.platform.PlatformDecimalFormat("#,##0", com.localfirst.assistant.platform.PlatformDecimalFormatSymbols()).apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
        isGroupingUsed = grouped
    }.format(value)
