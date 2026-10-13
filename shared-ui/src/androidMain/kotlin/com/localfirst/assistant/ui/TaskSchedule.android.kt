package com.localfirst.assistant.ui
import com.localfirst.assistant.presentation.TaskSchedule
import java.time.LocalDate
import java.time.ZoneId
internal actual fun resolveTaskStart(date: String, hour: Int, minute: Int, now: Long): Long =
    TaskSchedule.resolve(LocalDate.parse(date), hour, minute, ZoneId.systemDefault(), now)
