package com.localfirst.assistant.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the shipped wake-phrase models on short clips of FRIDAY's own voice. The
 * reference openWakeWord package peaks at 0.876 on the positive clip, so this
 * port should land on the same score.
 */
class WakeWordDetectorTest {
    private fun model(name: String) = File("src/main/assets/wakeword/$name").readBytes()
    private val detector = WakeWordDetector(model("melspectrogram.onnx"), model("embedding_model.onnx"), model("hey_friday.onnx"))

    @After fun close() = detector.close()

    private fun clip(name: String): ShortArray {
        val bytes = javaClass.getResourceAsStream("/wakeword/$name.wav")!!.use { it.readBytes() }
        val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(pcm.remaining()).also { pcm.get(it) }
    }

    private class Heard(val woke: Boolean, val peak: Float)

    /** Two seconds of silence first, as when the listener has been running a while. */
    private fun play(audio: ShortArray): Heard {
        val all = ShortArray(2 * WakeWordDetector.SAMPLE_RATE) + audio
        var woke = false
        var peak = 0f
        for (start in 0..all.size - WakeWordDetector.FRAME step WakeWordDetector.FRAME) {
            woke = detector.accept(all.copyOfRange(start, start + WakeWordDetector.FRAME)) || woke
            peak = maxOf(peak, detector.score)
        }
        return Heard(woke, peak)
    }

    @Test fun hearsTheWakePhraseWithTheReferenceScore() {
        val heard = play(clip("hey_friday"))
        assertTrue(heard.woke)
        assertEquals(0.876f, heard.peak, 0.03f)
    }

    @Test fun ignoresOtherSpeechEvenWithFriday() {
        for (name in listOf("see_you_friday", "good_morning")) {
            val heard = play(clip(name))
            assertFalse(name, heard.woke)
            assertTrue("$name peaked at ${heard.peak}", heard.peak < 0.2f)
        }
    }

    @Test fun silenceAndFaintNoiseSkipTheCostlyStep() {
        play(ShortArray(10 * WakeWordDetector.SAMPLE_RATE))
        val random = Random(7)
        play(ShortArray(10 * WakeWordDetector.SAMPLE_RATE) { random.nextInt(-30, 30).toShort() })
        assertEquals(0, detector.embeddingRuns)
        assertEquals(0, detector.melRuns)
    }

    @Test fun speechWakesTheModelOnlyAroundIt() {
        val audio = clip("good_morning")
        play(audio + ShortArray(10 * WakeWordDetector.SAMPLE_RATE))
        val frames = audio.size / WakeWordDetector.FRAME
        // One embedding per frame while there's sound, a backfill when it starts, then two seconds of tail.
        assertTrue("ran ${detector.embeddingRuns} for $frames frames", detector.embeddingRuns <= frames + 16 + 25)
    }
}
