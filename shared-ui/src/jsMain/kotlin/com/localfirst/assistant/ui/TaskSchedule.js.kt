package com.localfirst.assistant.ui
import com.localfirst.assistant.platform.PlatformTime
internal actual fun resolveTaskStart(date: String, hour: Int, minute: Int, now: Long): Long {
    val parts = date.split('-').map(String::toDouble)
    val time = PlatformTime.fromParts(listOf(parts[0], parts[1] - 1, parts[2], hour.toDouble(), minute.toDouble()), false)
    require(time.isFinite() && PlatformTime.field(time, false, "Hours") == hour && PlatformTime.field(time, false, "Minutes") == minute) {
        "This time does not exist because the clocks change. Choose another time."
    }
    require(time > now) { "Choose a future date and time." }
    return time.toLong()
}
