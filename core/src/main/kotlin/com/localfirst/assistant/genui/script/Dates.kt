package com.localfirst.assistant.genui.script

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor

/** JS Date on java.time. Local getters use [zone], the device's time zone unless a test sets it. */
object Dates {
    @Volatile var zone: ZoneId = ZoneId.systemDefault()
    /** The clock behind `Date.now()` and `new Date()`. */
    @Volatile var now: () -> Long = System::currentTimeMillis

    private fun zoned(t: Double, utc: Boolean = false): ZonedDateTime =
        Instant.ofEpochMilli(t.toLong()).atZone(if (utc) ZoneOffset.UTC else zone)

    fun toIsoString(t: Double): String {
        if (t.isNaN()) return "Invalid Date"
        return DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").format(zoned(t, utc = true))
    }

    fun toDisplayString(t: Double): String {
        if (t.isNaN()) return "Invalid Date"
        val z = zoned(t)
        val offset = z.offset.totalSeconds / 60
        val sign = if (offset >= 0) "+" else "-"
        val o = Math.abs(offset)
        return DateTimeFormatter.ofPattern("EEE MMM dd uuuu HH:mm:ss", Locale.US).format(z) +
            " GMT$sign${(o / 60).toString().padStart(2, '0')}${(o % 60).toString().padStart(2, '0')}"
    }

    /** Parses the formats models write: ISO dates and times, and "Oct 11, 2026"-style text. */
    fun parse(text: String): Double {
        val s = text.trim()
        runCatching { return Instant.parse(s).toEpochMilli().toDouble() }
        runCatching { return ZonedDateTime.parse(s).toInstant().toEpochMilli().toDouble() }
        runCatching { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli().toDouble() }
        // A date-only ISO string is UTC midnight; a date-time without an offset is local time.
        runCatching { return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli().toDouble() }
        runCatching { return LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli().toDouble() }
        val patterns = listOf("MMM d, uuuu", "MMMM d, uuuu", "MMM d uuuu", "MMMM d uuuu", "d MMM uuuu", "d MMMM uuuu", "M/d/uuuu", "EEE MMM dd uuuu", "EEE, MMM d, uuuu")
        for (pattern in patterns) {
            runCatching { return LocalDate.parse(s, DateTimeFormatter.ofPattern(pattern, Locale.US)).atStartOfDay(zone).toInstant().toEpochMilli().toDouble() }
        }
        val withTime = listOf("MMM d, uuuu h:mm a", "MMMM d, uuuu h:mm a", "M/d/uuuu h:mm a", "MMM d, uuuu HH:mm", "M/d/uuuu, h:mm:ss a")
        for (pattern in withTime) {
            runCatching { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(pattern, Locale.US)).atZone(zone).toInstant().toEpochMilli().toDouble() }
        }
        return Double.NaN
    }

    private fun fromParts(parts: List<Double>, utc: Boolean): Double {
        if (parts.any { it.isNaN() || it.isInfinite() }) return Double.NaN
        val year = parts.getOrElse(0) { 1970.0 }.let { if (it in 0.0..99.0) 1900 + it else it }.toInt()
        val month = parts.getOrElse(1) { 0.0 }.toLong()
        val start = LocalDateTime.of(year, 1, 1, 0, 0).plusMonths(month)
            .plusDays(parts.getOrElse(2) { 1.0 }.toLong() - 1)
            .plusHours(parts.getOrElse(3) { 0.0 }.toLong())
            .plusMinutes(parts.getOrElse(4) { 0.0 }.toLong())
            .plusSeconds(parts.getOrElse(5) { 0.0 }.toLong())
            .plusNanos(parts.getOrElse(6) { 0.0 }.toLong() * 1_000_000)
        return start.atZone(if (utc) ZoneOffset.UTC else zone).toInstant().toEpochMilli().toDouble()
    }

    fun install(interp: Interpreter) {
        val proto = JsObject()
        fun date(self: Any?): JsDate = self as? JsDate ?: throw interp.typeError("this is not a Date object.")
        fun getter(name: String, utc: Boolean, f: (ZonedDateTime) -> Int) =
            proto.set(name, NativeFunction(name) { _, t, _ -> val d = date(t); if (d.time.isNaN()) Double.NaN else f(zoned(d.time, utc)).toDouble() })
        for (utc in listOf(false, true)) {
            val u = if (utc) "UTC" else ""
            getter("get${u}FullYear", utc) { it.year }
            getter("get${u}Month", utc) { it.monthValue - 1 }
            getter("get${u}Date", utc) { it.dayOfMonth }
            getter("get${u}Day", utc) { it.dayOfWeek.value % 7 }
            getter("get${u}Hours", utc) { it.hour }
            getter("get${u}Minutes", utc) { it.minute }
            getter("get${u}Seconds", utc) { it.second }
            getter("get${u}Milliseconds", utc) { it.nano / 1_000_000 }
        }
        proto.set("getYear", NativeFunction("getYear") { _, t, _ -> (zoned(date(t).time).year - 1900).toDouble() })
        proto.set("getTime", NativeFunction("getTime") { _, t, _ -> date(t).time })
        proto.set("valueOf", NativeFunction("valueOf") { _, t, _ -> date(t).time })
        proto.set("getTimezoneOffset", NativeFunction("getTimezoneOffset") { _, t, _ -> -(zoned(date(t).time).offset.totalSeconds / 60).toDouble() })
        proto.set("toISOString", NativeFunction("toISOString") { i, t, _ -> date(t).time.let { if (it.isNaN()) throw i.error("RangeError", "Invalid time value") else toIsoString(it) } })
        proto.set("toJSON", NativeFunction("toJSON") { _, t, _ -> date(t).time.let { if (it.isNaN()) Null else toIsoString(it) } })
        proto.set("toString", NativeFunction("toString") { _, t, _ -> toDisplayString(date(t).time) })
        proto.set("toDateString", NativeFunction("toDateString") { _, t, _ -> date(t).time.let { if (it.isNaN()) "Invalid Date" else DateTimeFormatter.ofPattern("EEE MMM dd uuuu", Locale.US).format(zoned(it)) } })
        proto.set("toTimeString", NativeFunction("toTimeString") { _, t, _ -> toDisplayString(date(t).time).substringAfter(' ').substringAfter(' ').substringAfter(' ').substringAfter(' ') })
        proto.set("toUTCString", NativeFunction("toUTCString") { _, t, _ -> DateTimeFormatter.RFC_1123_DATE_TIME.format(zoned(date(t).time, utc = true)) })
        proto.set("toLocaleDateString", NativeFunction("toLocaleDateString", 2) { _, t, a -> format(date(t).time, a.getOrNull(1) as? JsObject, "date") })
        proto.set("toLocaleTimeString", NativeFunction("toLocaleTimeString", 2) { _, t, a -> format(date(t).time, a.getOrNull(1) as? JsObject, "time") })
        proto.set("toLocaleString", NativeFunction("toLocaleString", 2) { _, t, a -> format(date(t).time, a.getOrNull(1) as? JsObject, "datetime") })
        fun setter(name: String, utc: Boolean, apply: (ZonedDateTime, List<Double>) -> ZonedDateTime) =
            proto.set(name, NativeFunction(name, 1) { _, t, a ->
                val d = date(t)
                val values = a.map { Values.toNumber(it) }
                d.time = if (d.time.isNaN() || values.any { it.isNaN() }) Double.NaN else apply(zoned(d.time, utc), values).toInstant().toEpochMilli().toDouble()
                d.time
            })
        for (utc in listOf(false, true)) {
            val u = if (utc) "UTC" else ""
            setter("set${u}FullYear", utc) { z, v -> z.withYear(v[0].toInt()).let { r -> if (v.size > 1) r.withMonth(1).plusMonths(v[1].toLong()) else r }.let { r -> if (v.size > 2) r.withDayOfMonth(1).plusDays(v[2].toLong() - 1) else r } }
            setter("set${u}Month", utc) { z, v -> z.withMonth(1).plusMonths(v[0].toLong()).let { r -> if (v.size > 1) r.withDayOfMonth(1).plusDays(v[1].toLong() - 1) else r } }
            setter("set${u}Date", utc) { z, v -> z.withDayOfMonth(1).plusDays(v[0].toLong() - 1) }
            setter("set${u}Hours", utc) { z, v -> z.withHour(0).plusHours(v[0].toLong()).let { r -> if (v.size > 1) r.withMinute(0).plusMinutes(v[1].toLong()) else r } }
            setter("set${u}Minutes", utc) { z, v -> z.withMinute(0).plusMinutes(v[0].toLong()) }
            setter("set${u}Seconds", utc) { z, v -> z.withSecond(0).plusSeconds(v[0].toLong()) }
            setter("set${u}Milliseconds", utc) { z, v -> z.withNano(0).plusNanos(v[0].toLong() * 1_000_000) }
        }
        proto.set("setTime", NativeFunction("setTime", 1) { _, t, a -> date(t).also { it.time = Values.toNumber(a.firstOrNull()) }.time })

        fun make(args: List<Any?>): JsDate {
            interp.allocate()
            val values = args.map(Values::normalize)
            val time = when {
                values.isEmpty() -> now().toDouble()
                values.size == 1 -> when (val v = values[0]) {
                    is JsDate -> v.time
                    is String -> parse(v)
                    else -> Values.toNumber(v).let { if (it.isFinite()) floor(it) else Double.NaN }
                }
                else -> fromParts(values.map { Values.toNumber(it) }, utc = false)
            }
            return JsDate(time, proto)
        }
        val ctor = NativeFunction("Date", 7) { _, _, _ -> toDisplayString(now().toDouble()) }
        ctor.set("__construct", NativeFunction("Date", 7) { _, _, a -> make(a) })
        ctor.set("now", NativeFunction("now") { _, _, _ -> now().toDouble() })
        ctor.set("parse", NativeFunction("parse", 1) { _, _, a -> parse(Values.toStr(a.firstOrNull())) })
        ctor.set("UTC", NativeFunction("UTC", 7) { _, _, a -> fromParts(a.map { Values.toNumber(it) }, utc = true) })
        ctor.set("prototype", proto)
        interp.globals.declare("Date", ctor, constant = true)
    }

    /** toLocaleDateString / toLocaleTimeString / toLocaleString and Intl.DateTimeFormat, in en-US style. */
    fun format(t: Double, options: JsObject?, kind: String): String {
        if (t.isNaN()) return "Invalid Date"
        fun opt(key: String) = options?.get(key)?.takeIf { it !== Undefined }?.let(Values::toStr)
        val tz = opt("timeZone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
        val z = Instant.ofEpochMilli(t.toLong()).atZone(tz)
        val dateStyle = opt("dateStyle")
        val timeStyle = opt("timeStyle")
        var weekday = opt("weekday")
        var year = opt("year")
        var month = opt("month")
        var day = opt("day")
        var hour = opt("hour")
        var minute = opt("minute")
        var second = opt("second")
        val hour12 = options?.get("hour12")?.takeIf { it !== Undefined }?.let(Values::truthy) ?: (opt("hourCycle")?.let { it == "h12" || it == "h11" } ?: true)
        when (dateStyle) {
            "full" -> { weekday = "long"; month = "long"; day = "numeric"; year = "numeric" }
            "long" -> { month = "long"; day = "numeric"; year = "numeric" }
            "medium" -> { month = "short"; day = "numeric"; year = "numeric" }
            "short" -> { month = "numeric"; day = "numeric"; year = "2-digit" }
        }
        when (timeStyle) {
            "full", "long", "medium" -> { hour = "numeric"; minute = "2-digit"; second = "2-digit" }
            "short" -> { hour = "numeric"; minute = "2-digit" }
        }
        val anyField = listOf(weekday, year, month, day, hour, minute, second).any { it != null }
        if (!anyField) {
            if (kind != "time") { year = "numeric"; month = "numeric"; day = "numeric" }
            if (kind != "date") { hour = "numeric"; minute = "2-digit"; second = "2-digit" }
        }
        val parts = mutableListOf<String>()
        val datePart = buildString {
            val y = when (year) { "2-digit" -> (z.year % 100).toString().padStart(2, '0'); null -> null; else -> z.year.toString() }
            val d = when (day) { "2-digit" -> z.dayOfMonth.toString().padStart(2, '0'); null -> null; else -> z.dayOfMonth.toString() }
            when (month) {
                "long", "short", "narrow" -> {
                    val name = when (month) {
                        "long" -> z.month.getDisplayName(TextStyle.FULL, Locale.US)
                        "short" -> z.month.getDisplayName(TextStyle.SHORT, Locale.US)
                        else -> z.month.getDisplayName(TextStyle.NARROW, Locale.US)
                    }
                    append(name)
                    if (d != null) append(" ").append(d)
                    if (y != null) append(if (d != null) ", " else " ").append(y)
                }
                "numeric", "2-digit" -> {
                    val m = if (month == "2-digit") z.monthValue.toString().padStart(2, '0') else z.monthValue.toString()
                    append(listOfNotNull(m, d, y).joinToString("/"))
                }
                else -> append(listOfNotNull(d, y).joinToString(" "))
            }
        }
        val weekdayPart = weekday?.let {
            when (it) {
                "long" -> z.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
                "narrow" -> z.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.US)
                else -> z.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
            }
        }
        val dateText = listOfNotNull(weekdayPart, datePart.takeIf { it.isNotEmpty() }).joinToString(", ")
        if (dateText.isNotEmpty()) parts += dateText
        if (hour != null || minute != null || second != null) {
            val h = if (hour12) (z.hour % 12).let { if (it == 0) 12 else it } else z.hour
            val time = buildString {
                if (hour != null) append(if (hour == "2-digit" || !hour12) h.toString().padStart(2, '0') else h.toString())
                if (minute != null) { if (isNotEmpty()) append(':'); append(z.minute.toString().padStart(2, '0')) }
                if (second != null) { if (isNotEmpty()) append(':'); append(z.second.toString().padStart(2, '0')) }
                if (hour12 && hour != null) append(if (z.hour < 12) " AM" else " PM")
            }
            parts += time
        }
        if (opt("timeZoneName") != null) parts += tz.getDisplayName(TextStyle.SHORT, Locale.US)
        return parts.joinToString(", ")
    }
}
