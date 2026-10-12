package com.localfirst.assistant.platform

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/** JS-backed implementations of the platform surface the shared engine uses. */

actual object PlatformSystem {
    actual fun currentTimeMillis(): Long = js("Date.now()").unsafeCast<Double>().toLong()
    actual fun nanoTime(): Long =
        js("(typeof performance!=='undefined'&&performance.now)?performance.now():Date.now()").unsafeCast<Double>().toLong() * 1_000_000L
    actual fun identityHashCode(obj: Any): Int {
        val d = obj.unsafeCast<dynamic>()
        val existing = identityRegistry.get(d)
        if (existing != null) return (existing.unsafeCast<Int>() * 2654435761u.toInt()).let { it xor (it ushr 16) }
        identityCounter += 1
        identityRegistry.set(d, identityCounter)
        return (identityCounter * 2654435761u.toInt()).let { it xor (it ushr 16) }
    }
}
private val identityRegistry = js("new WeakMap()").unsafeCast<dynamic>()
private var identityCounter = 0

actual object PlatformMath {
    actual val PI: Double = js("Math.PI").unsafeCast<Double>()
    actual val E: Double = js("Math.E").unsafeCast<Double>()
    actual fun sqrt(v: Double) = js("Math.sqrt(v)").unsafeCast<Double>()
    actual fun cbrt(v: Double) = js("Math.cbrt(v)").unsafeCast<Double>()
    actual fun exp(v: Double) = js("Math.exp(v)").unsafeCast<Double>()
    actual fun expm1(v: Double) = js("Math.expm1(v)").unsafeCast<Double>()
    actual fun log(v: Double) = js("Math.log(v)").unsafeCast<Double>()
    actual fun log10(v: Double) = js("Math.log10(v)").unsafeCast<Double>()
    actual fun log1p(v: Double) = js("Math.log1p(v)").unsafeCast<Double>()
    actual fun sin(v: Double) = js("Math.sin(v)").unsafeCast<Double>()
    actual fun cos(v: Double) = js("Math.cos(v)").unsafeCast<Double>()
    actual fun tan(v: Double) = js("Math.tan(v)").unsafeCast<Double>()
    actual fun asin(v: Double) = js("Math.asin(v)").unsafeCast<Double>()
    actual fun acos(v: Double) = js("Math.acos(v)").unsafeCast<Double>()
    actual fun atan(v: Double) = js("Math.atan(v)").unsafeCast<Double>()
    actual fun sinh(v: Double) = js("Math.sinh(v)").unsafeCast<Double>()
    actual fun cosh(v: Double) = js("Math.cosh(v)").unsafeCast<Double>()
    actual fun tanh(v: Double) = js("Math.tanh(v)").unsafeCast<Double>()
    actual fun ceil(v: Double) = js("Math.ceil(v)").unsafeCast<Double>()
    actual fun floor(v: Double) = js("Math.floor(v)").unsafeCast<Double>()
    actual fun abs(v: Double) = js("Math.abs(v)").unsafeCast<Double>()
    actual fun sign(v: Double) = js("Math.sign(v)").unsafeCast<Double>()
    actual fun pow(a: Double, b: Double) = js("Math.pow(a, b)").unsafeCast<Double>()
    actual fun atan2(y: Double, x: Double) = js("Math.atan2(y, x)").unsafeCast<Double>()
    actual fun ulp(v: Double) = js("Math.ulp ? Math.ulp(v) : Number.MIN_VALUE").unsafeCast<Double>()
}

actual object PlatformCharacter {
    actual fun digit(c: Char, radix: Int): Int {
        val v = js("parseInt(String.fromCharCode(c.code), radix)").unsafeCast<Double>()
        return if (v.isNaN()) -1 else v.toInt()
    }
    actual fun toChars(codePoint: Int): CharArray =
        if (codePoint < 0x10000) charArrayOf(codePoint.toChar())
        else charArrayOf(
            (0xD800 + ((codePoint - 0x10000) shr 10)).toChar(),
            (0xDC00 + ((codePoint - 0x10000) and 0x3FF)).toChar(),
        )
}

actual class PlatformBigDecimal internal constructor(internal val value: Double, internal val scale: Int) {
    actual fun setScale(scale: Int, @Suppress("UNUSED_PARAMETER") mode: PlatformRoundingMode): PlatformBigDecimal {
        val factor = js("Math.pow(10, scale)").unsafeCast<Double>()
        return PlatformBigDecimal(js("Math.round((value + Number.EPSILON) * factor) / factor").unsafeCast<Double>(), scale)
    }
    actual fun round(context: PlatformMathContext): PlatformBigDecimal {
        if (value == 0.0) return PlatformBigDecimal(0.0, 0)
        val e = floor(js("Math.log10(Math.abs(value))").unsafeCast<Double>()).toInt()
        val factor = js("Math.pow(10, context.precision - 1 - e)").unsafeCast<Double>()
        val r = js("Math.round(value * factor) / factor").unsafeCast<Double>()
        return PlatformBigDecimal(r, naturalScale(r))
    }
    actual fun stripTrailingZeros(): PlatformBigDecimal = PlatformBigDecimal(value, 0)
    actual fun toPlainString(): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (scale > 0) return js("value.toFixed(scale)").unsafeCast<String>()
        if (value == floor(value) && abs(value) < 1e21) return value.toLong().toString()
        return js("String(value)").unsafeCast<String>()
    }
    actual fun precision(): Int {
        val digits = toPlainString().replace("-", "").replace(".", "").trimStart('0').trimEnd('0')
        return digits.ifEmpty { "0" }.length
    }
    actual fun scale(): Int = scale
    actual fun unscaledValueDigits(): String = toPlainString().replace("-", "").replace(".", "")
}
actual fun PlatformBigDecimal(value: Double): PlatformBigDecimal = PlatformBigDecimal(value, naturalScale(value))
actual fun PlatformBigDecimal(text: String): PlatformBigDecimal = PlatformBigDecimal(text.toDouble(), naturalScale(text.toDouble()))
actual class PlatformMathContext actual constructor(actual val precision: Int, actual val roundingMode: PlatformRoundingMode)

private fun naturalScale(v: Double): Int {
    if (v.isNaN() || v.isInfinite() || v == floor(v)) return 0
    val s = js("String(v)").unsafeCast<String>()
    val dot = s.indexOf('.')
    return if (dot < 0) 0 else s.length - dot - 1
}

actual class PlatformDecimalFormatSymbols actual constructor()
actual class PlatformDecimalFormat actual constructor(@Suppress("UNUSED_PARAMETER") pattern: String, @Suppress("UNUSED_PARAMETER") symbols: PlatformDecimalFormatSymbols) {
    actual var minimumFractionDigits: Int = 0
    actual var maximumFractionDigits: Int = 3
    actual var isGroupingUsed: Boolean = true
    actual fun format(v: Double): String {
        val opts = js("({minimumFractionDigits: minimumFractionDigits, maximumFractionDigits: maximumFractionDigits, useGrouping: isGroupingUsed})")
        return js("v.toLocaleString('en-US', opts)").unsafeCast<String>()
    }
}
actual object PlatformCollator {
    actual fun compare(a: String, b: String): Int {
        val r = js("a.localeCompare(b, 'en')").unsafeCast<Double>()
        return if (r < 0) -1 else if (r > 0) 1 else 0
    }
}
actual object PlatformNormalizer {
    actual fun normalize(s: String, form: String): String = js("s.normalize(form)").unsafeCast<String>()
}

actual class PlatformRandom actual constructor() {
    actual fun nextDouble(): Double = js("Math.random()").unsafeCast<Double>()
}
actual object PlatformCurrency {
    actual fun defaultFractionDigits(code: String): Int = when (code) {
        "JPY", "KRW", "VND", "IDR", "CLP", "PYG", "ISK", "KMF", "DJF", "GNF", "VUV", "XAF", "XOF", "XPF" -> 0
        "BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND" -> 3
        else -> 2
    }
    actual fun symbol(code: String): String = when (code) {
        "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; "JPY" -> "¥"; "INR" -> "₹"; "CNY" -> "¥"; "KRW" -> "₩"
        "CAD" -> "$"; "AUD" -> "$"; "MXN" -> "$"; "BRL" -> "R$"; "CHF" -> "CHF"; "RUB" -> "₽"; "TRY" -> "₺"
        else -> code
    }
}
actual class PlatformIdentityHashMap<K, V> actual constructor() {
    private val map = js("new Map()").unsafeCast<dynamic>()
    actual fun put(key: K, value: V): V? { val prev = map.get(key); map.set(key, value); return prev.unsafeCast<V?>() }
    actual fun containsKey(key: K): Boolean = map.has(key).unsafeCast<Boolean>()
    actual fun remove(key: K): V? { val prev = map.get(key); map.delete(key); return prev.unsafeCast<V?>() }
    actual fun clear() { map.clear() }
}
actual fun <K> newIdentitySet(map: PlatformIdentityHashMap<K, Boolean>): MutableSet<K> = object : MutableSet<K> {
    private val backing = LinkedHashMap<K, Boolean>()
    override val size: Int get() = backing.size
    override fun contains(element: K): Boolean = backing.containsKey(element)
    override fun containsAll(elements: Collection<K>): Boolean = elements.all { backing.containsKey(it) }
    override fun isEmpty(): Boolean = backing.isEmpty()
    override fun iterator(): MutableIterator<K> = backing.keys.iterator()
    override fun add(element: K): Boolean = if (backing.containsKey(element)) false else { backing[element] = true; true }
    override fun addAll(elements: Collection<K>): Boolean { var c = false; elements.forEach { c = add(it) || c }; return c }
    override fun clear() { backing.clear() }
    override fun remove(element: K): Boolean = backing.remove(element) != null
    override fun removeAll(elements: Collection<K>): Boolean { var c = false; elements.forEach { c = remove(it) || c }; return c }
    override fun retainAll(elements: Collection<K>): Boolean { var c = false; backing.keys.filter { it !in elements }.forEach { remove(it); c = true }; return c }
}

actual object PlatformNet {
    actual fun encodeUrl(s: String): String = js("encodeURIComponent(s)").unsafeCast<String>()
    actual fun decodeUrl(s: String): String = js("decodeURIComponent(s)").unsafeCast<String>()
}

actual class PlatformUri actual constructor(url: String) {
    private val u: dynamic = js("new URL(url)")
    actual val scheme: String? get() = runCatching { u.protocol.unsafeCast<String>().removeSuffix(":") }.getOrNull()
    actual val host: String? get() = runCatching { u.hostname.unsafeCast<String>() }.getOrNull()
    actual val port: Int get() = runCatching { val p = u.port.unsafeCast<String>(); if (p.isEmpty()) -1 else p.toInt() }.getOrDefault(-1)
    actual val rawUserInfo: String? get() = runCatching { val x = u.username.unsafeCast<String>(); if (x.isEmpty()) null else x }.getOrNull()
    actual val rawPath: String get() = runCatching { u.pathname.unsafeCast<String>() }.getOrDefault("")
    actual val rawQuery: String? get() = runCatching { val q = u.search.unsafeCast<String>(); if (q.isEmpty()) null else q.removePrefix("?") }.getOrNull()
}

actual object PlatformMessageDigest {
    actual fun sha1(input: ByteArray): ByteArray = Sha1.digest(input)
}

actual fun formatString(pattern: String, vararg args: Any?): String = Printf.format(pattern, args.toList())
actual fun encodeUtf8(s: String): ByteArray {
    val enc = js("new TextEncoder()")
    val u8 = enc.encode(s)
    return u8.unsafeCast<ByteArray>()
}
actual fun codePointAt(s: String, index: Int): Int = js("s.codePointAt(index)").unsafeCast<Int>()
actual fun codePoints(s: String): IntArray {
    val out = mutableListOf<Int>()
    var i = 0
    while (i < s.length) {
        val c = s[i].code
        if (c in 0xD800..0xDBFF && i + 1 < s.length) {
            val lo = s[i + 1].code
            if (lo in 0xDC00..0xDFFF) { out += 0x10000 + ((c - 0xD800) shl 10) + (lo - 0xDC00); i += 2; continue }
        }
        out += c; i += 1
    }
    return out.toIntArray()
}
actual fun appendCodePoint(sb: StringBuilder, codePoint: Int): StringBuilder {
    if (codePoint < 0x10000) sb.append(codePoint.toChar())
    else {
        sb.append((0xD800 + ((codePoint - 0x10000) shr 10)).toChar())
        sb.append((0xDC00 + ((codePoint - 0x10000) and 0x3FF)).toChar())
    }
    return sb
}

/** `@Volatile` is a JVM cross-thread hint; the browser interpreter is single-threaded. */
actual annotation class PlatformVolatile actual constructor()

/** The interpreter catches this on deep recursion; JS surfaces stack exhaustion as a RangeError. */
actual class PlatformStackOverflowError : Throwable()

actual object PlatformTime {
    actual var zone: String = js("(typeof Intl!=='undefined')?Intl.DateTimeFormat().resolvedOptions().timeZone:'UTC'").unsafeCast<String>()
    actual fun nowMillis(): Long = js("Date.now()").unsafeCast<Double>().toLong()

    private fun d(t: Double): dynamic = js("new Date(t)")

    actual fun isoString(t: Double): String = if (t.isNaN()) "Invalid Date" else d(t).toISOString().unsafeCast<String>()

    actual fun displayString(t: Double): String {
        if (t.isNaN()) return "Invalid Date"
        val dt = d(t)
        val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val off = -dt.getTimezoneOffset().unsafeCast<Int>()
        val sign = if (off >= 0) "+" else "-"
        val o = abs(off)
        return "${days[dt.getDay().unsafeCast<Int>()]} ${months[dt.getMonth().unsafeCast<Int>()]} " +
            "${dt.getDate().unsafeCast<Int>().toString().padStart(2, '0')} ${dt.getFullYear().unsafeCast<Int>()} " +
            "${pad2(dt.getHours().unsafeCast<Int>())}:${pad2(dt.getMinutes().unsafeCast<Int>())}:${pad2(dt.getSeconds().unsafeCast<Int>())} " +
            "GMT$sign${pad2(o / 60)}${pad2(o % 60)}"
    }

    actual fun dateString(t: Double): String = if (t.isNaN()) "Invalid Date" else d(t).toDateString().unsafeCast<String>()
    actual fun timeString(t: Double): String = if (t.isNaN()) "Invalid Date" else d(t).toTimeString().unsafeCast<String>()
    actual fun utcString(t: Double): String = if (t.isNaN()) "Invalid Date" else d(t).toUTCString().unsafeCast<String>()

    actual fun parse(text: String): Double {
        val s = text.trim()
        val iso = js("Date.parse(s)").unsafeCast<Double>()
        return if (iso.isNaN()) Double.NaN else iso
    }

    actual fun format(t: Double, options: Map<String, String>, kind: String): String {
        if (t.isNaN()) return "Invalid Date"
        val opts = js("({})")
        for ((k, v) in options) opts[k] = v
        if (kind == "date" && opts.dateStyle == null && opts.year == null) opts.dateStyle = "medium"
        if (kind == "time" && opts.timeStyle == null && opts.hour == null) opts.timeStyle = "medium"
        return js("new Date(t).toLocaleString('en-US', opts)").unsafeCast<String>()
    }

    actual fun field(t: Double, utc: Boolean, field: String): Int {
        val dt = d(t)
        val u = if (utc) "UTC" else ""
        return when (field) {
            "FullYear" -> js("dt['get'+u+'FullYear']()").unsafeCast<Int>()
            "Month" -> js("dt['get'+u+'Month']()").unsafeCast<Int>()
            "Date" -> js("dt['get'+u+'Date']()").unsafeCast<Int>()
            "Day" -> js("dt['get'+u+'Day']()").unsafeCast<Int>()
            "Hours" -> js("dt['get'+u+'Hours']()").unsafeCast<Int>()
            "Minutes" -> js("dt['get'+u+'Minutes']()").unsafeCast<Int>()
            "Seconds" -> js("dt['get'+u+'Seconds']()").unsafeCast<Int>()
            "Milliseconds" -> js("dt['get'+u+'Milliseconds']()").unsafeCast<Int>()
            "TimezoneOffset" -> -dt.getTimezoneOffset().unsafeCast<Int>()
            else -> 0
        }
    }

    actual fun setFields(t: Double, utc: Boolean, year: Double?, month: Double?, day: Double?, hour: Double?, min: Double?, sec: Double?, ms: Double?): Double {
        val dt = d(t)
        val u = if (utc) "UTC" else ""
        if (year != null) js("dt['set'+u+'FullYear'](year)")
        if (month != null) js("dt['set'+u+'Month'](month)")
        if (day != null) js("dt['set'+u+'Date'](day)")
        if (hour != null) js("dt['set'+u+'Hours'](hour)")
        if (min != null) js("dt['set'+u+'Minutes'](min)")
        if (sec != null) js("dt['set'+u+'Seconds'](sec)")
        if (ms != null) js("dt['set'+u+'Milliseconds'](ms)")
        return dt.getTime().unsafeCast<Double>()
    }

    actual fun fromParts(parts: List<Double>, utc: Boolean): Double {
        if (parts.any { it.isNaN() || it.isInfinite() }) return Double.NaN
        val year = parts.getOrElse(0) { 1970.0 }.let { if (it in 0.0..99.0) 1900 + it else it }.toInt()
        val month = parts.getOrElse(1) { 0.0 }.toInt()
        val day = parts.getOrElse(2) { 1.0 }.toInt()
        val hour = parts.getOrElse(3) { 0.0 }.toInt()
        val min = parts.getOrElse(4) { 0.0 }.toInt()
        val sec = parts.getOrElse(5) { 0.0 }.toInt()
        val ms = parts.getOrElse(6) { 0.0 }.toInt()
        val dt = if (utc) js("new Date(Date.UTC(year, month, day, hour, min, sec, ms))") else js("new Date(year, month, day, hour, min, sec, ms)")
        return dt.getTime().unsafeCast<Double>()
    }
}

private fun pad2(v: Int): String = v.toString().padStart(2, '0')
