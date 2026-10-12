package com.localfirst.assistant.voice

import com.localfirst.assistant.platform.platformRegex

/**
 * Matches the spoken "Hey FRIDAY" phrase in recognizer transcripts. Pure text
 * matching so the service stays testable; the Android service owns the mic.
 */
object WakeWord {
    /** Adjacent word pairs that wake the assistant. */
    fun isWakeWord(text: String): Boolean {
        val words = text.lowercase().split(platformRegex("[^a-z]+")).filter { it.isNotEmpty() }
        return words.zipWithNext().any { (first, second) -> first == "hey" && second == "friday" }
    }
}
