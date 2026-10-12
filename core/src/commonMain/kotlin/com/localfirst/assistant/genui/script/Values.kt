package com.localfirst.assistant.genui.script

import com.localfirst.assistant.platform.PlatformBigDecimal
import com.localfirst.assistant.platform.buildRegex
import kotlin.math.abs
import kotlin.math.floor

/**
 * Values of FRIDAY's sandboxed JavaScript subset. Numbers are Doubles, strings are Strings, booleans
 * are Booleans; [Undefined] and [Null] stand in for JS's two empty values. Objects, arrays and
 * functions are the classes below. Nothing here can reach the host except through functions the host
 * passes in explicitly.
 */
object Undefined { override fun toString() = "undefined" }
object Null { override fun toString() = "null" }

/** A thrown JS value. */
class JsThrow(val value: Any?) : RuntimeException(null, null) {
    override val message: String get() = Values.errorMessage(value)
}

/** The program used up its step, time, depth or memory budget. */
class ScriptLimit(message: String) : RuntimeException(message)

open class JsObject(
    val properties: LinkedHashMap<String, Any?> = LinkedHashMap(),
    /** Shared methods, for objects made by a constructor such as Date or an Error. */
    open val proto: JsObject? = null,
    /** "Object", "Error", "Date"… for toString and error checks. */
    val className: String = "Object",
) {
    var frozen = false

    open fun get(key: String): Any? {
        if (properties.containsKey(key)) return properties[key]
        var p = proto
        while (p != null) {
            if (p.properties.containsKey(key)) return p.properties[key]
            p = p.proto
        }
        return Undefined
    }

    open fun set(key: String, value: Any?) {
        if (frozen) return
        properties[key] = value
    }

    open fun has(key: String): Boolean = properties.containsKey(key) || proto?.has(key) == true
    open fun keys(): List<String> = properties.keys.toList()
    open fun delete(key: String): Boolean { if (frozen) return false; properties.remove(key); return true }
}

class JsArray(val items: MutableList<Any?> = mutableListOf()) : JsObject(className = "Array") {
    override fun get(key: String): Any? {
        if (key == "length") return items.size.toDouble()
        key.toIntOrNull()?.let { return if (it in items.indices) items[it] else Undefined }
        return super.get(key)
    }

    override fun set(key: String, value: Any?) {
        if (frozen) return
        if (key == "length") {
            val n = Values.toNumber(value).toInt().coerceIn(0, Values.MAX_ARRAY)
            while (items.size > n) items.removeAt(items.lastIndex)
            while (items.size < n) items.add(Undefined)
            return
        }
        val index = key.toIntOrNull()
        if (index != null && index >= 0) {
            if (index >= Values.MAX_ARRAY) throw ScriptLimit("Array is too large.")
            while (items.size <= index) items.add(Undefined)
            items[index] = value
            return
        }
        super.set(key, value)
    }

    override fun has(key: String) = key == "length" || key.toIntOrNull()?.let { it in items.indices } ?: super.has(key)
    override fun keys(): List<String> = items.indices.map { it.toString() } + properties.keys
}

/** Anything callable. [thisValue] is the receiver for method calls. */
abstract class JsFunction(val name: String) : JsObject(className = "Function") {
    abstract fun call(interpreter: Interpreter, thisValue: Any?, args: List<Any?>): Any?
    open val arity: Int get() = 0
    override fun get(key: String): Any? = when (key) {
        "name" -> name
        "length" -> arity.toDouble()
        else -> super.get(key)
    }
}

/** A host or built-in function. */
class NativeFunction(name: String, override val arity: Int = 0, private val body: (Interpreter, Any?, List<Any?>) -> Any?) : JsFunction(name) {
    override fun call(interpreter: Interpreter, thisValue: Any?, args: List<Any?>): Any? = body(interpreter, thisValue, args)
}

class JsRegExp(val source: String, val flags: String) : JsObject(className = "RegExp") {
    val pattern: Regex = buildRegex(translate(source), 'i' in flags, 'm' in flags, 's' in flags)
    val global get() = 'g' in flags
    var lastIndex = 0

    override fun get(key: String): Any? = when (key) {
        "source" -> source
        "flags" -> flags
        "global" -> global
        "lastIndex" -> lastIndex.toDouble()
        else -> super.get(key)
    }

    override fun set(key: String, value: Any?) {
        if (key == "lastIndex") lastIndex = Values.toNumber(value).toInt() else super.set(key, value)
    }

    companion object {
        /** JS and Java regex syntax mostly agree; named groups and a few escapes differ. */
        fun translate(source: String): String = source.replace("[^]", "[\\s\\S]")
    }
}

/** A Date: milliseconds since the epoch, UTC unless formatted for display. */
class JsDate(var time: Double, proto: JsObject?) : JsObject(proto = proto, className = "Date")

object Values {
    const val MAX_ARRAY = 100_000
    const val MAX_STRING = 1_000_000

    fun isNullish(v: Any?) = v === Undefined || v === Null || v == null

    fun typeOf(v: Any?): String = when (v) {
        null, Undefined -> "undefined"
        Null -> "object"
        is Boolean -> "boolean"
        is Double, is Int, is Long, is Float -> "number"
        is String -> "string"
        is JsFunction -> "function"
        else -> "object"
    }

    fun truthy(v: Any?): Boolean = when (v) {
        null, Undefined, Null -> false
        is Boolean -> v
        is Double -> !(v == 0.0 || v.isNaN())
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        else -> true
    }

    fun toNumber(v: Any?): Double = when (v) {
        null, Undefined -> Double.NaN
        Null -> 0.0
        is Boolean -> if (v) 1.0 else 0.0
        is Double -> v
        is Number -> v.toDouble()
        is String -> parseNumber(v)
        is JsDate -> v.time
        is JsArray -> when (v.items.size) { 0 -> 0.0; 1 -> toNumber(v.items[0]); else -> Double.NaN }
        else -> Double.NaN
    }

    fun parseNumber(text: String): Double {
        val s = text.trim()
        if (s.isEmpty()) return 0.0
        if (s == "Infinity" || s == "+Infinity") return Double.POSITIVE_INFINITY
        if (s == "-Infinity") return Double.NEGATIVE_INFINITY
        if (s.startsWith("0x") || s.startsWith("0X")) return s.substring(2).toLongOrNull(16)?.toDouble() ?: Double.NaN
        if (s.startsWith("0b") || s.startsWith("0B")) return s.substring(2).toLongOrNull(2)?.toDouble() ?: Double.NaN
        if (s.startsWith("0o") || s.startsWith("0O")) return s.substring(2).toLongOrNull(8)?.toDouble() ?: Double.NaN
        if (!s.matches(Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?"))) return Double.NaN
        return s.toDoubleOrNull() ?: Double.NaN
    }

    fun toInt32(v: Any?): Int {
        val d = toNumber(v)
        if (d.isNaN() || d.isInfinite()) return 0
        return (d.toLong() and 0xFFFFFFFFL).toInt()
    }

    fun toUint32(v: Any?): Long = toInt32(v).toLong() and 0xFFFFFFFFL

    fun toInteger(v: Any?): Double {
        val d = toNumber(v)
        if (d.isNaN()) return 0.0
        if (d.isInfinite()) return d
        return if (d < 0) -floor(-d) else floor(d)
    }

    /** JS's Number#toString: integers without a fraction, shortest round-trip otherwise. */
    fun numberToString(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == Double.POSITIVE_INFINITY) return "Infinity"
        if (d == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (d == 0.0) return "0"
        if (d == floor(d) && abs(d) < 1e21) {
            return if (abs(d) < Long.MAX_VALUE.toDouble()) d.toLong().toString() else PlatformBigDecimal(d).toPlainString()
        }
        val repr = d.toString() // shortest round-trip on modern JVMs and ART
        if (!repr.contains('E')) return repr.removeSuffix(".0")
        val mantissa = repr.substringBefore('E').removeSuffix(".0")
        val exponent = repr.substringAfter('E').toInt()
        if (exponent in -6 until 21) {
            return PlatformBigDecimal(repr).stripTrailingZeros().toPlainString()
        }
        return mantissa + "e" + (if (exponent > 0) "+" else "") + exponent
    }

    fun toStr(v: Any?): String = when (v) {
        null, Undefined -> "undefined"
        Null -> "null"
        is Boolean -> v.toString()
        is Double -> numberToString(v)
        is Number -> numberToString(v.toDouble())
        is String -> v
        is JsArray -> v.items.joinToString(",") { if (isNullish(it)) "" else toStr(it) }
        is JsFunction -> "function ${v.name}() { [native code] }"
        is JsRegExp -> "/${v.source}/${v.flags}"
        is JsDate -> Dates.toIsoString(v.time)
        is JsObject -> if (v.className == "Error") {
            val name = toStr(v.get("name")); val message = toStr(v.get("message"))
            if (message.isEmpty()) name else "$name: $message"
        } else "[object Object]"
        else -> v.toString()
    }

    /** For `+`, comparisons and template literals: objects become their primitive value. */
    fun toPrimitive(v: Any?, hint: String = "default"): Any? = when (v) {
        is JsDate -> if (hint == "number") v.time else Dates.toDisplayString(v.time)
        is JsArray, is JsRegExp, is JsFunction -> toStr(v)
        is JsObject -> if (v.className == "Error") toStr(v) else "[object Object]"
        else -> v
    }

    fun strictEquals(a: Any?, b: Any?): Boolean {
        val x = normalize(a); val y = normalize(b)
        if (x is Double && y is Double) return x == y && !x.isNaN()
        if (x is String && y is String) return x == y
        if (x is Boolean && y is Boolean) return x == y
        return x === y
    }

    fun looseEquals(a: Any?, b: Any?): Boolean {
        val x = normalize(a); val y = normalize(b)
        if (isNullish(x) && isNullish(y)) return true
        if (isNullish(x) || isNullish(y)) return false
        if (typeOf(x) == typeOf(y)) return strictEquals(x, y)
        if (x is Double && y is String) return x == toNumber(y)
        if (x is String && y is Double) return toNumber(x) == y
        if (x is Boolean) return looseEquals(toNumber(x), y)
        if (y is Boolean) return looseEquals(x, toNumber(y))
        if ((x is Double || x is String) && y is JsObject) return looseEquals(x, toPrimitive(y))
        if (x is JsObject && (y is Double || y is String)) return looseEquals(toPrimitive(x), y)
        return false
    }

    /** Same-value-zero, as used by includes() and Map keys. */
    fun sameValueZero(a: Any?, b: Any?): Boolean {
        val x = normalize(a); val y = normalize(b)
        if (x is Double && y is Double) return x == y || (x.isNaN() && y.isNaN())
        return strictEquals(x, y)
    }

    /** Host code may hand back Ints, Longs or Kotlin nulls; inside the interpreter they are Doubles and Undefined. */
    fun normalize(v: Any?): Any? = when (v) {
        null -> Undefined
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Float -> v.toDouble()
        is Short -> v.toDouble()
        is Byte -> v.toDouble()
        else -> v
    }

    fun propertyKey(v: Any?): String = when (val n = normalize(v)) {
        is Double -> numberToString(n)
        else -> toStr(n)
    }

    fun errorMessage(value: Any?): String = when (value) {
        is JsObject -> if (value.className == "Error") toStr(value) else "Uncaught ${toStr(value)}"
        else -> "Uncaught ${toStr(value)}"
    }

    /** Converts plain Kotlin data (maps, lists, numbers, strings) into script values. */
    fun fromKotlin(value: Any?): Any? = when (value) {
        null -> Null
        is Map<*, *> -> JsObject(LinkedHashMap<String, Any?>().apply { value.forEach { (k, v) -> put(k.toString(), fromKotlin(v)) } })
        is List<*> -> JsArray(value.map(::fromKotlin).toMutableList())
        is Number -> value.toDouble()
        is Boolean, is String -> value
        is Undefined, is Null, is JsObject -> value
        else -> value.toString()
    }

    /** The reverse of [fromKotlin], for host code reading results. Functions become null. */
    fun toKotlin(value: Any?, depth: Int = 0, wholeNumbersAsLong: Boolean = true): Any? = when {
        depth > 50 -> null
        value === Undefined || value === Null || value == null -> null
        value is JsArray -> value.items.map { toKotlin(it, depth + 1, wholeNumbersAsLong) }
        value is JsFunction -> null
        value is JsDate -> Dates.toIsoString(value.time)
        value is JsObject -> LinkedHashMap<String, Any?>().apply { value.keys().forEach { put(it, toKotlin(value.get(it), depth + 1, wholeNumbersAsLong)) } }
        value is Double -> if (wholeNumbersAsLong && value == floor(value) && abs(value) < 1e15) value.toLong() else value
        else -> value
    }
}
