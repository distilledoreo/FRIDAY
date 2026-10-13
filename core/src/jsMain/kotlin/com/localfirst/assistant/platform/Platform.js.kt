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
    actual fun round(v: Double): Long = js("Math.round(v)").unsafeCast<Double>().toLong()
    actual fun abs(v: Double) = js("Math.abs(v)").unsafeCast<Double>()
    actual fun sign(v: Double) = js("Math.sign(v)").unsafeCast<Double>()
    actual fun pow(a: Double, b: Double) = js("Math.pow(a, b)").unsafeCast<Double>()
    actual fun atan2(y: Double, x: Double) = js("Math.atan2(y, x)").unsafeCast<Double>()
    actual fun ulp(v: Double): Double {
        if (v.isNaN()) return Double.NaN
        if (v.isInfinite()) return Double.POSITIVE_INFINITY
        val result = js("Math.pow(2, Math.floor(Math.log2(Math.abs(v))) - 52)").unsafeCast<Double>()
        return maxOf(result, js("Number.MIN_VALUE").unsafeCast<Double>())
    }
}

actual object PlatformCharacter {
    actual fun digit(c: Char, radix: Int): Int {
        val charCode = c.code
        val v = js("parseInt(String.fromCharCode(charCode), radix)").unsafeCast<Double>()
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
    private var forceScale = false
    actual fun setScale(scale: Int, @Suppress("UNUSED_PARAMETER") mode: PlatformRoundingMode): PlatformBigDecimal = PlatformBigDecimal(value, scale).also { it.forceScale = true }
    actual fun round(context: PlatformMathContext): PlatformBigDecimal {
        val number = value
        val precision = context.precision
        val rounded = js("Number(number.toPrecision(precision))").unsafeCast<Double>()
        return PlatformBigDecimal(rounded, naturalScale(rounded))
    }
    actual fun stripTrailingZeros(): PlatformBigDecimal = PlatformBigDecimal(value, 0)
    actual fun toPlainString(): String {
        val number = value
        val places = scale
        if (number.isNaN() || number.isInfinite()) return number.toString()
        if (places > 0 || forceScale) return js("number.toFixed(places)").unsafeCast<String>()
        if (number == floor(number) && abs(number) < 1e21) return js("number.toFixed(0)").unsafeCast<String>()
        return js("String(number)").unsafeCast<String>()
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
        val opts = js("({})")
        opts.minimumFractionDigits = minimumFractionDigits
        opts.maximumFractionDigits = maximumFractionDigits
        opts.useGrouping = isGroupingUsed
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
actual open class PlatformStacklessException actual constructor() : RuntimeException()

actual object PlatformTime {
    actual var zone: String = js("Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'").unsafeCast<String>()
    actual fun nowMillis(): Long = js("Date.now()").unsafeCast<Double>().toLong()
    private fun d(t: Double): dynamic = js("new Date(t)")
    private fun localWall(t: Double): Double {
        val requestedZone = zone
        val parts = js("new Intl.DateTimeFormat('en-US', {timeZone: requestedZone, year:'numeric',month:'numeric',day:'numeric',hour:'numeric',minute:'numeric',second:'numeric',hourCycle:'h23'}).formatToParts(new Date(t))")
        val fields = mutableMapOf<String, Int>()
        for (i in 0 until parts.length.unsafeCast<Int>()) {
            val part = parts[i]; val text = part.value.unsafeCast<String>()
            text.toIntOrNull()?.let { fields[part.type.unsafeCast<String>()] = it }
        }
        val year = fields.getValue("year"); val month = fields.getValue("month") - 1; val day = fields.getValue("day")
        val hour = fields.getValue("hour") % 24; val minute = fields.getValue("minute"); val second = fields.getValue("second")
        val ms = d(t).getUTCMilliseconds().unsafeCast<Int>()
        return js("Date.UTC(year, month, day, hour, minute, second, ms)").unsafeCast<Double>()
    }
    private fun instant(wall: Double): Double {
        var result = wall
        repeat(3) { result = wall - (localWall(result) - result) }
        return result
    }
    actual fun isoString(t: Double): String = if (t.isNaN()) "Invalid Date" else d(t).toISOString().unsafeCast<String>()
    actual fun displayString(t: Double): String {
        if (t.isNaN()) return "Invalid Date"
        val wall = localWall(t); val dt = d(wall)
        val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val offset = ((wall - t) / 60000).toInt(); val sign = if (offset >= 0) "+" else "-"; val o = abs(offset)
        return "${days[dt.getUTCDay().unsafeCast<Int>()]} ${months[dt.getUTCMonth().unsafeCast<Int>()]} " +
            "${pad2(dt.getUTCDate().unsafeCast<Int>())} ${dt.getUTCFullYear().unsafeCast<Int>()} " +
            "${pad2(dt.getUTCHours().unsafeCast<Int>())}:${pad2(dt.getUTCMinutes().unsafeCast<Int>())}:${pad2(dt.getUTCSeconds().unsafeCast<Int>())} GMT$sign${pad2(o/60)}${pad2(o%60)}"
    }
    actual fun dateString(t: Double): String = if (t.isNaN()) "Invalid Date" else displayString(t).split(' ').take(4).joinToString(" ")
    actual fun timeString(t: Double): String = if (t.isNaN()) "Invalid Date" else displayString(t).split(' ').drop(4).joinToString(" ")
    actual fun utcString(t: Double): String = if (t.isNaN()) "Invalid Date" else d(t).toUTCString().unsafeCast<String>()
    actual fun parse(text: String): Double {
        val s = text.trim()
        val local = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,3}))?)?$").matchEntire(s)
        if (local != null) {
            val g = local.groupValues
            return fromParts(listOf(g[1].toDouble(),g[2].toDouble()-1,g[3].toDouble(),g[4].toDouble(),g[5].toDouble(),g[6].toDoubleOrNull() ?: 0.0,g[7].padEnd(3,'0').toDoubleOrNull() ?: 0.0), false)
        }
        return js("Date.parse(s)").unsafeCast<Double>()
    }
    actual fun format(t: Double, options: Map<String, String>, kind: String): String {
        if (t.isNaN()) return "Invalid Date"
        val opts = js("({})"); opts.timeZone = zone
        for ((key,value) in options) opts[key] = if (key == "hour12") value == "true" else value
        val dt = d(t)
        return when(kind) { "date" -> dt.toLocaleDateString("en-US",opts); "time" -> dt.toLocaleTimeString("en-US",opts); else -> dt.toLocaleString("en-US",opts) }.unsafeCast<String>()
    }
    actual fun field(t: Double, utc: Boolean, field: String): Int {
        if (field == "TimezoneOffset") return ((t - localWall(t)) / 60000).toInt()
        val dt = d(if (utc) t else localWall(t))
        return when(field) {
            "FullYear" -> dt.getUTCFullYear(); "Month" -> dt.getUTCMonth(); "Date" -> dt.getUTCDate(); "Day" -> dt.getUTCDay()
            "Hours" -> dt.getUTCHours(); "Minutes" -> dt.getUTCMinutes(); "Seconds" -> dt.getUTCSeconds(); "Milliseconds" -> dt.getUTCMilliseconds(); else -> 0
        }.unsafeCast<Int>()
    }
    actual fun setFields(t: Double, utc: Boolean, year: Double?, month: Double?, day: Double?, hour: Double?, min: Double?, sec: Double?, ms: Double?): Double {
        val dt = d(if (utc) t else localWall(t))
        if (year != null) dt.setUTCFullYear(year); if (month != null) dt.setUTCMonth(month); if (day != null) dt.setUTCDate(day)
        if (hour != null) dt.setUTCHours(hour); if (min != null) dt.setUTCMinutes(min); if (sec != null) dt.setUTCSeconds(sec); if (ms != null) dt.setUTCMilliseconds(ms)
        val result = dt.getTime().unsafeCast<Double>()
        return if (utc) result else instant(result)
    }
    actual fun fromParts(parts: List<Double>, utc: Boolean): Double {
        if (parts.any { !it.isFinite() }) return Double.NaN
        val year = parts.getOrElse(0){1970.0}.toInt(); val month = parts.getOrElse(1){0.0}.toInt(); val day = parts.getOrElse(2){1.0}.toInt()
        val hour = parts.getOrElse(3){0.0}.toInt(); val minute = parts.getOrElse(4){0.0}.toInt(); val second = parts.getOrElse(5){0.0}.toInt(); val ms = parts.getOrElse(6){0.0}.toInt()
        val wall = js("Date.UTC(year,month,day,hour,minute,second,ms)").unsafeCast<Double>()
        return if (utc) wall else instant(wall)
    }
}

private fun pad2(v: Int): String = v.toString().padStart(2, '0')

/** Insertion-ordered map re-sorted by key on each insert (JS has no TreeMap). */
actual fun <K : Comparable<K>, V> sortedMapOf(): MutableMap<K, V> = SortedMapImpl()

private class SortedMapImpl<K : Comparable<K>, V> : MutableMap<K, V> {
    private val keyList = mutableListOf<K>()
    private val valMap = LinkedHashMap<K, V>()
    override val size: Int get() = valMap.size
    override fun containsKey(key: K) = valMap.containsKey(key)
    override fun containsValue(value: V) = valMap.containsValue(value)
    override fun get(key: K): V? = valMap[key]
    override fun isEmpty() = valMap.isEmpty()
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = keyList.map { MutableEntry(it, valMap[it] as V) }.toMutableSet()
    override val keys: MutableSet<K> get() = keyList.toMutableSet()
    override val values: MutableCollection<V> get() = keyList.mapNotNull { valMap[it] }.toMutableList()
    override fun put(key: K, value: V): V? {
        val prev = valMap.put(key, value)
        if (prev == null) { keyList.add(key); keyList.sortWith { a, b -> a.compareTo(b) } }
        return prev
    }
    override fun remove(key: K): V? { val prev = valMap.remove(key); keyList.remove(key); return prev }
    override fun putAll(from: Map<out K, V>) { from.forEach { put(it.key, it.value) } }
    override fun clear() { keyList.clear(); valMap.clear() }
}
private data class MutableEntry<K, V>(override val key: K, override val value: V) : MutableMap.MutableEntry<K, V> {
    override fun setValue(newValue: V): V { val old = value; return old }
}

actual fun Throwable.className(): String = js("(this && this.constructor && this.constructor.name) ? this.constructor.name : 'Error'").unsafeCast<String>()

/** JS RegExp has flags on its constructor, rather than JVM inline (?ims) groups. */
actual fun platformRegex(pattern: String, options: Set<RegexOption>): Regex {
    var source = pattern
    val flags = options.toMutableSet()
    var dotAll = false
    while (source.startsWith("(?")) {
        val end = source.indexOf(')')
        if (end < 0) break
        val inline = source.substring(2, end)
        if (inline.isEmpty() || inline.any { it !in "ims" }) break
        if ('i' in inline) flags.add(RegexOption.IGNORE_CASE)
        if ('m' in inline) flags.add(RegexOption.MULTILINE)
        if ('s' in inline) dotAll = true
        source = source.substring(end + 1)
    }
    val translated = StringBuilder()
    var escaped = false
    var characterClass = false
    for (c in source) {
        if (escaped) {
            if (c == 'z' && !characterClass) translated.append("(?![\\s\\S])") else translated.append('\\').append(c)
            escaped = false
        } else when(c) {
            '\\' -> escaped = true
            '[' -> { characterClass = true; translated.append(c) }
            ']' -> { characterClass = false; translated.append(c) }
            '.' -> translated.append(if (dotAll && !characterClass) "[\\s\\S]" else ".")
            else -> translated.append(c)
        }
    }
    if (escaped) translated.append('\\')
    return Regex(translated.toString(), flags)
}
