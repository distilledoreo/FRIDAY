package com.localfirst.assistant.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Streaming "Hey FRIDAY" detector: the openWakeWord pipeline (mel spectrogram,
 * shared speech embedding, small keyword classifier) run with ONNX Runtime on
 * the phone. Feed it 80 ms frames of 16 kHz mono audio.
 *
 * Nothing runs during silence: the last two seconds of raw audio are only
 * kept, and when sound returns they go through the mel step first, so the
 * embeddings the classifier needs are backfilled as if it had been listening.
 * While there has been sound in the last two seconds, every frame runs. A detection needs [needed] frames in a row at or above [threshold]:
 * a lone "Friday" peaks briefly, the full phrase stays high for longer.
 */
class WakeWordDetector(
    mel: ByteArray,
    embedding: ByteArray,
    keyword: ByteArray,
    private val threshold: Float = 0.5f,
    private val needed: Int = 3,
) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1); setInterOpNumThreads(1) }
    private val melModel = env.createSession(mel, options)
    private val embeddingModel = env.createSession(embedding, options)
    private val keywordModel = env.createSession(keyword, options)

    private val context = FloatArray(CONTEXT)
    private val melFrames = ArrayDeque<FloatArray>()
    private var melCount = 0
    private val embeddings = ArrayDeque<FloatArray>()
    // Raw frames heard while asleep, reused in a ring; replayed through the mel step when sound returns.
    private val backlog = Array(BACKLOG) { ShortArray(FRAME) }
    private var backlogStart = 0
    private var backlogSize = 0
    private var embeddedAt = 0
    private var quietFrames = AWAKE_FRAMES
    private var noise = -1f
    private var streak = 0

    /** Embedding model calls so far; the costly step, counted so tests can see silence skip it. */
    var embeddingRuns = 0
        private set

    /** Mel model calls so far; silence skips these too. */
    var melRuns = 0
        private set

    /** The classifier's latest score, 0 while asleep in silence. */
    var score = 0f
        private set

    init { reset() }

    /** Clears all audio history, as after a detection. */
    fun reset() {
        context.fill(0f)
        melFrames.clear()
        repeat(WINDOW) { melFrames.addLast(FloatArray(MELS) { 1f }) }
        melCount = WINDOW
        embeddings.clear()
        embeddedAt = 0
        backlogSize = 0
        streak = 0
        score = 0f
    }

    /** Takes one [FRAME]-sample frame; true when the wake phrase has just been heard. */
    fun accept(frame: ShortArray): Boolean {
        require(frame.size == FRAME)
        if (!soundRecently(frame)) { keep(frame); streak = 0; score = 0f; return false }
        while (backlogSize > 0) {
            appendMel(backlog[backlogStart])
            backlogStart = (backlogStart + 1) % BACKLOG; backlogSize--
        }
        appendMel(frame)
        embedNewPositions()
        score = if (embeddings.size == FEATURES) classify() else 0f
        streak = if (score >= threshold) streak + 1 else 0
        if (streak < needed) return false
        reset()
        return true
    }

    /** Keeps [frame] as the newest of the last [BACKLOG] silent frames. */
    private fun keep(frame: ShortArray) {
        frame.copyInto(backlog[(backlogStart + backlogSize) % BACKLOG])
        if (backlogSize < BACKLOG) backlogSize++ else backlogStart = (backlogStart + 1) % BACKLOG
    }

    private fun appendMel(frame: ShortArray) {
        melRuns++
        val input = FloatArray(CONTEXT + FRAME)
        context.copyInto(input)
        for (i in frame.indices) input[CONTEXT + i] = frame[i].toFloat()
        input.copyInto(context, 0, FRAME, FRAME + CONTEXT)
        val spec = run(melModel, input, longArrayOf(1, input.size.toLong()))
        for (start in spec.indices step MELS) {
            melFrames.addLast(FloatArray(MELS) { spec[start + it] / 10f + 2f })
            melCount++
        }
        while (melFrames.size > MEL_KEEP) melFrames.removeFirst()
    }

    /** Silence gate with a slowly rising noise floor, so steady room noise doesn't keep it awake forever. */
    private fun soundRecently(frame: ShortArray): Boolean {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = sqrt(sum / frame.size).toFloat()
        noise = when {
            noise < 0f || rms < noise -> rms
            else -> noise + (rms - noise) * NOISE_RISE
        }
        quietFrames = if (rms > maxOf(MIN_RMS, noise * LOUDER)) 0 else quietFrames + 1
        return quietFrames < AWAKE_FRAMES
    }

    /** Embeds every 8-frame step since the last one, up to the classifier's window; a gap starts it over. */
    private fun embedNewPositions() {
        val oldest = melCount - melFrames.size
        if (embeddedAt < melCount - STRIDE * FEATURES) embeddings.clear()
        var position = maxOf(embeddedAt + STRIDE, melCount - STRIDE * (FEATURES - 1))
        if (embeddings.isEmpty()) position = melCount - STRIDE * (FEATURES - 1)
        while (position <= melCount) {
            if (position - WINDOW >= oldest) {
                val window = FloatArray(WINDOW * MELS)
                for (row in 0 until WINDOW) melFrames[position - WINDOW - oldest + row].copyInto(window, row * MELS)
                embeddings.addLast(run(embeddingModel, window, longArrayOf(1, WINDOW.toLong(), MELS.toLong(), 1)))
                embeddingRuns++
                if (embeddings.size > FEATURES) embeddings.removeFirst()
            }
            position += STRIDE
        }
        embeddedAt = melCount
    }

    private fun classify(): Float {
        val features = FloatArray(FEATURES * EMBEDDING)
        embeddings.forEachIndexed { i, e -> e.copyInto(features, i * EMBEDDING) }
        return run(keywordModel, features, longArrayOf(1, FEATURES.toLong(), EMBEDDING.toLong()))[0]
    }

    private fun run(session: OrtSession, input: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                FloatArray(out.remaining()).also { out.get(it) }
            }
        }

    override fun close() {
        melModel.close(); embeddingModel.close(); keywordModel.close(); options.close()
    }

    companion object {
        /** 80 ms at 16 kHz. */
        const val FRAME = 1280
        const val SAMPLE_RATE = 16_000
        private const val CONTEXT = 480
        private const val MELS = 32
        private const val WINDOW = 76
        private const val STRIDE = 8
        private const val FEATURES = 16
        private const val EMBEDDING = 96
        private const val MEL_KEEP = WINDOW + STRIDE * (FEATURES - 1)
        private const val AWAKE_FRAMES = 25
        /** Silent frames kept for replay: enough mel history (196 frames) for a full classifier window. */
        private const val BACKLOG = 25
        private const val MIN_RMS = 40f
        private const val LOUDER = 2f
        private const val NOISE_RISE = 0.002f
    }
}
