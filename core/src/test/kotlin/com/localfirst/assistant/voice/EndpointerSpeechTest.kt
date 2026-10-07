package com.localfirst.assistant.voice

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Endpointing on real speech (a Kokoro sample: "Text Jordan Lee: I'm running about ten minutes late, sorry!"). */
class EndpointerSpeechTest {
    private val speech = Wav.decode(javaClass.getResource("/audio/speech-16k.wav")!!.readBytes())

    private fun noise(samples: Int, amplitude: Int, random: Random) =
        ShortArray(samples) { (random.nextInt(-amplitude, amplitude + 1)).toShort() }

    @Test
    fun aSpokenSentenceIsOneUtteranceWithItsStartIntact() {
        assertEquals(16_000, speech.sampleRate)
        val random = Random(7)
        // 1.5 s of quiet room noise (~-56 dBFS), the sentence, then 1.5 s of noise.
        val stream = noise(24_000, 60, random) + speech.pcm.map { (it + random.nextInt(-60, 61)).toShort() }.toShortArray() +
            noise(24_000, 60, random)
        val endpointer = Endpointer()
        val events = stream.toList().chunked(320).filter { it.size == 320 }.mapNotNull { endpointer.accept(it.toShortArray()) }

        assertEquals(1, events.count { it == Endpointer.Event.Onset })
        val utterance = events.filterIsInstance<Endpointer.Event.Utterance>().single()
        val firstLoud = speech.pcm.indexOfFirst { kotlin.math.abs(it.toInt()) > 1_000 }
        val speechStartInStream = 24_000 + firstLoud
        val utteranceStart = (utterance.startedAtFrame - 1) * 320
        assertTrue("utterance starts ${speechStartInStream - utteranceStart} samples before the speech", utteranceStart <= speechStartInStream)
        // Covers the whole sentence and stops soon after it ends.
        assertTrue(utterance.pcm.size >= speech.pcm.size - firstLoud)
        assertTrue(utterance.pcm.size <= speech.pcm.size + 16_000)
    }
}
