package com.localfirst.assistant.tools

import android.content.Context
import android.media.AudioManager

/**
 * Applies [SetMediaVolumeTool]'s 0–100 percentage to [AudioManager.STREAM_MUSIC].
 * Failures are thrown so the tool can record them without touching conversation state.
 */
class AndroidMediaVolume(
    context: Context,
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun setPercent(levelPercent: Int) {
        val stream = AudioManager.STREAM_MUSIC
        val streamMax = audioManager.getStreamMaxVolume(stream)
        if (streamMax < 1) {
            throw IllegalStateException("This device has no media volume range.")
        }
        val index = MediaVolumeScale.toStreamIndex(levelPercent, streamMax)
        try {
            audioManager.setStreamVolume(stream, index, AudioManager.FLAG_SHOW_UI)
        } catch (security: SecurityException) {
            throw IllegalStateException("Android denied the media volume change.", security)
        }
        val actual = audioManager.getStreamVolume(stream)
        if (actual != index) {
            throw IllegalStateException(
                "Requested media volume step $index of $streamMax, but the device stayed at $actual.",
            )
        }
    }
}
