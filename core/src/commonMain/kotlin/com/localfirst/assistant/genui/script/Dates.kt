package com.localfirst.assistant.genui.script

import com.localfirst.assistant.platform.PlatformTime
import com.localfirst.assistant.platform.PlatformVolatile
import kotlin.math.floor

/**
 * JS Date for the sandbox. The time-zone math lives behind [PlatformTime]
 * (java.time on JVM, native Date on JS); this file wires the JS Date prototype.
 */
object Dates {
    @PlatformVolatile var zone: String
        get() = PlatformTime.zone
        set(value) { PlatformTime.zone = value }
    /** The clock behind `Date.now()` and `new Date()`. */
    @PlatformVolatile var now: () -> Long = PlatformTime::nowMillis

    fun toIsoString(t: Double): String = PlatformTime.isoString(t)
    fun toDisplayString(t: Double): String = PlatformTime.displayString(t)
    fun parse(text: String): Double = PlatformTime.parse(text)
    private fun fromParts(parts: List<Double>, utc: Boolean): Double = PlatformTime.fromParts(parts, utc)

    fun install(interp: Interpreter) {
        val proto = JsObject()
        fun date(self: Any?): JsDate = self as? JsDate ?: throw interp.typeError("this is not a Date object.")
        fun getter(name: String, utc: Boolean, field: String) =
            proto.set(name, NativeFunction(name) { _, t, _ -> val d = date(t); if (d.time.isNaN()) Double.NaN else PlatformTime.field(d.time, utc, field).toDouble() })
        for (utc in listOf(false, true)) {
            val u = if (utc) "UTC" else ""
            getter("get${u}FullYear", utc, "FullYear")
            getter("get${u}Month", utc, "Month")
            getter("get${u}Date", utc, "Date")
            getter("get${u}Day", utc, "Day")
            getter("get${u}Hours", utc, "Hours")
            getter("get${u}Minutes", utc, "Minutes")
            getter("get${u}Seconds", utc, "Seconds")
            getter("get${u}Milliseconds", utc, "Milliseconds")
        }
        proto.set("getYear", NativeFunction("getYear") { _, t, _ -> (PlatformTime.field(date(t).time, false, "FullYear") - 1900).toDouble() })
        proto.set("getTime", NativeFunction("getTime") { _, t, _ -> date(t).time })
        proto.set("valueOf", NativeFunction("valueOf") { _, t, _ -> date(t).time })
        proto.set("getTimezoneOffset", NativeFunction("getTimezoneOffset") { _, t, _ -> (-PlatformTime.field(date(t).time, false, "TimezoneOffset")).toDouble() })
        proto.set("toISOString", NativeFunction("toISOString") { i, t, _ -> date(t).time.let { if (it.isNaN()) throw i.error("RangeError", "Invalid time value") else toIsoString(it) } })
        proto.set("toJSON", NativeFunction("toJSON") { _, t, _ -> date(t).time.let { if (it.isNaN()) Null else toIsoString(it) } })
        proto.set("toString", NativeFunction("toString") { _, t, _ -> toDisplayString(date(t).time) })
        proto.set("toDateString", NativeFunction("toDateString") { _, t, _ -> PlatformTime.dateString(date(t).time) })
        proto.set("toTimeString", NativeFunction("toTimeString") { _, t, _ -> PlatformTime.timeString(date(t).time) })
        proto.set("toUTCString", NativeFunction("toUTCString") { _, t, _ -> PlatformTime.utcString(date(t).time) })
        proto.set("toLocaleDateString", NativeFunction("toLocaleDateString", 2) { _, t, a -> format(date(t).time, a.getOrNull(1) as? JsObject, "date") })
        proto.set("toLocaleTimeString", NativeFunction("toLocaleTimeString", 2) { _, t, a -> format(date(t).time, a.getOrNull(1) as? JsObject, "time") })
        proto.set("toLocaleString", NativeFunction("toLocaleString", 2) { _, t, a -> format(date(t).time, a.getOrNull(1) as? JsObject, "datetime") })
        fun set(name: String, utc: Boolean, map: (List<Double>) -> Array<Double?>) =
            proto.set(name, NativeFunction(name, 1) { _, t, a ->
                val d = date(t)
                val values = a.map { Values.toNumber(it) }
                d.time = if (d.time.isNaN() || values.any { it.isNaN() }) Double.NaN
                else { val v = map(values); PlatformTime.setFields(d.time, utc, v[0], v[1], v[2], v[3], v[4], v[5], v[6]) }
                d.time
            })
        for (utc in listOf(false, true)) {
            val u = if (utc) "UTC" else ""
            set("set${u}FullYear", utc) { v -> arrayOf(v.getOrNull(0), v.getOrNull(1), v.getOrNull(2), null, null, null, null) }
            set("set${u}Month", utc) { v -> arrayOf(null, v.getOrNull(0), v.getOrNull(1), null, null, null, null) }
            set("set${u}Date", utc) { v -> arrayOf(null, null, v.getOrNull(0), null, null, null, null) }
            set("set${u}Hours", utc) { v -> arrayOf(null, null, null, v.getOrNull(0), v.getOrNull(1), null, null) }
            set("set${u}Minutes", utc) { v -> arrayOf(null, null, null, null, v.getOrNull(0), null, null) }
            set("set${u}Seconds", utc) { v -> arrayOf(null, null, null, null, null, v.getOrNull(0), null) }
            set("set${u}Milliseconds", utc) { v -> arrayOf(null, null, null, null, null, null, v.getOrNull(0)) }
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
        val opts = buildMap {
            for (key in listOf("timeZone", "dateStyle", "timeStyle", "weekday", "year", "month", "day", "hour", "minute", "second", "hour12", "hourCycle", "timeZoneName")) {
                val v = options?.get(key)?.takeIf { it !== Undefined }?.let(Values::toStr) ?: continue
                put(key, v)
            }
        }
        return PlatformTime.format(t, opts, kind)
    }
}
