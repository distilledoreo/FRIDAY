package com.localfirst.assistant.platform

/**
 * Platform abstraction for the JVM/JS-only APIs the shared `genui` script engine
 * uses. `androidMain` delegates to `java.*`; `jsMain` delegates to JS built-ins.
 * Keeping this surface small lets the engine live in `commonMain` unchanged.
 */

/** Wall-clock and identity hashing (`java.lang.System`). */
expect object PlatformSystem {
    fun currentTimeMillis(): Long
    fun nanoTime(): Long
    fun identityHashCode(obj: Any): Int
}

/** `java.lang.Math` surface used by the sandbox. */
expect object PlatformMath {
    val PI: Double
    val E: Double
    fun sqrt(v: Double): Double
    fun cbrt(v: Double): Double
    fun exp(v: Double): Double
    fun expm1(v: Double): Double
    fun log(v: Double): Double
    fun log10(v: Double): Double
    fun log1p(v: Double): Double
    fun sin(v: Double): Double
    fun cos(v: Double): Double
    fun tan(v: Double): Double
    fun asin(v: Double): Double
    fun acos(v: Double): Double
    fun atan(v: Double): Double
    fun sinh(v: Double): Double
    fun cosh(v: Double): Double
    fun tanh(v: Double): Double
    fun ceil(v: Double): Double
    fun floor(v: Double): Double
    fun round(v: Double): Long
    fun abs(v: Double): Double
    fun sign(v: Double): Double
    fun pow(a: Double, b: Double): Double
    fun atan2(y: Double, x: Double): Double
    fun ulp(v: Double): Double
}

/** `java.lang.Character` helpers. */
expect object PlatformCharacter {
    fun digit(c: Char, radix: Int): Int
    fun toChars(codePoint: Int): CharArray
}

/** `java.math.BigDecimal` surface (Number#toFixed / toPrecision). */
expect class PlatformBigDecimal {
    fun setScale(scale: Int, mode: PlatformRoundingMode): PlatformBigDecimal
    fun round(context: PlatformMathContext): PlatformBigDecimal
    fun stripTrailingZeros(): PlatformBigDecimal
    fun toPlainString(): String
    fun precision(): Int
    fun scale(): Int
    fun unscaledValueDigits(): String
}
expect fun PlatformBigDecimal(value: Double): PlatformBigDecimal
expect fun PlatformBigDecimal(text: String): PlatformBigDecimal
expect class PlatformMathContext(precision: Int, roundingMode: PlatformRoundingMode) {
    val precision: Int
    val roundingMode: PlatformRoundingMode
}
enum class PlatformRoundingMode { HALF_UP, HALF_EVEN, UP, DOWN, CEILING, FLOOR, HALF_DOWN, UNNECESSARY }

/** `java.text` number formatting and normalisation. */
expect class PlatformDecimalFormatSymbols()
expect class PlatformDecimalFormat(pattern: String, symbols: PlatformDecimalFormatSymbols) {
    var minimumFractionDigits: Int
    var maximumFractionDigits: Int
    var isGroupingUsed: Boolean
    fun format(v: Double): String
}
expect object PlatformCollator {
    fun compare(a: String, b: String): Int
}
expect object PlatformNormalizer {
    fun normalize(s: String, form: String): String
}

/** `java.util` helpers. */
expect class PlatformRandom() {
    fun nextDouble(): Double
}
expect object PlatformCurrency {
    fun defaultFractionDigits(code: String): Int
    fun symbol(code: String): String
}
expect class PlatformIdentityHashMap<K, V>() {
    fun put(key: K, value: V): V?
    fun containsKey(key: K): Boolean
    fun remove(key: K): V?
    fun clear()
}
expect fun <K> newIdentitySet(map: PlatformIdentityHashMap<K, Boolean>): MutableSet<K>

/** `java.net` URL encoding. */
expect object PlatformNet {
    fun encodeUrl(s: String): String
    fun decodeUrl(s: String): String
}

/** `java.net.URI` parsing for grounding / citation checks. */
expect class PlatformUri(url: String) {
    val scheme: String?
    val host: String?
    val port: Int
    val rawUserInfo: String?
    val rawPath: String
    val rawQuery: String?
}

/** `java.security.MessageDigest` (SHA-1) for stable citation ids. */
expect object PlatformMessageDigest {
    fun sha1(input: ByteArray): ByteArray
}

/** `String.format` / `toByteArray` / `codePointAt` / `codePoints` helpers. */
expect fun formatString(pattern: String, vararg args: Any?): String
expect fun encodeUtf8(s: String): ByteArray
expect fun codePointAt(s: String, index: Int): Int
expect fun codePoints(s: String): IntArray
expect fun appendCodePoint(sb: StringBuilder, codePoint: Int): StringBuilder

/** Regex builder with dotAll via the `(?s)` inline flag (works on JVM and JS RegExp). */
fun buildRegex(pattern: String, ignoreCase: Boolean, multiline: Boolean, dotAll: Boolean): Regex {
    val opts = buildSet {
        if (ignoreCase) add(RegexOption.IGNORE_CASE)
        if (multiline) add(RegexOption.MULTILINE)
    }
    return platformRegex((if (dotAll) "(?s)" else "") + pattern, opts)
}

/** `@Volatile` cross-thread hint (no-op on JS). */
expect annotation class PlatformVolatile constructor()

/** Stack-overflow signal the interpreter catches on deep recursion. */
expect class PlatformStackOverflowError : Throwable

/** A RuntimeException that skips stack-trace capture where the platform allows it (the JVM). */
expect open class PlatformStacklessException() : RuntimeException

/** JS Date emulation backend (java.time on JVM, native Date on JS). */
expect object PlatformTime {
    var zone: String
    fun nowMillis(): Long
    fun isoString(t: Double): String
    fun displayString(t: Double): String
    fun dateString(t: Double): String
    fun timeString(t: Double): String
    fun utcString(t: Double): String
    fun parse(text: String): Double
    fun format(t: Double, options: Map<String, String>, kind: String): String
    fun field(t: Double, utc: Boolean, field: String): Int
    fun setFields(t: Double, utc: Boolean, year: Double?, month: Double?, day: Double?, hour: Double?, min: Double?, sec: Double?, ms: Double?): Double
    fun fromParts(parts: List<Double>, utc: Boolean): Double
}

/** `Map.putIfAbsent` (a JVM default method; Kotlin's MutableMap lacks it). */
fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? =
    if (containsKey(key)) this[key] else { put(key, value); null }

/** A map kept in natural key order (TreeMap on JVM, insertion-sorted on JS). */
expect fun <K : Comparable<K>, V> sortedMapOf(): MutableMap<K, V>

/** `Throwable.javaClass.simpleName` (constructor name on JS). */
expect fun Throwable.className(): String

/** Regex construction with portable JVM inline flag semantics. */
expect fun platformRegex(pattern: String, options: Set<RegexOption> = emptySet()): Regex
fun platformRegex(pattern: String, option: RegexOption): Regex = platformRegex(pattern, setOf(option))
