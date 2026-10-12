package com.localfirst.assistant.platform

import java.text.Collator
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Currency
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Random
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

actual object PlatformSystem {
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()
    actual fun nanoTime(): Long = System.nanoTime()
    actual fun identityHashCode(obj: Any): Int = System.identityHashCode(obj)
}

actual object PlatformMath {
    actual val PI: Double = Math.PI
    actual val E: Double = Math.E
    actual fun sqrt(v: Double) = Math.sqrt(v)
    actual fun cbrt(v: Double) = Math.cbrt(v)
    actual fun exp(v: Double) = Math.exp(v)
    actual fun expm1(v: Double) = Math.expm1(v)
    actual fun log(v: Double) = Math.log(v)
    actual fun log10(v: Double) = Math.log10(v)
    actual fun log1p(v: Double) = Math.log1p(v)
    actual fun sin(v: Double) = Math.sin(v)
    actual fun cos(v: Double) = Math.cos(v)
    actual fun tan(v: Double) = Math.tan(v)
    actual fun asin(v: Double) = Math.asin(v)
    actual fun acos(v: Double) = Math.acos(v)
    actual fun atan(v: Double) = Math.atan(v)
    actual fun sinh(v: Double) = Math.sinh(v)
    actual fun cosh(v: Double) = Math.cosh(v)
    actual fun tanh(v: Double) = Math.tanh(v)
    actual fun ceil(v: Double) = Math.ceil(v)
    actual fun floor(v: Double) = Math.floor(v)
    actual fun round(v: Double): Long = Math.round(v)
    actual fun abs(v: Double) = Math.abs(v)
    actual fun sign(v: Double) = Math.signum(v)
    actual fun pow(a: Double, b: Double) = Math.pow(a, b)
    actual fun atan2(y: Double, x: Double) = Math.atan2(y, x)
    actual fun ulp(v: Double) = Math.ulp(v)
}

actual object PlatformCharacter {
    actual fun digit(c: Char, radix: Int): Int = Character.digit(c, radix)
    actual fun toChars(codePoint: Int): CharArray = Character.toChars(codePoint)
}

actual class PlatformBigDecimal internal constructor(private val delegate: java.math.BigDecimal) {
    actual fun setScale(scale: Int, mode: PlatformRoundingMode): PlatformBigDecimal =
        PlatformBigDecimal(delegate.setScale(scale, java.math.RoundingMode.valueOf(mode.name)))
    actual fun round(context: PlatformMathContext): PlatformBigDecimal =
        PlatformBigDecimal(delegate.round(java.math.MathContext(context.precision, java.math.RoundingMode.valueOf(context.roundingMode.name))))
    actual fun stripTrailingZeros(): PlatformBigDecimal = PlatformBigDecimal(delegate.stripTrailingZeros())
    actual fun toPlainString(): String = delegate.toPlainString()
    actual fun precision(): Int = delegate.precision()
    actual fun scale(): Int = delegate.scale()
    actual fun unscaledValueDigits(): String = delegate.unscaledValue().abs().toString()
}
actual fun PlatformBigDecimal(value: Double): PlatformBigDecimal = PlatformBigDecimal(java.math.BigDecimal(value))
actual fun PlatformBigDecimal(text: String): PlatformBigDecimal = PlatformBigDecimal(java.math.BigDecimal(text))
actual class PlatformMathContext actual constructor(actual val precision: Int, actual val roundingMode: PlatformRoundingMode)

actual class PlatformDecimalFormatSymbols actual constructor() {
    internal val delegate = DecimalFormatSymbols(Locale.US)
}
actual class PlatformDecimalFormat actual constructor(pattern: String, symbols: PlatformDecimalFormatSymbols) {
    private val delegate = DecimalFormat(pattern, symbols.delegate)
    actual var minimumFractionDigits: Int = 0
        set(value) { field = value; delegate.minimumFractionDigits = value }
    actual var maximumFractionDigits: Int = 3
        set(value) { field = value; delegate.maximumFractionDigits = value }
    actual var isGroupingUsed: Boolean = true
        set(value) { field = value; delegate.isGroupingUsed = value }
    actual fun format(v: Double): String = delegate.format(v)
}
actual object PlatformCollator {
    private val collator = Collator.getInstance(Locale.US)
    actual fun compare(a: String, b: String): Int = collator.compare(a, b)
}
actual object PlatformNormalizer {
    actual fun normalize(s: String, form: String): String =
        Normalizer.normalize(s, Normalizer.Form.valueOf(form))
}

actual class PlatformRandom actual constructor() {
    private val delegate = Random()
    actual fun nextDouble(): Double = delegate.nextDouble()
}
actual object PlatformCurrency {
    actual fun defaultFractionDigits(code: String): Int = Currency.getInstance(code).defaultFractionDigits
    actual fun symbol(code: String): String = Currency.getInstance(code).getSymbol(Locale.US)
}
actual class PlatformIdentityHashMap<K, V> actual constructor() {
    private val delegate = IdentityHashMap<K, V>()
    actual fun put(key: K, value: V): V? = delegate.put(key, value)
    actual fun containsKey(key: K): Boolean = delegate.containsKey(key)
    actual fun remove(key: K): V? = delegate.remove(key)
    actual fun clear() = delegate.clear()
}
actual fun <K> newIdentitySet(map: PlatformIdentityHashMap<K, Boolean>): MutableSet<K> {
    val set = java.util.Collections.newSetFromMap(IdentityHashMap<K, Boolean>())
    return set
}

actual object PlatformNet {
    actual fun encodeUrl(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
    actual fun decodeUrl(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
}

actual class PlatformUri actual constructor(url: String) {
    private val delegate = java.net.URI(url)
    actual val scheme: String? get() = delegate.scheme
    actual val host: String? get() = delegate.host
    actual val port: Int get() = delegate.port
    actual val rawUserInfo: String? get() = delegate.rawUserInfo
    actual val rawPath: String get() = delegate.rawPath
    actual val rawQuery: String? get() = delegate.rawQuery
}

actual object PlatformMessageDigest {
    actual fun sha1(input: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-1").digest(input)
}

actual fun formatString(pattern: String, vararg args: Any?): String = String.format(pattern, *args)
actual fun encodeUtf8(s: String): ByteArray = s.toByteArray()
actual fun codePointAt(s: String, index: Int): Int = s.codePointAt(index)
actual fun codePoints(s: String): IntArray = s.codePoints().toArray()
actual fun appendCodePoint(sb: StringBuilder, codePoint: Int): StringBuilder = sb.appendCodePoint(codePoint)

actual typealias PlatformVolatile = kotlin.jvm.Volatile
actual typealias PlatformStackOverflowError = java.lang.StackOverflowError

actual object PlatformTime {
    actual var zone: String = ZoneId.systemDefault().id
    actual fun nowMillis(): Long = System.currentTimeMillis()
    private fun zoneId(): ZoneId = ZoneId.of(zone)
    private fun zoned(t: Double, utc: Boolean = false): ZonedDateTime =
        Instant.ofEpochMilli(t.toLong()).atZone(if (utc) ZoneOffset.UTC else zoneId())

    actual fun isoString(t: Double): String {
        if (t.isNaN()) return "Invalid Date"
        return DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").format(zoned(t, utc = true))
    }

    actual fun displayString(t: Double): String {
        if (t.isNaN()) return "Invalid Date"
        val z = zoned(t)
        val offset = z.offset.totalSeconds / 60
        val sign = if (offset >= 0) "+" else "-"
        val o = abs(offset)
        return DateTimeFormatter.ofPattern("EEE MMM dd uuuu HH:mm:ss", Locale.US).format(z) +
            " GMT$sign${(o / 60).toString().padStart(2, '0')}${(o % 60).toString().padStart(2, '0')}"
    }

    actual fun dateString(t: Double): String {
        if (t.isNaN()) return "Invalid Date"
        return DateTimeFormatter.ofPattern("EEE MMM dd uuuu", Locale.US).format(zoned(t))
    }

    actual fun timeString(t: Double): String = displayString(t).substringAfter(' ').substringAfter(' ').substringAfter(' ').substringAfter(' ')

    actual fun utcString(t: Double): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(zoned(t, utc = true))

    actual fun parse(text: String): Double {
        val s = text.trim()
        runCatching { return Instant.parse(s).toEpochMilli().toDouble() }
        runCatching { return ZonedDateTime.parse(s).toInstant().toEpochMilli().toDouble() }
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli().toDouble() }
        runCatching { return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli().toDouble() }
        runCatching { return LocalDateTime.parse(s.replace(' ', 'T')).atZone(zoneId()).toInstant().toEpochMilli().toDouble() }
        val patterns = listOf("MMM d, uuuu", "MMMM d, uuuu", "MMM d uuuu", "MMMM d uuuu", "d MMM uuuu", "d MMMM uuuu", "M/d/uuuu", "EEE MMM dd uuuu", "EEE, MMM d, uuuu")
        for (pattern in patterns) runCatching { return LocalDate.parse(s, DateTimeFormatter.ofPattern(pattern, Locale.US)).atStartOfDay(zoneId()).toInstant().toEpochMilli().toDouble() }
        val withTime = listOf("MMM d, uuuu h:mm a", "MMMM d, uuuu h:mm a", "M/d/uuuu h:mm a", "MMM d, uuuu HH:mm", "M/d/uuuu, h:mm:ss a")
        for (pattern in withTime) runCatching { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(pattern, Locale.US)).atZone(zoneId()).toInstant().toEpochMilli().toDouble() }
        return Double.NaN
    }

    actual fun format(t: Double, options: Map<String, String>, kind: String): String {
        if (t.isNaN()) return "Invalid Date"
        fun opt(key: String) = options[key]
        val tz = opt("timeZone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zoneId()
        val z = Instant.ofEpochMilli(t.toLong()).atZone(tz)
        val dateStyle = opt("dateStyle")
        val timeStyle = opt("timeStyle")
        var weekday = opt("weekday"); var year = opt("year"); var month = opt("month"); var day = opt("day")
        var hour = opt("hour"); var minute = opt("minute"); var second = opt("second")
        val hour12 = opt("hour12")?.let { it == "true" } ?: (opt("hourCycle")?.let { it == "h12" || it == "h11" } ?: true)
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
                    append(name); if (d != null) append(" ").append(d); if (y != null) append(if (d != null) ", " else " ").append(y)
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

    actual fun field(t: Double, utc: Boolean, field: String): Int {
        val z = zoned(t, utc)
        return when (field) {
            "FullYear" -> z.year
            "Month" -> z.monthValue - 1
            "Date" -> z.dayOfMonth
            "Day" -> z.dayOfWeek.value % 7
            "Hours" -> z.hour
            "Minutes" -> z.minute
            "Seconds" -> z.second
            "Milliseconds" -> z.nano / 1_000_000
            "TimezoneOffset" -> -(zoned(t).offset.totalSeconds / 60)
            else -> 0
        }
    }

    actual fun setFields(t: Double, utc: Boolean, year: Double?, month: Double?, day: Double?, hour: Double?, min: Double?, sec: Double?, ms: Double?): Double {
        var z = zoned(t, utc)
        if (year != null) z = z.withYear(year.toInt())
        if (month != null) z = z.withMonth(1).plusMonths(month.toLong())
        if (day != null) z = z.withDayOfMonth(1).plusDays(day.toLong() - 1)
        if (hour != null) z = z.withHour(0).plusHours(hour.toLong())
        if (min != null) z = z.withMinute(0).plusMinutes(min.toLong())
        if (sec != null) z = z.withSecond(0).plusSeconds(sec.toLong())
        if (ms != null) z = z.withNano(0).plusNanos(ms.toLong() * 1_000_000)
        return z.toInstant().toEpochMilli().toDouble()
    }

    actual fun fromParts(parts: List<Double>, utc: Boolean): Double {
        if (parts.any { it.isNaN() || it.isInfinite() }) return Double.NaN
        val year = parts.getOrElse(0) { 1970.0 }.let { if (it in 0.0..99.0) 1900 + it else it }.toInt()
        val month = parts.getOrElse(1) { 0.0 }.toLong()
        val start = LocalDateTime.of(year, 1, 1, 0, 0).plusMonths(month)
            .plusDays(parts.getOrElse(2) { 1.0 }.toLong() - 1)
            .plusHours(parts.getOrElse(3) { 0.0 }.toLong())
            .plusMinutes(parts.getOrElse(4) { 0.0 }.toLong())
            .plusSeconds(parts.getOrElse(5) { 0.0 }.toLong())
            .plusNanos(parts.getOrElse(6) { 0.0 }.toLong() * 1_000_000)
        return start.atZone(if (utc) ZoneOffset.UTC else zoneId()).toInstant().toEpochMilli().toDouble()
    }
}

actual fun <K : Comparable<K>, V> sortedMapOf(): MutableMap<K, V> = java.util.TreeMap()

actual fun Throwable.className(): String = javaClass.simpleName

actual fun platformRegex(pattern: String, options: Set<RegexOption>): Regex = Regex(pattern, options)
