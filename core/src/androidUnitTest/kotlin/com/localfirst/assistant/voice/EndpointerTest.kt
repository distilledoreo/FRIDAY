package com.localfirst.assistant.voice

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointerTest {
    private val frame = 320 // 20 ms at 16 kHz

    /** A 20 ms frame of a 220 Hz tone at [db] dBFS (silence below -85). */
    private fun tone(db: Double, phase: Int = 0): ShortArray {
        if (db < -85) return ShortArray(frame)
        val amp = 32768.0 * 10.0.pow(db / 20.0) * 1.414
        return ShortArray(frame) { i -> (amp * sin(2 * PI * 220 * (phase * frame + i) / 16_000)).toInt().toShort() }
    }

    private fun Endpointer.feed(db: Double, frames: Int): List<Endpointer.Event> =
        (0 until frames).mapNotNull { accept(tone(db, it)) }

    @Test
    fun capturesAnUtteranceWithPreRollAndEndsAfterSilence() {
        val ep = Endpointer()
        assertTrue(ep.feed(-65.0, 100).isEmpty())
        val onset = ep.feed(-25.0, 10)
        assertEquals(listOf(Endpointer.Event.Onset), onset)
        val during = ep.feed(-25.0, 50)
        assertTrue(during.isEmpty())
        val end = ep.feed(-65.0, 50)
        val utterance = end.single() as Endpointer.Event.Utterance
        // 60 loud frames plus up to 800 ms of pre-roll and a little trailing silence.
        val ms = utterance.pcm.size / 16
        assertTrue("utterance was $ms ms", ms in 1_400..2_500)
    }

    @Test
    fun shortBlipsAreDiscardedAndQuietSyllablesDontEndSpeech() {
        val ep = Endpointer()
        ep.feed(-65.0, 100)
        // 240 ms burst: confirms onset, but too little voiced audio to keep.
        val blip = ep.feed(-25.0, 12) + ep.feed(-65.0, 60)
        assertEquals(listOf(Endpointer.Event.Onset, Endpointer.Event.Discarded), blip)

        // Speech with a softer middle (-38 dB) stays one utterance.
        val events = ep.feed(-25.0, 20) + ep.feed(-38.0, 30) + ep.feed(-25.0, 20) + ep.feed(-65.0, 60)
        assertEquals(1, events.count { it is Endpointer.Event.Utterance })
    }

    @Test
    fun echoWhilePlayingNeedsALouderVoice() {
        val ep = Endpointer()
        ep.feed(-60.0, 100)
        ep.playing = true
        // Residual echo 13 dB above the floor: below the playing threshold (15 + 6).
        assertTrue(ep.feed(-47.0, 100).none { it == Endpointer.Event.Onset })
        assertTrue(ep.feed(-20.0, 15).contains(Endpointer.Event.Onset))
    }

    @Test
    fun wavRoundTrips() {
        val pcm = ShortArray(1000) { (it * 31).toShort() }
        val decoded = Wav.decode(Wav.encode(pcm, 24_000))
        assertEquals(24_000, decoded.sampleRate)
        assertTrue(pcm.contentEquals(decoded.pcm))
        assertTrue(runCatching { Wav.decode("nope".toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
    }
}
