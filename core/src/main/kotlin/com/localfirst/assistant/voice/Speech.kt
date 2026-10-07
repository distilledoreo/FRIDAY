package com.localfirst.assistant.voice

import kotlin.math.max

/** Turns Markdown into text worth reading aloud. */
object SpeechText {
    private val link = Regex("""\[([^\]]+)]\((?:[^)]+)\)""")
    private val bareUrl = Regex("""https?://\S+""")
    private val emphasis = Regex("""(\*\*|__|\*|_|~~)(?=\S)(.+?)(?<=\S)\1""")
    private val heading = Regex("""^\s{0,3}#{1,6}\s+""", RegexOption.MULTILINE)
    private val bullet = Regex("""^\s*(?:[-*+]|\d+[.)])\s+""", RegexOption.MULTILINE)
    private val tableRule = Regex("""^\s*\|?\s*:?-{3,}.*$""", RegexOption.MULTILINE)
    private val codeFence = Regex("""```[\s\S]*?(?:```|$)""")

    fun fromMarkdown(markdown: String): String {
        var text = codeFence.replace(markdown) { " The code is on screen. " }
        text = link.replace(text) { it.groupValues[1] }
        text = bareUrl.replace(text, "a link")
        text = text.replace("`", "")
        text = tableRule.replace(text, "")
        text = heading.replace(text, "")
        text = bullet.replace(text, "")
        repeat(2) { text = emphasis.replace(text) { it.groupValues[2] } }
        text = text.lines().joinToString(" ") { line ->
            val cells = line.trim().trim('|').split('|').map(String::trim).filter(String::isNotEmpty)
            if (line.trim().startsWith("|") && cells.size > 1) cells.joinToString(", ") + "." else line
        }
        return text.replace(Regex("""\s+"""), " ").trim()
    }
}

/**
 * Feeds a reply that is still streaming and hands back whole sentences to
 * speak, so speech starts before the answer is finished. Code blocks are held
 * until they close. Call [update] with the full text so far; it returns only
 * what hasn't been returned yet.
 */
class SpeechChunker(private val minChars: Int = 24) {
    private var consumed = 0
    private var source = ""

    fun update(fullText: String, final: Boolean): List<String> {
        if (!fullText.startsWith(source.take(consumed))) {
            // The text was rewritten (for example, leaked markup removed); start over at its end.
            consumed = fullText.length.coerceAtMost(consumed)
        }
        source = fullText
        val out = mutableListOf<String>()
        while (true) {
            val end = nextBoundary(fullText, consumed) ?: break
            val chunk = fullText.substring(consumed, end)
            if (chunk.trim().length < minChars && !final && end < fullText.length) {
                // Too short to sound natural on its own; wait for the next sentence.
                val more = nextBoundary(fullText, end) ?: break
                out += speakable(fullText.substring(consumed, more))
                consumed = more
                continue
            }
            out += speakable(chunk)
            consumed = end
        }
        if (final && consumed < fullText.length) {
            out += speakable(fullText.substring(consumed))
            consumed = fullText.length
        }
        return out.filter { it.isNotBlank() }
    }

    fun reset() {
        consumed = 0
        source = ""
    }

    private fun speakable(chunk: String) = SpeechText.fromMarkdown(chunk)

    /** Index just after the next sentence end at or after [from], outside code fences. */
    private fun nextBoundary(text: String, from: Int): Int? {
        var inFence = text.substring(0, from).split("```").size % 2 == 0
        var i = from
        while (i < text.length) {
            if (text.startsWith("```", i)) {
                inFence = !inFence
                i += 3
                if (!inFence) return max(i, from + 1)
                continue
            }
            if (!inFence) {
                val c = text[i]
                if (c == '\n') return i + 1
                if ((c == '.' || c == '!' || c == '?') && i + 1 < text.length && text[i + 1].isWhitespace()) {
                    // Skip decimals like 3.5 and common abbreviations.
                    val prev = text.substring(maxOf(from, i - 3), i)
                    if (c == '.' && (prev.endsWith("e.g") || prev.endsWith("i.e") || prev.endsWith("Mr") || prev.endsWith("Dr"))) {
                        i++
                        continue
                    }
                    return i + 1
                }
            }
            i++
        }
        return null
    }
}

/** Understands spoken answers to "Should I go ahead?". */
object VoiceReplies {
    private val no = listOf(
        "no", "nope", "nah", "don't", "do not", "cancel", "stop", "never mind", "nevermind", "deny", "wait", "hold on",
    )
    private val yes = listOf(
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "go ahead", "do it", "send it", "send", "call", "approve",
        "confirm", "please do", "correct", "right",
    )

    /** True for yes, false for no, null when unclear. Negatives win ("no, don't send it"). */
    fun approval(text: String): Boolean? {
        val words = " " + text.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (no.any { words.contains(" $it ") }) return false
        if (yes.any { words.contains(" $it ") }) return true
        return null
    }
}

/**
 * Decides when the user has started talking, from mic loudness in short
 * frames. It tracks the background level (including any of the assistant's
 * own voice that echo cancellation leaves behind) and fires once the signal
 * stays well above it. [margin] and [minDbfs] are higher on the loudspeaker,
 * where more echo reaches the mic.
 */
class SpeechOnsetDetector(
    private val margin: Double = 15.0,
    private val minDbfs: Double = -45.0,
    private val framesNeeded: Int = 9,
) {
    private var floor = -70.0
    private var run = 0

    /** Feed one frame's level in dBFS. Returns true once speech has started. */
    fun accept(dbfs: Double): Boolean {
        val loud = dbfs > floor + margin && dbfs > minDbfs
        if (loud) {
            run++
        } else {
            run = 0
            // Track the background only between loud stretches: fall quickly, rise slowly.
            floor = if (dbfs < floor) floor * 0.7 + dbfs * 0.3 else floor * 0.98 + dbfs * 0.02
        }
        return run >= framesNeeded
    }

    companion object {
        fun forSpeaker() = SpeechOnsetDetector(margin = 24.0, minDbfs = -32.0, framesNeeded = 12)
        fun forHeadset() = SpeechOnsetDetector()
    }
}
