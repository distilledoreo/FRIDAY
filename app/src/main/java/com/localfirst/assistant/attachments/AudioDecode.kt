package com.localfirst.assistant.attachments

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Decodes an audio file to 16 kHz mono PCM16 for the computer's /transcribe
 * endpoint (the same Parakeet service as voice mode). Decoding runs on the
 * phone; recognition runs on the computer. At most two minutes are kept.
 */
object AudioDecode {
    const val TARGET_RATE = 16_000
    const val MAX_SECONDS = 120
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val OUTPUT_DRAIN_MS = 10_000L

    /** Linear resampling to 16 kHz mono; pure for tests. */
    fun resampleTo16k(samples: ShortArray, rate: Int): ShortArray {
        if (samples.isEmpty()) return samples
        if (rate == TARGET_RATE) return samples.copyOf(min(samples.size, TARGET_RATE * MAX_SECONDS))
        require(rate in 8_000..192_000) { "Unsupported sample rate" }
        val keep = min(samples.size, (rate.toLong() * MAX_SECONDS).toInt())
        val count = ((keep - 1).toLong() * TARGET_RATE / rate + 1).toInt().coerceAtLeast(1)
        return ShortArray(count) { i ->
            val pos = i.toDouble() * rate / TARGET_RATE
            val low = pos.toInt().coerceIn(0, keep - 1)
            val high = (low + 1).coerceIn(0, keep - 1)
            val fraction = (pos - low).toFloat()
            (samples[low] + (samples[high] - samples[low]) * fraction).toInt().toShort()
        }
    }

    /** Decodes [uri] to 16 kHz mono PCM16, capped at two minutes. Throws [java.io.IOException]. */
    fun decode(context: Context, uri: Uri): ShortArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw java.io.IOException("No audio track found.")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val input = ByteBuffer.allocate(256 * 1024)
                var bytesRead = 0L
                val mono = mutableListOf<Short>()
                var sawInputEos = false
                var sawOutputEos = false
                var inputEosAt = 0L
                val info = MediaCodec.BufferInfo()
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, TARGET_RATE)
                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear()
                            val sample = extractor.readSampleData(buffer, 0)
                            // The duration cap stops feeding input but still drains the
                            // codec, so the kept audio ends cleanly instead of cut off.
                            if (sample < 0 || bytesRead + sample > MAX_BYTES || mono.size >= rate * MAX_SECONDS) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                                inputEosAt = android.os.SystemClock.elapsedRealtime()
                            } else {
                                bytesRead += sample
                                codec.queueInputBuffer(index, 0, sample, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val outFormat = codec.outputFormat
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, rate)
                        val frame = ShortArray(info.size / 2)
                        buffer.get(frame)
                        var c = 0
                        while (c < frame.size) {
                            var sum = 0
                            for (ch in 0 until channels) sum += frame.getOrElse(c + ch) { 0 }
                            mono.add((sum / channels).toShort())
                            c += channels
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER && sawInputEos &&
                        android.os.SystemClock.elapsedRealtime() - inputEosAt > OUTPUT_DRAIN_MS
                    ) {
                        // The codec went quiet after input ended: take what drained.
                        sawOutputEos = true
                    }
                }
                return resampleTo16k(mono.toShortArray(), rate)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }
}
