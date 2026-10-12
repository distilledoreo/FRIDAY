package com.localfirst.assistant.genui.script

import kotlin.math.pow

/** Binary operators with JS coercion rules. */
object Operators {
    fun binary(interp: Interpreter, op: String, a: Any?, b: Any?): Any? = when (op) {
        "+" -> {
            val x = Values.toPrimitive(a)
            val y = Values.toPrimitive(b)
            if (x is String || y is String) {
                val s = Values.toStr(x) + Values.toStr(y)
                interp.checkString(s.length)
                s
            } else Values.toNumber(x) + Values.toNumber(y)
        }
        "-" -> num(a) - num(b)
        "*" -> num(a) * num(b)
        "/" -> num(a) / num(b)
        "%" -> { val x = num(a); val y = num(b); if (y == 0.0 || x.isInfinite()) Double.NaN else x % y }
        "**" -> num(a).pow(num(b))
        "==" -> Values.looseEquals(a, b)
        "!=" -> !Values.looseEquals(a, b)
        "===" -> Values.strictEquals(a, b)
        "!==" -> !Values.strictEquals(a, b)
        "<" -> compare(a, b) { it < 0 }
        ">" -> compare(a, b) { it > 0 }
        "<=" -> compare(a, b, nanResult = false) { it <= 0 }
        ">=" -> compare(a, b, nanResult = false) { it >= 0 }
        "&" -> (Values.toInt32(a) and Values.toInt32(b)).toDouble()
        "|" -> (Values.toInt32(a) or Values.toInt32(b)).toDouble()
        "^" -> (Values.toInt32(a) xor Values.toInt32(b)).toDouble()
        "<<" -> (Values.toInt32(a) shl (Values.toInt32(b) and 31)).toDouble()
        ">>" -> (Values.toInt32(a) shr (Values.toInt32(b) and 31)).toDouble()
        ">>>" -> (Values.toUint32(a) ushr (Values.toInt32(b) and 31)).toDouble()
        "instanceof" -> instanceOf(a, b)
        "in" -> when (val o = Values.normalize(b)) {
            is JsObject -> o.has(Values.propertyKey(a))
            else -> throw interp.typeError("Cannot use 'in' to search for a key in ${Values.toStr(b)}")
        }
        else -> throw interp.error("SyntaxError", "Unknown operator $op")
    }

    private fun num(v: Any?) = Values.toNumber(Values.toPrimitive(v, "number"))

    private inline fun compare(a: Any?, b: Any?, nanResult: Boolean = false, test: (Int) -> Boolean): Boolean {
        val x = Values.toPrimitive(a, "number")
        val y = Values.toPrimitive(b, "number")
        if (x is String && y is String) return test(x.compareTo(y))
        val m = Values.toNumber(x)
        val n = Values.toNumber(y)
        if (m.isNaN() || n.isNaN()) return nanResult
        return test(m.compareTo(n))
    }

    private fun instanceOf(a: Any?, ctor: Any?): Boolean {
        val c = ctor as? JsFunction ?: return false
        return when (c.name) {
            "Array" -> a is JsArray
            "Date" -> a is JsDate
            "RegExp" -> a is JsRegExp
            "Map" -> a is JsMap
            "Set" -> a is JsSet
            "Function" -> a is JsFunction
            "Object" -> a is JsObject
            "Error", "TypeError", "RangeError", "ReferenceError", "SyntaxError" -> {
                val obj = a as? JsObject ?: return false
                if (obj.className != "Error") return false
                c.name == "Error" || Values.toStr(obj.get("name")) == c.name
            }
            else -> {
                val proto = c.get("prototype") as? JsObject ?: return false
                var p = (a as? JsObject)?.proto
                while (p != null) { if (p === proto) return true; p = p.proto }
                false
            }
        }
    }
}
