package com.localfirst.assistant.genui.script

import com.localfirst.assistant.platform.platformRegex

import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.platform.PlatformBigDecimal
import com.localfirst.assistant.platform.PlatformCollator
import com.localfirst.assistant.platform.PlatformCurrency
import com.localfirst.assistant.platform.PlatformDecimalFormat
import com.localfirst.assistant.platform.PlatformDecimalFormatSymbols
import com.localfirst.assistant.platform.PlatformIdentityHashMap
import com.localfirst.assistant.platform.PlatformMath
import com.localfirst.assistant.platform.PlatformMathContext
import com.localfirst.assistant.platform.PlatformNet
import com.localfirst.assistant.platform.PlatformNormalizer
import com.localfirst.assistant.platform.PlatformRandom
import com.localfirst.assistant.platform.PlatformRoundingMode
import com.localfirst.assistant.platform.PlatformSystem
import com.localfirst.assistant.platform.PlatformCharacter
import com.localfirst.assistant.platform.appendCodePoint
import com.localfirst.assistant.platform.codePointAt
import com.localfirst.assistant.platform.newIdentitySet
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject as KJsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Map and Set keyed by JS same-value-zero. */
private data class IdentityKey(val obj: Any) {
    override fun equals(other: Any?) = other is IdentityKey && other.obj === obj
    override fun hashCode() = PlatformSystem.identityHashCode(obj)
}
private object NaNKey

internal fun mapKey(v: Any?): Any = when (val n = Values.normalize(v)) {
    is Double -> when { n.isNaN() -> NaNKey; n == 0.0 -> 0.0; else -> n }
    is JsObject -> IdentityKey(n)
    null -> Undefined
    else -> n
}

class JsMap : JsObject(className = "Map") { val entries = LinkedHashMap<Any, Pair<Any?, Any?>>() }
class JsSet : JsObject(className = "Set") { val entries = LinkedHashMap<Any, Pair<Any?, Any?>>() }

private fun arg(args: List<Any?>, i: Int): Any? = if (i < args.size) Values.normalize(args[i]) else Undefined

/**
 * The global objects and prototype methods of the JavaScript subset. Deliberately absent, as in
 * ChatGPT's sandbox: network access, timers, messaging, dynamic code evaluation and any way to reach
 * the host other than functions the host installs itself.
 */
object Builtins {
    private fun native(name: String, arity: Int = 0, body: (Interpreter, Any?, List<Any?>) -> Any?) = NativeFunction(name, arity, body)

    fun install(interp: Interpreter) {
        val g = interp.globals
        val objectProto = JsObject()
        interp.objectPrototype = objectProto
        objectProto.set("hasOwnProperty", native("hasOwnProperty", 1) { _, self, a -> (self as? JsObject)?.properties?.containsKey(Values.propertyKey(arg(a, 0))) == true })
        objectProto.set("toString", native("toString") { _, self, _ -> Values.toStr(self) })
        objectProto.set("valueOf", native("valueOf") { _, self, _ -> self })

        fun obj(vararg entries: Pair<String, Any?>) = JsObject(LinkedHashMap(entries.toMap()))
        fun def(name: String, value: Any?) = g.declare(name, value, constant = true)

        def("NaN", Double.NaN)
        def("Infinity", Double.POSITIVE_INFINITY)

        // ---- console ----
        def("console", obj(*listOf("log", "info", "warn", "error", "debug").map { level ->
            level to native(level) { i, _, a ->
                if (i.console.size < 200) i.console += a.joinToString(" ") { v -> if (v is String) v else inspect(i, v) }.take(4_000)
                Undefined
            }
        }.toTypedArray()))

        // ---- Errors ----
        for (type in listOf("Error", "TypeError", "RangeError", "ReferenceError", "SyntaxError", "EvalError", "URIError")) {
            val proto = JsObject()
            proto.set("name", type)
            val ctor = native(type, 1) { _, _, a ->
                JsObject(proto = proto, className = "Error").apply {
                    set("message", if (arg(a, 0) === Undefined) "" else Values.toStr(arg(a, 0)))
                    set("name", type)
                }
            }
            ctor.set("prototype", proto)
            def(type, ctor)
        }

        // ---- Object ----
        val objectCtor = native("Object", 1) { i, _, a -> (arg(a, 0) as? JsObject) ?: i.newObject() }
        objectCtor.set("keys", native("keys", 1) { i, _, a -> i.newArray(i.keysOf(arg(a, 0)).map { Values.toStr(it) }) })
        objectCtor.set("values", native("values", 1) { i, _, a -> val o = arg(a, 0); i.newArray(i.keysOf(o).map { i.getProperty(o, Values.toStr(it)) }) })
        objectCtor.set("entries", native("entries", 1) { i, _, a ->
            val o = arg(a, 0)
            i.newArray(i.keysOf(o).map { k -> i.newArray(listOf(Values.toStr(k), i.getProperty(o, Values.toStr(k)))) })
        })
        objectCtor.set("assign", native("assign", 2) { i, _, a ->
            val target = arg(a, 0) as? JsObject ?: throw i.typeError("Cannot convert ${Values.toStr(arg(a, 0))} to object")
            a.drop(1).forEach { source -> if (!Values.isNullish(source)) i.keysOf(source).forEach { k -> target.set(Values.toStr(k), i.getProperty(source, Values.toStr(k))) } }
            target
        })
        objectCtor.set("fromEntries", native("fromEntries", 1) { i, _, a ->
            val out = i.newObject()
            i.iterate(arg(a, 0)).forEach { entry -> val pair = i.iterate(entry); out.set(Values.propertyKey(pair.getOrNull(0)), pair.getOrElse(1) { Undefined }) }
            out
        })
        objectCtor.set("freeze", native("freeze", 1) { _, _, a -> (arg(a, 0) as? JsObject)?.frozen = true; arg(a, 0) })
        objectCtor.set("isFrozen", native("isFrozen", 1) { _, _, a -> (arg(a, 0) as? JsObject)?.frozen ?: true })
        objectCtor.set("create", native("create", 2) { i, _, a -> i.allocate(); JsObject(proto = arg(a, 0) as? JsObject) })
        objectCtor.set("hasOwn", native("hasOwn", 2) { _, _, a -> (arg(a, 0) as? JsObject)?.properties?.containsKey(Values.propertyKey(arg(a, 1))) == true || (arg(a, 0) as? JsArray)?.has(Values.propertyKey(arg(a, 1))) == true })
        objectCtor.set("getPrototypeOf", native("getPrototypeOf", 1) { _, _, a -> (arg(a, 0) as? JsObject)?.proto ?: Null })
        objectCtor.set("defineProperty", native("defineProperty", 3) { i, _, a ->
            val target = arg(a, 0) as? JsObject ?: throw i.typeError("Object.defineProperty called on non-object")
            val descriptor = arg(a, 2) as? JsObject
            target.set(Values.propertyKey(arg(a, 1)), descriptor?.get("value") ?: Undefined)
            target
        })
        objectCtor.set("is", native("is", 2) { _, _, a ->
            val x = arg(a, 0); val y = arg(a, 1)
            if (x is Double && y is Double) (x.isNaN() && y.isNaN()) || (x == y && (x != 0.0 || 1 / x == 1 / y)) else Values.strictEquals(x, y)
        })
        objectCtor.set("prototype", objectProto)
        def("Object", objectCtor)

        // ---- Array ----
        val arrayCtor = native("Array", 1) { i, _, a ->
            val n = arg(a, 0)
            if (a.size == 1 && n is Double) {
                if (n < 0 || n > Values.MAX_ARRAY || n != floor(n)) throw i.error("RangeError", "Invalid array length")
                i.newArray(List(n.toInt()) { Undefined })
            } else i.newArray(a.map(Values::normalize))
        }
        arrayCtor.set("isArray", native("isArray", 1) { _, _, a -> arg(a, 0) is JsArray })
        arrayCtor.set("of", native("of") { i, _, a -> i.newArray(a.map(Values::normalize)) })
        arrayCtor.set("from", native("from", 1) { i, _, a ->
            val source = arg(a, 0)
            val items: List<Any?> = when {
                source is JsObject && source !is JsArray && source !is JsMap && source !is JsSet && source.has("length") ->
                    List(Values.toNumber(source.get("length")).toInt().coerceIn(0, Values.MAX_ARRAY)) { k -> source.get(k.toString()) }
                Values.isNullish(source) -> throw i.typeError("Array.from requires an iterable")
                else -> i.iterate(source)
            }
            val mapFn = arg(a, 1) as? JsFunction
            i.newArray(if (mapFn == null) items else items.mapIndexed { k, v -> i.callFunction(mapFn, Undefined, listOf(v, k.toDouble())) })
        })
        def("Array", arrayCtor)

        // ---- Number, String, Boolean ----
        val numberCtor = native("Number", 1) { _, _, a -> if (a.isEmpty()) 0.0 else Values.toNumber(Values.toPrimitive(arg(a, 0), "number")) }
        numberCtor.set("isInteger", native("isInteger", 1) { _, _, a -> (arg(a, 0) as? Double)?.let { it.isFinite() && it == floor(it) } ?: false })
        numberCtor.set("isSafeInteger", native("isSafeInteger", 1) { _, _, a -> (arg(a, 0) as? Double)?.let { it.isFinite() && it == floor(it) && abs(it) <= 9007199254740991.0 } ?: false })
        numberCtor.set("isFinite", native("isFinite", 1) { _, _, a -> (arg(a, 0) as? Double)?.isFinite() ?: false })
        numberCtor.set("isNaN", native("isNaN", 1) { _, _, a -> (arg(a, 0) as? Double)?.isNaN() ?: false })
        numberCtor.set("parseFloat", native("parseFloat", 1) { _, _, a -> parseFloat(Values.toStr(arg(a, 0))) })
        numberCtor.set("parseInt", native("parseInt", 2) { _, _, a -> parseInt(Values.toStr(arg(a, 0)), arg(a, 1)) })
        numberCtor.set("EPSILON", PlatformMath.ulp(1.0))
        numberCtor.set("MAX_SAFE_INTEGER", 9007199254740991.0)
        numberCtor.set("MIN_SAFE_INTEGER", -9007199254740991.0)
        numberCtor.set("MAX_VALUE", Double.MAX_VALUE)
        numberCtor.set("MIN_VALUE", 4.9e-324)
        numberCtor.set("POSITIVE_INFINITY", Double.POSITIVE_INFINITY)
        numberCtor.set("NEGATIVE_INFINITY", Double.NEGATIVE_INFINITY)
        numberCtor.set("NaN", Double.NaN)
        def("Number", numberCtor)
        val stringCtor = native("String", 1) { _, _, a -> if (a.isEmpty()) "" else Values.toStr(Values.toPrimitive(arg(a, 0), "string")) }
        stringCtor.set("fromCharCode", native("fromCharCode", 1) { _, _, a -> a.joinToString("") { (Values.toUint32(it) and 0xFFFF).toInt().toChar().toString() } })
        stringCtor.set("fromCodePoint", native("fromCodePoint", 1) { _, _, a -> buildString { a.forEach { appendCodePoint(this, Values.toNumber(it).toInt()) } } })
        def("String", stringCtor)
        def("Boolean", native("Boolean", 1) { _, _, a -> Values.truthy(arg(a, 0)) })

        def("parseInt", native("parseInt", 2) { _, _, a -> parseInt(Values.toStr(arg(a, 0)), arg(a, 1)) })
        def("parseFloat", native("parseFloat", 1) { _, _, a -> parseFloat(Values.toStr(arg(a, 0))) })
        def("isNaN", native("isNaN", 1) { _, _, a -> Values.toNumber(arg(a, 0)).isNaN() })
        def("isFinite", native("isFinite", 1) { _, _, a -> Values.toNumber(arg(a, 0)).isFinite() })
        def("encodeURIComponent", native("encodeURIComponent", 1) { _, _, a ->
            PlatformNet.encodeUrl(Values.toStr(arg(a, 0))).replace("+", "%20").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")
        })
        def("decodeURIComponent", native("decodeURIComponent", 1) { i, _, a ->
            try { PlatformNet.decodeUrl(Values.toStr(arg(a, 0)).replace("+", "%2B")) } catch (e: Exception) { throw i.error("URIError", "URI malformed") }
        })
        def("structuredClone", native("structuredClone", 1) { _, _, a -> Values.fromKotlin(Values.toKotlin(arg(a, 0))).let { if (it === Null && arg(a, 0) === Undefined) Undefined else it } })

        // ---- Math ----
        val random = PlatformRandom()
        fun m1(name: String, f: (Double) -> Double) = name to native(name, 1) { _, _, a -> f(Values.toNumber(arg(a, 0))) }
        def("Math", obj(
            "PI" to PlatformMath.PI, "E" to PlatformMath.E, "LN2" to ln(2.0), "LN10" to ln(10.0), "LOG2E" to 1 / ln(2.0), "LOG10E" to 1 / ln(10.0),
            "SQRT2" to PlatformMath.sqrt(2.0), "SQRT1_2" to PlatformMath.sqrt(0.5),
            m1("abs", ::abs), m1("floor", ::floor), m1("ceil", PlatformMath::ceil), m1("trunc") { if (it < 0) -floor(-it) else floor(it) },
            m1("round") { if (it.isNaN() || it.isInfinite()) it else floor(it + 0.5) }, m1("sign") { if (it.isNaN()) it else sign(it) },
            m1("sqrt", PlatformMath::sqrt), m1("cbrt", PlatformMath::cbrt), m1("exp", PlatformMath::exp), m1("expm1", PlatformMath::expm1), m1("log", PlatformMath::log),
            m1("log10", PlatformMath::log10), m1("log2") { ln(it) / ln(2.0) }, m1("log1p", PlatformMath::log1p),
            m1("sin", PlatformMath::sin), m1("cos", PlatformMath::cos), m1("tan", PlatformMath::tan), m1("asin", PlatformMath::asin), m1("acos", PlatformMath::acos),
            m1("atan", PlatformMath::atan), m1("sinh", PlatformMath::sinh), m1("cosh", PlatformMath::cosh), m1("tanh", PlatformMath::tanh), m1("fround") { it.toFloat().toDouble() },
            "atan2" to native("atan2", 2) { _, _, a -> PlatformMath.atan2(Values.toNumber(arg(a, 0)), Values.toNumber(arg(a, 1))) },
            "pow" to native("pow", 2) { _, _, a -> Values.toNumber(arg(a, 0)).pow(Values.toNumber(arg(a, 1))) },
            "hypot" to native("hypot", 2) { _, _, a -> PlatformMath.sqrt(a.sumOf { Values.toNumber(it).pow(2) }) },
            "min" to native("min", 2) { _, _, a -> a.map { Values.toNumber(it) }.let { xs -> if (xs.any { it.isNaN() }) Double.NaN else xs.minOrNull() ?: Double.POSITIVE_INFINITY } },
            "max" to native("max", 2) { _, _, a -> a.map { Values.toNumber(it) }.let { xs -> if (xs.any { it.isNaN() }) Double.NaN else xs.maxOrNull() ?: Double.NEGATIVE_INFINITY } },
            "random" to native("random") { _, _, _ -> random.nextDouble() },
        ))

        // ---- JSON ----
        def("JSON", obj(
            "stringify" to native("stringify", 3) { i, _, a -> Json.stringify(i, arg(a, 0), arg(a, 1), arg(a, 2)) ?: Undefined },
            "parse" to native("parse", 2) { i, _, a ->
                val element = try { JsonCodec.json.parseToJsonElement(Values.toStr(arg(a, 0))) } catch (e: Exception) { throw i.error("SyntaxError", "Unexpected token in JSON") }
                Json.fromElement(i, element)
            },
        ))

        // ---- Map and Set ----
        def("Map", native("Map") { i, _, a ->
            i.allocate()
            JsMap().also { map -> if (!Values.isNullish(arg(a, 0))) i.iterate(arg(a, 0)).forEach { e -> val p = i.iterate(e); map.entries[mapKey(p.getOrNull(0))] = p.getOrElse(0) { Undefined } to p.getOrElse(1) { Undefined } } }
        })
        def("Set", native("Set") { i, _, a ->
            i.allocate()
            JsSet().also { set -> if (!Values.isNullish(arg(a, 0))) i.iterate(arg(a, 0)).forEach { v -> set.entries[mapKey(v)] = v to v } }
        })
        def("RegExp", native("RegExp", 2) { i, _, a ->
            try { JsRegExp(if (arg(a, 0) is JsRegExp) (arg(a, 0) as JsRegExp).source else Values.toStr(arg(a, 0)), if (arg(a, 1) === Undefined) "" else Values.toStr(arg(a, 1))) }
            catch (e: Exception) { throw i.error("SyntaxError", "Invalid regular expression") }
        })

        // ---- Date and Intl ----
        Dates.install(interp)
        Intl.install(interp)
    }

    // ---- primitive and object properties ---------------------------------------------------------------

    fun stringProperty(interp: Interpreter, s: String, key: String): Any? {
        if (key == "length") return s.length.toDouble()
        key.toIntOrNull()?.let { return if (it in s.indices) s[it].toString() else Undefined }
        return stringMethods[key] ?: Undefined
    }

    fun numberProperty(interp: Interpreter, n: Double, key: String): Any? = numberMethods[key] ?: Undefined
    fun booleanProperty(interp: Interpreter, b: Boolean, key: String): Any? = when (key) {
        "toString" -> native("toString") { _, self, _ -> Values.toStr(self) }
        "valueOf" -> native("valueOf") { _, self, _ -> self }
        else -> Undefined
    }

    fun arrayProperty(interp: Interpreter, a: JsArray, key: String): Any? = arrayMethods[key] ?: objectProperty(interp, a, key)
    fun functionProperty(interp: Interpreter, f: JsFunction, key: String): Any? = functionMethods[key] ?: Undefined

    fun objectProperty(interp: Interpreter, o: JsObject, key: String): Any? = when (o) {
        is JsMap -> mapMethods[key] ?: if (key == "size") o.entries.size.toDouble() else Undefined
        is JsSet -> setMethods[key] ?: if (key == "size") o.entries.size.toDouble() else Undefined
        is JsRegExp -> regexMethods[key] ?: Undefined
        is JsDate -> Undefined
        else -> interp.objectPrototype?.get(key) ?: Undefined
    }

    private fun self(thisValue: Any?): String = Values.toStr(thisValue)
    private fun selfArray(interp: Interpreter, thisValue: Any?): JsArray = thisValue as? JsArray ?: throw interp.typeError("Array method called on a non-array")
    private fun callback(interp: Interpreter, a: List<Any?>): JsFunction = arg(a, 0) as? JsFunction ?: throw interp.typeError("${Values.toStr(arg(a, 0))} is not a function")

    private fun relativeIndex(value: Any?, length: Int, default: Int): Int {
        if (value === Undefined) return default
        val n = Values.toInteger(value)
        return (if (n < 0) max(length + n, 0.0) else min(n, length.toDouble())).toInt()
    }

    private val stringMethods: Map<String, NativeFunction> = mapOf(
        "charAt" to native("charAt", 1) { _, t, a -> self(t).let { s -> Values.toInteger(arg(a, 0)).toInt().let { if (it in s.indices) s[it].toString() else "" } } },
        "charCodeAt" to native("charCodeAt", 1) { _, t, a -> self(t).let { s -> Values.toInteger(arg(a, 0)).toInt().let { if (it in s.indices) s[it].code.toDouble() else Double.NaN } } },
        "codePointAt" to native("codePointAt", 1) { _, t, a -> self(t).let { s -> Values.toInteger(arg(a, 0)).toInt().let { if (it in s.indices) codePointAt(s, it).toDouble() else Undefined } } },
        "at" to native("at", 1) { _, t, a -> self(t).let { s -> Values.toInteger(arg(a, 0)).toInt().let { i -> val k = if (i < 0) s.length + i else i; if (k in s.indices) s[k].toString() else Undefined } } },
        "indexOf" to native("indexOf", 1) { _, t, a -> self(t).indexOf(Values.toStr(arg(a, 0)), Values.toInteger(arg(a, 1)).toInt().coerceAtLeast(0)).toDouble() },
        "lastIndexOf" to native("lastIndexOf", 1) { _, t, a -> self(t).lastIndexOf(Values.toStr(arg(a, 0))).toDouble() },
        "includes" to native("includes", 1) { i, t, a -> if (arg(a, 0) is JsRegExp) throw i.typeError("includes() can't take a regular expression") else self(t).indexOf(Values.toStr(arg(a, 0)), Values.toInteger(arg(a, 1)).toInt().coerceAtLeast(0)) >= 0 },
        "startsWith" to native("startsWith", 1) { _, t, a -> self(t).startsWith(Values.toStr(arg(a, 0)), Values.toInteger(arg(a, 1)).toInt().coerceAtLeast(0)) },
        "endsWith" to native("endsWith", 1) { _, t, a -> self(t).let { s -> val end = if (arg(a, 1) === Undefined) s.length else Values.toInteger(arg(a, 1)).toInt().coerceIn(0, s.length); s.substring(0, end).endsWith(Values.toStr(arg(a, 0))) } },
        "slice" to native("slice", 2) { _, t, a -> self(t).let { s -> val from = relativeIndex(arg(a, 0), s.length, 0); val to = relativeIndex(arg(a, 1), s.length, s.length); if (from < to) s.substring(from, to) else "" } },
        "substring" to native("substring", 2) { _, t, a -> self(t).let { s ->
            val x = Values.toInteger(arg(a, 0)).toInt().coerceIn(0, s.length)
            val y = if (arg(a, 1) === Undefined) s.length else Values.toInteger(arg(a, 1)).toInt().coerceIn(0, s.length)
            s.substring(min(x, y), max(x, y))
        } },
        "substr" to native("substr", 2) { _, t, a -> self(t).let { s ->
            val start = relativeIndex(arg(a, 0), s.length, 0)
            val length = if (arg(a, 1) === Undefined) s.length - start else Values.toInteger(arg(a, 1)).toInt().coerceIn(0, s.length - start)
            s.substring(start, start + length)
        } },
        "toUpperCase" to native("toUpperCase") { _, t, _ -> self(t).uppercase() },
        "toLowerCase" to native("toLowerCase") { _, t, _ -> self(t).lowercase() },
        "toLocaleUpperCase" to native("toLocaleUpperCase") { _, t, _ -> self(t).uppercase() },
        "toLocaleLowerCase" to native("toLocaleLowerCase") { _, t, _ -> self(t).lowercase() },
        "trim" to native("trim") { _, t, _ -> self(t).trim() },
        "trimStart" to native("trimStart") { _, t, _ -> self(t).trimStart() },
        "trimEnd" to native("trimEnd") { _, t, _ -> self(t).trimEnd() },
        "padStart" to native("padStart", 2) { i, t, a -> pad(i, self(t), a, start = true) },
        "padEnd" to native("padEnd", 2) { i, t, a -> pad(i, self(t), a, start = false) },
        "repeat" to native("repeat", 1) { i, t, a ->
            val n = Values.toInteger(arg(a, 0))
            if (n < 0 || n.isInfinite()) throw i.error("RangeError", "Invalid count value")
            val s = self(t); i.checkString((s.length * n).toInt()); s.repeat(n.toInt())
        },
        "concat" to native("concat", 1) { i, t, a -> (self(t) + a.joinToString("") { Values.toStr(it) }).also { i.checkString(it.length) } },
        "split" to native("split", 2) { i, t, a ->
            val s = self(t)
            val limit = if (arg(a, 1) === Undefined) Int.MAX_VALUE else Values.toUint32(arg(a, 1)).toInt().coerceAtLeast(0)
            val parts: List<String> = when (val sep = arg(a, 0)) {
                Undefined -> listOf(s)
                is JsRegExp -> if (s.isEmpty()) (if (sep.pattern.matches("")) emptyList() else listOf("")) else sep.pattern.split(s)
                else -> { val d = Values.toStr(sep); if (d.isEmpty()) s.map { it.toString() } else s.split(d) }
            }
            i.newArray(parts.take(limit))
        },
        "replace" to native("replace", 2) { i, t, a -> replace(i, self(t), arg(a, 0), arg(a, 1), all = (arg(a, 0) as? JsRegExp)?.global == true) },
        "replaceAll" to native("replaceAll", 2) { i, t, a -> replace(i, self(t), arg(a, 0), arg(a, 1), all = true) },
        "match" to native("match", 1) { i, t, a ->
            val s = self(t)
            val re = arg(a, 0) as? JsRegExp ?: JsRegExp(Regex.escape(Values.toStr(arg(a, 0))), "")
            if (re.global) re.pattern.findAll(s).map { it.value }.toList().takeIf { it.isNotEmpty() }?.let { i.newArray(it) } ?: Null
            else re.pattern.find(s)?.let { matchArray(i, it) } ?: Null
        },
        "matchAll" to native("matchAll", 1) { i, t, a ->
            val re = arg(a, 0) as? JsRegExp ?: JsRegExp(Regex.escape(Values.toStr(arg(a, 0))), "g")
            i.newArray(re.pattern.findAll(self(t)).map { matchArray(i, it) }.toList())
        },
        "search" to native("search", 1) { _, t, a ->
            val re = arg(a, 0) as? JsRegExp ?: JsRegExp(Regex.escape(Values.toStr(arg(a, 0))), "")
            (re.pattern.find(self(t))?.range?.first ?: -1).toDouble()
        },
        "localeCompare" to native("localeCompare", 1) { _, t, a ->
            sign(PlatformCollator.compare(self(t), Values.toStr(arg(a, 0))).toDouble())
        },
        "normalize" to native("normalize") { _, t, a -> PlatformNormalizer.normalize(self(t), when (Values.toStr(arg(a, 0))) { "NFD" -> "NFD"; "NFKC" -> "NFKC"; "NFKD" -> "NFKD"; else -> "NFC" }) },
        "toString" to native("toString") { _, t, _ -> self(t) },
        "valueOf" to native("valueOf") { _, t, _ -> self(t) },
    )

    private fun pad(interp: Interpreter, s: String, a: List<Any?>, start: Boolean): String {
        val target = Values.toInteger(arg(a, 0)).toInt()
        interp.checkString(target)
        val filler = if (arg(a, 1) === Undefined) " " else Values.toStr(arg(a, 1))
        if (target <= s.length || filler.isEmpty()) return s
        val needed = target - s.length
        val padding = filler.repeat(needed / filler.length + 1).take(needed)
        return if (start) padding + s else s + padding
    }

    private fun matchArray(interp: Interpreter, m: MatchResult): JsArray {
        val arr = interp.newArray(m.groupValues.mapIndexed { k, v -> if (m.groups[k] == null) Undefined else v })
        arr.set("index", m.range.first.toDouble())
        return arr
    }

    private fun replace(interp: Interpreter, s: String, pattern: Any?, replacement: Any?, all: Boolean): String {
        val fn = replacement as? JsFunction
        val template = if (fn == null) Values.toStr(replacement) else ""
        fun expand(m: MatchResult): String {
            if (fn != null) {
                val args = m.groupValues.mapIndexed { k, v -> if (m.groups[k] == null) Undefined else v } + listOf(m.range.first.toDouble(), s)
                return Values.toStr(interp.callFunction(fn, Undefined, args))
            }
            val out = StringBuilder()
            var k = 0
            while (k < template.length) {
                val c = template[k]
                if (c == '$' && k + 1 < template.length) {
                    val n = template[k + 1]
                    when {
                        n == '$' -> { out.append('$'); k += 2; continue }
                        n == '&' -> { out.append(m.value); k += 2; continue }
                        n.isDigit() -> {
                            val two = template.substring(k + 1, min(k + 3, template.length)).takeWhile { it.isDigit() }
                            val idx = two.toIntOrNull()?.takeIf { it < m.groupValues.size } ?: two.take(1).toInt()
                            if (idx in 1 until m.groupValues.size) { out.append(m.groupValues[idx]); k += 1 + idx.toString().length; continue }
                        }
                    }
                }
                out.append(c); k++
            }
            return out.toString()
        }
        val regex = (pattern as? JsRegExp)?.pattern ?: platformRegex(Regex.escape(Values.toStr(pattern)))
        val result = if (all) regex.replace(s) { expand(it) } else regex.find(s)?.let { m -> s.substring(0, m.range.first) + expand(m) + s.substring(m.range.last + 1) } ?: s
        interp.checkString(result.length)
        return result
    }

    private val numberMethods: Map<String, NativeFunction> = mapOf(
        "toFixed" to native("toFixed", 1) { i, t, a ->
            val digits = Values.toInteger(arg(a, 0)).toInt()
            if (digits !in 0..100) throw i.error("RangeError", "toFixed() digits argument must be between 0 and 100")
            toFixed(Values.toNumber(t), digits)
        },
        "toPrecision" to native("toPrecision", 1) { i, t, a ->
            val n = Values.toNumber(t)
            if (arg(a, 0) === Undefined || !n.isFinite()) Values.numberToString(n) else {
                val p = Values.toInteger(arg(a, 0)).toInt()
                if (p !in 1..100) throw i.error("RangeError", "toPrecision() argument must be between 1 and 100")
                if (n == 0.0) toFixed(0.0, p - 1) else {
                    val e = floor(log10(abs(n))).toInt()
                    if (e < -6 || e >= p) {
                        val mantissa = PlatformBigDecimal(n).round(PlatformMathContext(p, PlatformRoundingMode.HALF_UP))
                        val exp = mantissa.precision() - mantissa.scale() - 1
                        val digits = mantissa.unscaledValueDigits().padEnd(p, '0')
                        (if (n < 0) "-" else "") + digits[0] + (if (p > 1) "." + digits.substring(1, p) else "") + "e" + (if (exp >= 0) "+" else "") + exp
                    } else PlatformBigDecimal(n).round(PlatformMathContext(p, PlatformRoundingMode.HALF_UP)).let { it.setScale(max(0, p - 1 - e), PlatformRoundingMode.HALF_UP).toPlainString() }
                }
            }
        },
        "toString" to native("toString", 1) { i, t, a ->
            val n = Values.toNumber(t)
            val radix = if (arg(a, 0) === Undefined) 10 else Values.toInteger(arg(a, 0)).toInt()
            if (radix !in 2..36) throw i.error("RangeError", "toString() radix must be between 2 and 36")
            if (radix == 10 || !n.isFinite()) Values.numberToString(n)
            else if (n == floor(n)) n.toLong().toString(radix)
            else Values.numberToString(n)
        },
        "toLocaleString" to native("toLocaleString", 2) { _, t, a -> Intl.formatNumber(Values.toNumber(t), arg(a, 1) as? JsObject) },
        "valueOf" to native("valueOf") { _, t, _ -> Values.toNumber(t) },
    )

    fun toFixed(n: Double, digits: Int): String {
        if (!n.isFinite()) return Values.numberToString(n)
        if (abs(n) >= 1e21) return Values.numberToString(n)
        val negative = n < 0
        val text = PlatformBigDecimal(abs(n)).setScale(digits, PlatformRoundingMode.HALF_UP).toPlainString()
        return if (negative && text.any { it in '1'..'9' }) "-$text" else text
    }

    private val functionMethods: Map<String, NativeFunction> = mapOf(
        "call" to native("call", 1) { i, t, a -> i.callFunction(t, arg(a, 0), a.drop(1)) },
        "apply" to native("apply", 2) { i, t, a -> i.callFunction(t, arg(a, 0), if (Values.isNullish(arg(a, 1))) emptyList() else i.iterate(arg(a, 1))) },
        "bind" to native("bind", 1) { _, t, a ->
            val target = t as? JsFunction
            val bound = arg(a, 0)
            val preset = a.drop(1)
            native("bound ${target?.name.orEmpty()}") { interp, _, rest -> interp.callFunction(target, bound, preset + rest) }
        },
        "toString" to native("toString") { _, t, _ -> Values.toStr(t) },
    )

    private fun sortItems(interp: Interpreter, items: MutableList<Any?>, comparator: JsFunction?) {
        val defined = items.filter { it !== Undefined }.toMutableList()
        val undefinedCount = items.size - defined.size
        val cmp: Comparator<Any?> = if (comparator == null) Comparator { x, y -> Values.toStr(x).compareTo(Values.toStr(y)) }
        else Comparator { x, y -> sign(Values.toNumber(interp.callFunction(comparator, Undefined, listOf(x, y))).let { if (it.isNaN()) 0.0 else it }).toInt() }
        val sorted = try { defined.sortedWith(cmp) } catch (e: IllegalArgumentException) { defined }
        items.clear()
        items.addAll(sorted)
        repeat(undefinedCount) { items.add(Undefined) }
    }

    private fun flatten(interp: Interpreter, items: List<Any?>, depth: Double, out: MutableList<Any?>) {
        for (item in items) {
            if (item is JsArray && depth >= 1) flatten(interp, item.items, depth - 1, out) else out += item
            if (out.size > Values.MAX_ARRAY) throw ScriptLimit("Array is too large.")
        }
    }

    private val arrayMethods: Map<String, NativeFunction> = mapOf(
        "push" to native("push", 1) { i, t, a -> selfArray(i, t).let { arr -> if (arr.frozen) throw i.typeError("Cannot add to a frozen array"); if (arr.items.size + a.size > Values.MAX_ARRAY) throw ScriptLimit("Array is too large."); i.allocate(); arr.items.addAll(a.map(Values::normalize)); arr.items.size.toDouble() } },
        "pop" to native("pop") { i, t, _ -> selfArray(i, t).items.let { if (it.isEmpty()) Undefined else it.removeAt(it.lastIndex) } },
        "shift" to native("shift") { i, t, _ -> selfArray(i, t).items.let { if (it.isEmpty()) Undefined else it.removeAt(0) } },
        "unshift" to native("unshift", 1) { i, t, a -> selfArray(i, t).let { arr -> arr.items.addAll(0, a.map(Values::normalize)); arr.items.size.toDouble() } },
        "slice" to native("slice", 2) { i, t, a -> selfArray(i, t).items.let { items -> val from = relativeIndex(arg(a, 0), items.size, 0); val to = relativeIndex(arg(a, 1), items.size, items.size); i.newArray(if (from < to) items.subList(from, to).toList() else emptyList()) } },
        "splice" to native("splice", 2) { i, t, a ->
            val items = selfArray(i, t).items
            val start = relativeIndex(arg(a, 0), items.size, 0)
            val count = if (a.size < 2) items.size - start else Values.toInteger(arg(a, 1)).toInt().coerceIn(0, items.size - start)
            val removed = items.subList(start, start + count).toList()
            repeat(count) { items.removeAt(start) }
            items.addAll(start, a.drop(2).map(Values::normalize))
            i.newArray(removed)
        },
        "concat" to native("concat", 1) { i, t, a -> i.newArray(selfArray(i, t).items + a.flatMap { v -> if (v is JsArray) v.items else listOf(Values.normalize(v)) }) },
        "join" to native("join", 1) { i, t, a ->
            val sep = if (arg(a, 0) === Undefined) "," else Values.toStr(arg(a, 0))
            selfArray(i, t).items.joinToString(sep) { if (Values.isNullish(it)) "" else Values.toStr(Values.toPrimitive(it)) }.also { i.checkString(it.length) }
        },
        "reverse" to native("reverse") { i, t, _ -> selfArray(i, t).also { it.items.reverse() } },
        "toReversed" to native("toReversed") { i, t, _ -> i.newArray(selfArray(i, t).items.reversed()) },
        "sort" to native("sort", 1) { i, t, a -> selfArray(i, t).also { sortItems(i, it.items, arg(a, 0) as? JsFunction) } },
        "toSorted" to native("toSorted", 1) { i, t, a -> i.newArray(selfArray(i, t).items).also { sortItems(i, it.items, arg(a, 0) as? JsFunction) } },
        "indexOf" to native("indexOf", 1) { i, t, a -> selfArray(i, t).items.indexOfFirst { Values.strictEquals(it, arg(a, 0)) }.toDouble() },
        "lastIndexOf" to native("lastIndexOf", 1) { i, t, a -> selfArray(i, t).items.indexOfLast { Values.strictEquals(it, arg(a, 0)) }.toDouble() },
        "includes" to native("includes", 1) { i, t, a -> selfArray(i, t).items.any { Values.sameValueZero(it, arg(a, 0)) } },
        "at" to native("at", 1) { i, t, a -> selfArray(i, t).items.let { items -> Values.toInteger(arg(a, 0)).toInt().let { k -> val idx = if (k < 0) items.size + k else k; if (idx in items.indices) items[idx] else Undefined } } },
        "find" to native("find", 1) { i, t, a -> val f = callback(i, a); selfArray(i, t).items.withIndex().firstOrNull { (k, v) -> Values.truthy(i.callFunction(f, Undefined, listOf(v, k.toDouble(), t))) }?.value ?: Undefined },
        "findIndex" to native("findIndex", 1) { i, t, a -> val f = callback(i, a); selfArray(i, t).items.withIndex().indexOfFirst { (k, v) -> Values.truthy(i.callFunction(f, Undefined, listOf(v, k.toDouble(), t))) }.toDouble() },
        "findLast" to native("findLast", 1) { i, t, a -> val f = callback(i, a); selfArray(i, t).items.withIndex().lastOrNull { (k, v) -> Values.truthy(i.callFunction(f, Undefined, listOf(v, k.toDouble(), t))) }?.value ?: Undefined },
        "findLastIndex" to native("findLastIndex", 1) { i, t, a -> val f = callback(i, a); selfArray(i, t).items.withIndex().indexOfLast { (k, v) -> Values.truthy(i.callFunction(f, Undefined, listOf(v, k.toDouble(), t))) }.toDouble() },
        "filter" to native("filter", 1) { i, t, a -> val f = callback(i, a); i.newArray(selfArray(i, t).items.toList().filterIndexed { k, v -> Values.truthy(i.callFunction(f, Undefined, listOf(v, k.toDouble(), t))) }) },
        "map" to native("map", 1) { i, t, a -> val f = callback(i, a); i.newArray(selfArray(i, t).items.toList().mapIndexed { k, v -> i.callFunction(f, Undefined, listOf(v, k.toDouble(), t)) }) },
        "flatMap" to native("flatMap", 1) { i, t, a ->
            val f = callback(i, a)
            val mapped = selfArray(i, t).items.toList().mapIndexed { k, v -> i.callFunction(f, Undefined, listOf(v, k.toDouble(), t)) }
            val out = mutableListOf<Any?>(); flatten(i, mapped, 1.0, out); i.newArray(out)
        },
        "flat" to native("flat") { i, t, a -> val out = mutableListOf<Any?>(); flatten(i, selfArray(i, t).items, if (arg(a, 0) === Undefined) 1.0 else Values.toNumber(arg(a, 0)), out); i.newArray(out) },
        "forEach" to native("forEach", 1) { i, t, a -> val f = callback(i, a); selfArray(i, t).items.toList().forEachIndexed { k, v -> i.callFunction(f, Undefined, listOf(v, k.toDouble(), t)) }; Undefined },
        "some" to native("some", 1) { i, t, a -> val f = callback(i, a); selfArray(i, t).items.toList().withIndex().any { (k, v) -> Values.truthy(i.callFunction(f, Undefined, listOf(v, k.toDouble(), t))) } },
        "every" to native("every", 1) { i, t, a -> val f = callback(i, a); selfArray(i, t).items.toList().withIndex().all { (k, v) -> Values.truthy(i.callFunction(f, Undefined, listOf(v, k.toDouble(), t))) } },
        "reduce" to native("reduce", 2) { i, t, a ->
            val f = callback(i, a)
            val items = selfArray(i, t).items.toList()
            var start = 0
            var acc: Any? = if (a.size >= 2) arg(a, 1) else { if (items.isEmpty()) throw i.typeError("Reduce of empty array with no initial value"); start = 1; items[0] }
            for (k in start until items.size) acc = i.callFunction(f, Undefined, listOf(acc, items[k], k.toDouble(), t))
            acc
        },
        "reduceRight" to native("reduceRight", 2) { i, t, a ->
            val f = callback(i, a)
            val items = selfArray(i, t).items.toList()
            var end = items.size - 1
            var acc: Any? = if (a.size >= 2) arg(a, 1) else { if (items.isEmpty()) throw i.typeError("Reduce of empty array with no initial value"); end--; items.last() }
            for (k in end downTo 0) acc = i.callFunction(f, Undefined, listOf(acc, items[k], k.toDouble(), t))
            acc
        },
        "fill" to native("fill", 1) { i, t, a -> selfArray(i, t).also { arr -> val from = relativeIndex(arg(a, 1), arr.items.size, 0); val to = relativeIndex(arg(a, 2), arr.items.size, arr.items.size); for (k in from until to) arr.items[k] = arg(a, 0) } },
        "keys" to native("keys") { i, t, _ -> i.newArray(selfArray(i, t).items.indices.map { it.toDouble() }) },
        "values" to native("values") { i, t, _ -> i.newArray(selfArray(i, t).items.toList()) },
        "entries" to native("entries") { i, t, _ -> i.newArray(selfArray(i, t).items.mapIndexed { k, v -> i.newArray(listOf(k.toDouble(), v)) }) },
        "with" to native("with", 2) { i, t, a -> i.newArray(selfArray(i, t).items.toMutableList().also { items -> val k = Values.toInteger(arg(a, 0)).toInt().let { if (it < 0) items.size + it else it }; if (k !in items.indices) throw i.error("RangeError", "Invalid index"); items[k] = arg(a, 1) }) },
        "toString" to native("toString") { i, t, _ -> Values.toStr(t) },
    )

    private val mapMethods: Map<String, NativeFunction> = mapOf(
        "get" to native("get", 1) { _, t, a -> (t as JsMap).entries[mapKey(arg(a, 0))]?.second ?: Undefined },
        "set" to native("set", 2) { _, t, a -> (t as JsMap).also { it.entries[mapKey(arg(a, 0))] = arg(a, 0) to arg(a, 1) } },
        "has" to native("has", 1) { _, t, a -> (t as JsMap).entries.containsKey(mapKey(arg(a, 0))) },
        "delete" to native("delete", 1) { _, t, a -> (t as JsMap).entries.remove(mapKey(arg(a, 0))) != null },
        "clear" to native("clear") { _, t, _ -> (t as JsMap).entries.clear(); Undefined },
        "keys" to native("keys") { i, t, _ -> i.newArray((t as JsMap).entries.values.map { it.first }) },
        "values" to native("values") { i, t, _ -> i.newArray((t as JsMap).entries.values.map { it.second }) },
        "entries" to native("entries") { i, t, _ -> i.newArray((t as JsMap).entries.values.map { i.newArray(listOf(it.first, it.second)) }) },
        "forEach" to native("forEach", 1) { i, t, a -> val f = callback(i, a); (t as JsMap).entries.values.toList().forEach { (k, v) -> i.callFunction(f, Undefined, listOf(v, k, t)) }; Undefined },
    )

    private val setMethods: Map<String, NativeFunction> = mapOf(
        "add" to native("add", 1) { _, t, a -> (t as JsSet).also { it.entries[mapKey(arg(a, 0))] = arg(a, 0) to arg(a, 0) } },
        "has" to native("has", 1) { _, t, a -> (t as JsSet).entries.containsKey(mapKey(arg(a, 0))) },
        "delete" to native("delete", 1) { _, t, a -> (t as JsSet).entries.remove(mapKey(arg(a, 0))) != null },
        "clear" to native("clear") { _, t, _ -> (t as JsSet).entries.clear(); Undefined },
        "values" to native("values") { i, t, _ -> i.newArray((t as JsSet).entries.values.map { it.first }) },
        "keys" to native("keys") { i, t, _ -> i.newArray((t as JsSet).entries.values.map { it.first }) },
        "entries" to native("entries") { i, t, _ -> i.newArray((t as JsSet).entries.values.map { i.newArray(listOf(it.first, it.first)) }) },
        "forEach" to native("forEach", 1) { i, t, a -> val f = callback(i, a); (t as JsSet).entries.values.toList().forEach { (v, _) -> i.callFunction(f, Undefined, listOf(v, v, t)) }; Undefined },
    )

    private val regexMethods: Map<String, NativeFunction> = mapOf(
        "test" to native("test", 1) { _, t, a -> (t as JsRegExp).pattern.containsMatchIn(Values.toStr(arg(a, 0))) },
        "exec" to native("exec", 1) { i, t, a ->
            val re = t as JsRegExp
            val s = Values.toStr(arg(a, 0))
            val m = re.pattern.find(s, if (re.global) re.lastIndex.coerceIn(0, s.length) else 0)
            if (m == null) { re.lastIndex = 0; Null } else { if (re.global) re.lastIndex = m.range.last + 1; matchArray(i, m) }
        },
        "toString" to native("toString") { _, t, _ -> Values.toStr(t) },
    )

    fun parseInt(text: String, radixArg: Any?): Double {
        var s = text.trim()
        var sign = 1.0
        if (s.startsWith("-")) { sign = -1.0; s = s.substring(1) } else if (s.startsWith("+")) s = s.substring(1)
        var radix = if (radixArg === Undefined) 10 else Values.toInt32(radixArg).let { if (it == 0) 10 else it }
        if ((radix == 16 || radixArg === Undefined) && (s.startsWith("0x") || s.startsWith("0X"))) { radix = 16; s = s.substring(2) }
        if (radix !in 2..36) return Double.NaN
        val digits = s.takeWhile { PlatformCharacter.digit(it, radix) >= 0 }
        if (digits.isEmpty()) return Double.NaN
        return sign * digits.fold(0.0) { acc, c -> acc * radix + PlatformCharacter.digit(c, radix) }
    }

    fun parseFloat(text: String): Double {
        val s = text.trim()
        val m = platformRegex("^[+-]?(Infinity|\\d+\\.?\\d*([eE][+-]?\\d+)?|\\.\\d+([eE][+-]?\\d+)?)").find(s) ?: return Double.NaN
        return Values.parseNumber(m.value)
    }

    /** A readable rendering of a value for console output. */
    fun inspect(interp: Interpreter, value: Any?, depth: Int = 0): String = when (val v = Values.normalize(value)) {
        is String -> if (depth == 0) v else "'$v'"
        is JsArray -> if (depth > 2) "[Array]" else "[ " + v.items.take(50).joinToString(", ") { inspect(interp, it, depth + 1) } + " ]"
        is JsFunction -> "[Function: ${v.name.ifEmpty { "(anonymous)" }}]"
        is JsDate -> Dates.toIsoString(v.time)
        is JsMap, is JsSet -> v.className
        is JsObject -> if (v.className == "Error") Values.toStr(v) else if (depth > 2) "[Object]" else "{ " + v.keys().take(50).joinToString(", ") { "$it: ${inspect(interp, v.get(it), depth + 1)}" } + " }"
        else -> Values.toStr(v)
    }
}

/** JSON.stringify and JSON.parse. */
internal object Json {
    fun stringify(interp: Interpreter, value: Any?, replacer: Any?, space: Any?): String? {
        val indent = when (val s = Values.normalize(space)) {
            is Double -> " ".repeat(s.toInt().coerceIn(0, 10))
            is String -> s.take(10)
            else -> ""
        }
        val seen = newIdentitySet(PlatformIdentityHashMap<Any, Boolean>())
        val replace = replacer as? JsFunction
        val allow = (replacer as? JsArray)?.items?.map { Values.toStr(it) }?.toSet()
        val out = StringBuilder()
        fun write(holder: Any?, key: String, raw: Any?, level: Int): Boolean {
            var v = Values.normalize(raw)
            if (v is JsDate) v = Dates.toIsoString(v.time)
            if (replace != null) v = Values.normalize(interp.callFunction(replace, holder, listOf(key, v)))
            when (v) {
                Undefined, is JsFunction -> return false
                Null -> out.append("null")
                is Boolean -> out.append(v)
                is Double -> out.append(if (v.isFinite()) Values.numberToString(v) else "null")
                is String -> out.append(JsonPrimitive(v).toString())
                is JsArray -> {
                    if (!seen.add(v)) throw interp.typeError("Converting circular structure to JSON")
                    out.append('[')
                    v.items.forEachIndexed { k, item ->
                        if (k > 0) out.append(',')
                        if (indent.isNotEmpty()) out.append('\n').append(indent.repeat(level + 1))
                        if (!write(v, k.toString(), item, level + 1)) out.append("null")
                    }
                    if (indent.isNotEmpty() && v.items.isNotEmpty()) out.append('\n').append(indent.repeat(level))
                    out.append(']')
                    seen.remove(v)
                }
                is JsMap, is JsSet -> out.append("{}")
                is JsObject -> {
                    if (!seen.add(v)) throw interp.typeError("Converting circular structure to JSON")
                    out.append('{')
                    var first = true
                    for (k in v.keys()) {
                        if (allow != null && k !in allow) continue
                        val mark = out.length
                        if (!first) out.append(',')
                        if (indent.isNotEmpty()) out.append('\n').append(indent.repeat(level + 1))
                        out.append(JsonPrimitive(k).toString()).append(':')
                        if (indent.isNotEmpty()) out.append(' ')
                        if (!write(v, k, v.get(k), level + 1)) out.setLength(mark) else first = false
                    }
                    if (indent.isNotEmpty() && !first) out.append('\n').append(indent.repeat(level))
                    out.append('}')
                    seen.remove(v)
                }
                else -> out.append(JsonPrimitive(Values.toStr(v)).toString())
            }
            interp.checkString(out.length)
            return true
        }
        return if (write(Undefined, "", value, 0)) out.toString() else null
    }

    fun fromElement(interp: Interpreter, element: JsonElement): Any? = when (element) {
        is JsonNull -> Null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" -> true
            element.content == "false" -> false
            else -> element.content.toDoubleOrNull() ?: Double.NaN
        }
        is JsonArray -> interp.newArray(element.map { fromElement(interp, it) })
        is KJsonObject -> interp.newObject().also { o -> element.forEach { (k, v) -> o.set(k, fromElement(interp, v)) } }
    }
}

/** Number formatting shared by toLocaleString and Intl.NumberFormat (en-US conventions). */
internal object Intl {
    fun install(interp: Interpreter) {
        val intl = JsObject()
        intl.set("NumberFormat", NativeFunction("NumberFormat", 2) { _, _, a ->
            val options = if (a.size > 1) Values.normalize(a[1]) as? JsObject else null
            JsObject().apply {
                set("format", NativeFunction("format", 1) { _, _, b -> formatNumber(Values.toNumber(if (b.isEmpty()) Undefined else b[0]), options) })
                set("resolvedOptions", NativeFunction("resolvedOptions") { i, _, _ -> i.newObject().also { it.set("locale", "en-US") } })
            }
        })
        intl.set("DateTimeFormat", NativeFunction("DateTimeFormat", 2) { _, _, a ->
            val options = if (a.size > 1) Values.normalize(a[1]) as? JsObject else null
            JsObject().apply {
                set("format", NativeFunction("format", 1) { _, _, b -> Dates.format((Values.normalize(b.firstOrNull()) as? JsDate)?.time ?: PlatformSystem.currentTimeMillis().toDouble(), options, "datetime") })
                set("resolvedOptions", NativeFunction("resolvedOptions") { i, _, _ -> i.newObject().also { it.set("timeZone", Dates.zone) } })
            }
        })
        intl.set("PluralRules", NativeFunction("PluralRules", 2) { _, _, _ ->
            JsObject().apply { set("select", NativeFunction("select", 1) { _, _, b -> if (Values.toNumber(b.firstOrNull()) == 1.0) "one" else "other" }) }
        })
        interp.globals.declare("Intl", intl, constant = true)
    }

    fun formatNumber(n: Double, options: JsObject?): String {
        if (n.isNaN()) return "NaN"
        if (n.isInfinite()) return if (n > 0) "∞" else "-∞"
        fun opt(key: String) = options?.get(key)?.takeIf { it !== Undefined }
        val style = opt("style")?.let(Values::toStr) ?: "decimal"
        val notation = opt("notation")?.let(Values::toStr) ?: "standard"
        val currency = opt("currency")?.let(Values::toStr)?.uppercase()
        var minFraction = opt("minimumFractionDigits")?.let { Values.toNumber(it).toInt() }
        var maxFraction = opt("maximumFractionDigits")?.let { Values.toNumber(it).toInt() }
        val grouping = opt("useGrouping")?.let(Values::truthy) ?: true
        var value = n
        var suffix = ""
        var prefix = ""
        when (style) {
            "percent" -> { value = n * 100; suffix = "%"; if (maxFraction == null) maxFraction = max(0, minFraction ?: 0) }
            "currency" -> {
                val digits = runCatching { PlatformCurrency.defaultFractionDigits(currency ?: "USD") }.getOrDefault(2)
                if (minFraction == null) minFraction = if (maxFraction != null) min(digits, maxFraction) else digits
                if (maxFraction == null) maxFraction = max(minFraction, digits)
                prefix = currencySymbol(currency ?: "USD", opt("currencyDisplay")?.let(Values::toStr))
            }
            "unit" -> suffix = opt("unit")?.let { " " + Values.toStr(it).replace("-per-", "/") }.orEmpty()
        }
        if (notation == "compact") {
            val units = listOf(1e12 to "T", 1e9 to "B", 1e6 to "M", 1e3 to "K")
            val hit = units.firstOrNull { abs(value) >= it.first }
            if (hit != null) {
                value /= hit.first
                suffix = hit.second + suffix
                if (maxFraction == null) maxFraction = if (abs(value) < 10) 1 else 0
            } else if (maxFraction == null) maxFraction = 0
        }
        val minF = (minFraction ?: 0).coerceIn(0, 20)
        val maxF = (maxFraction ?: max(minF, 3)).coerceIn(minF, 20)
        val format = PlatformDecimalFormat("#,##0", PlatformDecimalFormatSymbols()).apply {
            minimumFractionDigits = minF
            maximumFractionDigits = maxF
            isGroupingUsed = grouping
        }
        val body = format.format(abs(value))
        val negative = value < 0 && body.any { it in '1'..'9' }
        return (if (negative) "-" else "") + prefix + body + suffix
    }

    private fun currencySymbol(code: String, display: String?): String = when {
        display == "code" -> "$code "
        display == "name" -> ""
        code == "USD" -> "$"; code == "EUR" -> "€"; code == "GBP" -> "£"; code == "JPY" -> "¥"; code == "INR" -> "₹"
        code == "CNY" -> "CN¥"; code == "KRW" -> "₩"; code == "CAD" -> "CA$"; code == "AUD" -> "A$"; code == "MXN" -> "MX$"
        code == "BRL" -> "R$"; code == "CHF" -> "CHF "
        else -> runCatching { PlatformCurrency.symbol(code) }.getOrDefault(code).let { if (it.length > 1 && it.all(Char::isLetter)) "$it " else it }
    }
}

