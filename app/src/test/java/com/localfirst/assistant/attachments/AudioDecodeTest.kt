package com.localfirst.assistant.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioDecodeTest {
    private fun sine(rate: Int, seconds: Int, hz: Double = 440.0) =
        ShortArray(rate * seconds) { i -> (sin(2 * PI * hz * i / rate) * 20000).toInt().toShort() }

    @Test
    fun resamples48kTo16k() {
        val out = AudioDecode.resampleTo16k(sine(48_000, 1), 48_000)
        assertEquals(16_000, out.size)
        // Peaks survive resampling.
        assertTrue(out.max() > 15000)
        assertTrue(out.min() < -15000)
    }

    @Test
    fun keeps16kAndCapsTwoMinutes() {
        assertEquals(8_000, AudioDecode.resampleTo16k(sine(16_000, 1).copyOf(8_000), 16_000).size)
        val long = AudioDecode.resampleTo16k(ShortArray(16_000 * 200) { 1 }, 16_000)
        assertEquals(16_000 * 120, long.size)
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals(0, AudioDecode.resampleTo16k(ShortArray(0), 44_100).size)
    }
}
