package com.localfirst.assistant.voice

/**
 * Tells the assistant's own voice, picked up by the phone's mic from car or
 * room speakers, apart from the user talking. Recognition of that echo is
 * imperfect, so most of the words, not all of them, have to match.
 */
object EchoFilter {
    /** True when [heard] is mostly words from [spoken], what the assistant just said. Empty [heard] counts as echo. */
    fun isEcho(heard: String, spoken: List<String>): Boolean {
        val words = words(heard)
        if (words.isEmpty()) return true
        val said = spoken.flatMapTo(HashSet()) { words(it) }
        if (said.isEmpty()) return false
        return words.count { it in said } * 10 >= words.size * 6
    }

    internal fun words(text: String): List<String> =
        text.lowercase().replace('’', '\'').split(Regex("[^\\p{L}\\p{N}']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }
}
