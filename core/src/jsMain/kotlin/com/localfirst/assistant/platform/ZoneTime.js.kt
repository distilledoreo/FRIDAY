package com.localfirst.assistant.platform

actual object ZoneTime {
    actual fun system(): String = js("Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'").unsafeCast<String>()
    actual fun valid(zone: String): Boolean = zone.isNotBlank() && try { js("new Intl.DateTimeFormat('en-US', {timeZone: zone})"); true } catch (e: Throwable) { false }
    private fun id(zone: String) = if (valid(zone)) zone else system()
    /** Fields of [t] on the wall clock in [zone], as a UTC timestamp with the same fields. */
    private fun wallMillis(t: Double, zone: String): Double {
        val parts = js("new Intl.DateTimeFormat('en-US', {timeZone: zone, year:'numeric',month:'numeric',day:'numeric',hour:'numeric',minute:'numeric',second:'numeric',hourCycle:'h23'}).formatToParts(new Date(t))")
        val fields = mutableMapOf<String, Int>()
        for (i in 0 until parts.length.unsafeCast<Int>()) parts[i].value.unsafeCast<String>().toIntOrNull()?.let { fields[parts[i].type.unsafeCast<String>()] = it }
        val year = fields.getValue("year"); val month = fields.getValue("month") - 1; val day = fields.getValue("day")
        val hour = fields.getValue("hour") % 24; val minute = fields.getValue("minute"); val second = fields.getValue("second")
        return js("Date.UTC(year, month, day, hour, minute, second)").unsafeCast<Double>()
    }
    actual fun wall(epochMillis: Long, zone: String): WallTime {
        val wall = wallMillis(epochMillis.toDouble() - epochMillis.mod(1000L), id(zone))
        val d = js("new Date(wall)")
        val weekday = d.getUTCDay().unsafeCast<Int>()
        return WallTime(d.getUTCFullYear().unsafeCast<Int>(), d.getUTCMonth().unsafeCast<Int>() + 1, d.getUTCDate().unsafeCast<Int>(),
            d.getUTCHours().unsafeCast<Int>(), d.getUTCMinutes().unsafeCast<Int>(), if (weekday == 0) 7 else weekday)
    }
    actual fun epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: String): Long {
        val z = id(zone); val m = month - 1
        val target = js("Date.UTC(year, m, day, hour, minute)").unsafeCast<Double>()
        var result = target
        repeat(3) { result = target - (wallMillis(result, z) - result) }
        // In a skipped hour the wall clock never shows the target; the instant found lands after the gap.
        return result.toLong()
    }
    actual fun iso(epochMillis: Long, zone: String): String {
        val z = id(zone); val t = epochMillis.toDouble() - epochMillis.mod(1000L)
        val offset = ((wallMillis(t, z) - t) / 60000).toInt()
        val w = wall(epochMillis, z)
        fun two(n: Int) = n.toString().padStart(2, '0')
        val sign = if (offset < 0) "-" else "+"; val o = kotlin.math.abs(offset)
        val seconds = ((t / 1000).toLong().mod(60L)).toInt()
        return "${w.year.toString().padStart(4, '0')}-${two(w.month)}-${two(w.day)}T${two(w.hour)}:${two(w.minute)}:${two(seconds)}$sign${two(o / 60)}:${two(o % 60)}"
    }
    actual fun parseOffset(text: String): Long? {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})$").matches(text.trim())) return null
        val t = js("Date.parse(text)").unsafeCast<Double>()
        return if (t.isNaN()) null else t.toLong()
    }
    actual fun parseLocal(text: String, zone: String): Long? {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::\\d{2}(?:\\.\\d+)?)?$").matchEntire(text.trim()) ?: return null
        val g = m.groupValues.drop(1).map(String::toInt)
        return epoch(g[0], g[1], g[2], g[3], g[4], zone)
    }
}
