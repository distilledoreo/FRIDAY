package com.localfirst.assistant.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Finds utterances in a continuous mic stream: when speech starts, when it
 * ends, and the audio in between. Audio from just before the onset is kept,
 * so the first word survives even though onset needs a few frames to confirm.
 *
 * Feed fixed-size PCM16 frames to [accept]. It tracks the background level
 * between utterances and uses a stricter threshold while the assistant is
 * talking ([playing]), when leftover echo is the main risk.
 */
class Endpointer(
    private val sampleRate: Int = 16_000,
    private val frameMs: Int = 20,
    private val margin: Double = 15.0,
    private val minDbfs: Double = -45.0,
    private val playingExtraMargin: Double = 6.0,
    onsetMs: Int = 200,
    endSilenceMs: Int = 750,
    preRollMs: Int = 800,
    maxUtteranceMs: Int = 30_000,
    minUtteranceMs: Int = 300,
) {
    sealed interface Event {
        /** Speech started (use for barge-in). */
        data object Onset : Event

        /** A finished utterance, PCM16 at [sampleRate]. */
        class Utterance(val pcm: ShortArray, val startedAtFrame: Long) : Event

        /** Onset that turned out to be too short to be speech (a cough, a knock). */
        data object Discarded : Event
    }

    private val onsetFrames = (onsetMs / frameMs).coerceAtLeast(1)
    private val endFrames = (endSilenceMs / frameMs).coerceAtLeast(1)
    private val preRollFrames = (preRollMs / frameMs).coerceAtLeast(onsetFrames)
    private val maxFrames = maxUtteranceMs / frameMs
    private val minFrames = minUtteranceMs / frameMs

    private val preRoll = ArrayDeque<ShortArray>()
    private val utterance = mutableListOf<ShortArray>()
    private var inSpeech = false
    private var loudRun = 0
    private var quietRun = 0
    private var voicedFrames = 0
    private var frameIndex = 0L
    private var startFrame = 0L
    private var floor = -70.0

    /** True while the assistant's voice is playing. Set from the playback thread. */
    @Volatile var playing = false

    /** Level of the last frame, 0..1, for UI. */
    var level = 0f
        private set

    fun accept(frame: ShortArray): Event? {
        frameIndex++
        val db = dbfs(frame)
        level = ((db + 60.0) / 50.0).coerceIn(0.0, 1.0).toFloat()
        val threshold = maxOf(floor + margin + if (playing) playingExtraMargin else 0.0, minDbfs)
        val loud = db > threshold

        if (!inSpeech) {
            preRoll.addLast(frame)
            if (preRoll.size > preRollFrames) preRoll.removeFirst()
            if (loud) {
                loudRun++
            } else {
                // Brief dips between sounds (soft consonants) don't restart the count.
                loudRun = (loudRun - 2).coerceAtLeast(0)
                if (loudRun == 0) floor = if (db < floor) floor * 0.7 + db * 0.3 else floor * 0.98 + db * 0.02
            }
            if (loudRun >= onsetFrames) {
                inSpeech = true
                utterance.clear()
                utterance.addAll(preRoll)
                startFrame = frameIndex - preRoll.size + 1
                preRoll.clear()
                quietRun = 0
                voicedFrames = loudRun
                loudRun = 0
                return Event.Onset
            }
            return null
        }

        utterance += frame
        // Inside an utterance, a softer threshold keeps quiet syllables from ending it early.
        if (db > threshold - margin / 2) {
            quietRun = 0
            voicedFrames++
        } else {
            quietRun++
        }
        if (quietRun >= endFrames || utterance.size >= maxFrames) {
            inSpeech = false
            val keep = utterance.size - (quietRun - endFrames / 3).coerceAtLeast(0)
            val frames = utterance.take(keep.coerceAtLeast(1))
            utterance.clear()
            quietRun = 0
            if (voicedFrames < minFrames) return Event.Discarded
            return Event.Utterance(join(frames), startFrame)
        }
        return null
    }

    /** Drop any utterance in progress (for example, when voice mode pauses). */
    fun reset() {
        inSpeech = false
        utterance.clear()
        preRoll.clear()
        loudRun = 0
        quietRun = 0
    }

    val speaking: Boolean get() = inSpeech

    private fun join(frames: List<ShortArray>): ShortArray {
        val out = ShortArray(frames.sumOf { it.size })
        var at = 0
        for (f in frames) {
            f.copyInto(out, at)
            at += f.size
        }
        return out
    }

    companion object {
        fun dbfs(frame: ShortArray): Double {
            if (frame.isEmpty()) return -90.0
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            val rms = sqrt(sum / frame.size)
            return if (rms < 1.0) -90.0 else 20 * log10(rms / 32768.0)
        }
    }
}

/** Minimal PCM16 mono WAV encoding and decoding. */
object Wav {
    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val out = ByteArrayOutputStream(44 + pcm.size * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcm.size * 2)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(pcm.size * 2)
        }
        out.write(header.array())
        out.write(data.array())
        return out.toByteArray()
    }

    class Decoded(val pcm: ShortArray, val sampleRate: Int)

    /** Reads a PCM16 WAV, mixing to mono. Throws [IllegalArgumentException] for anything else. */
    fun decode(bytes: ByteArray): Decoded {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Not a WAV file." }
        var pos = 12
        var channels = 1
        var rate = 0
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    require(buf.getShort(body).toInt() == 1) { "Only PCM WAV is supported." }
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(bits == 16 && rate > 0) { "Only 16-bit WAV is supported." }
                    val end = minOf(bytes.size, body + size)
                    val frames = (end - body) / (2 * channels)
                    val pcm = ShortArray(frames) { i ->
                        var sum = 0
                        for (c in 0 until channels) sum += buf.getShort(body + (i * channels + c) * 2)
                        (sum / channels).toShort()
                    }
                    return Decoded(pcm, rate)
                }
            }
            pos = body + size + (size and 1)
        }
        throw IllegalArgumentException("WAV has no audio data.")
    }
}
