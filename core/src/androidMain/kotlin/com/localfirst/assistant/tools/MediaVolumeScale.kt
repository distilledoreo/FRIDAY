package com.localfirst.assistant.tools

import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Maps the assistant's 0–100 media-volume percentage onto Android's
 * [android.media.AudioManager.STREAM_MUSIC] index space.
 *
 * The percentage is the tool contract. Device stream ranges differ, so the
 * Android side converts with [toStreamIndex]. Half steps round half up
 * (30% of 15 steps is 4.5, which becomes 5).
 */
object MediaVolumeScale {
    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 100

    fun parsePercent(arguments: JsonObject): Int {
        val raw = arguments["level"]
            ?: throw IllegalArgumentException(
                "Missing required argument 'level'. Expected an integer from 0 to 100.",
            )
        val number = readNumber(raw)
            ?: throw IllegalArgumentException("'level' must be a number from 0 to 100.")
        if (number.isNaN() || number < MIN_PERCENT || number > MAX_PERCENT) {
            throw IllegalArgumentException("'level' must be between 0 and 100.")
        }
        return number.roundToInt()
    }

    fun toStreamIndex(levelPercent: Int, streamMax: Int): Int {
        require(levelPercent in MIN_PERCENT..MAX_PERCENT) {
            "levelPercent must be between 0 and 100."
        }
        require(streamMax >= 1) { "streamMax must be at least 1." }
        val scaled = (levelPercent / MAX_PERCENT.toDouble()) * streamMax
        return scaled.roundToInt().coerceIn(0, streamMax)
    }

    private fun readNumber(raw: JsonElement): Double? {
        val primitive = raw as? JsonPrimitive ?: return null
        primitive.doubleOrNull?.let { return it }
        if (primitive.isString) {
            return primitive.content.trim().toDoubleOrNull()
        }
        return null
    }
}
