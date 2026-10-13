package com.localfirst.assistant.genui

import com.localfirst.assistant.platform.PlatformMath

/** Package-local `Math` so shared code compiles on JS (delegates to the platform layer). */
internal object Math {
    val PI: Double get() = PlatformMath.PI
    val E: Double get() = PlatformMath.E
    fun pow(a: Double, b: Double) = PlatformMath.pow(a, b)
    fun sqrt(v: Double) = PlatformMath.sqrt(v)
    fun cbrt(v: Double) = PlatformMath.cbrt(v)
    fun exp(v: Double) = PlatformMath.exp(v)
    fun log(v: Double) = PlatformMath.log(v)
    fun log10(v: Double) = PlatformMath.log10(v)
    fun sin(v: Double) = PlatformMath.sin(v)
    fun cos(v: Double) = PlatformMath.cos(v)
    fun tan(v: Double) = PlatformMath.tan(v)
    fun atan(v: Double) = PlatformMath.atan(v)
    fun atan2(y: Double, x: Double) = PlatformMath.atan2(y, x)
    fun ceil(v: Double) = PlatformMath.ceil(v)
    fun floor(v: Double) = PlatformMath.floor(v)
    fun round(v: Double) = PlatformMath.round(v).toDouble()
    fun abs(v: Double) = PlatformMath.abs(v)
    fun sign(v: Double) = PlatformMath.sign(v)
    fun max(a: Double, b: Double) = if (a >= b) a else b
    fun min(a: Double, b: Double) = if (a <= b) a else b
}
