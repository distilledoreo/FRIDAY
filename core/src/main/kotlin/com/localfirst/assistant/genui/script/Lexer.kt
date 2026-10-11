package com.localfirst.assistant.genui.script

enum class TokenType { IDENT, NUMBER, STRING, TEMPLATE, PUNCT, REGEX, EOF }

data class Token(
    val type: TokenType,
    val text: String,
    val start: Int,
    val end: Int,
    /** A line break came between this token and the previous one (for automatic semicolons). */
    val newlineBefore: Boolean,
    val number: Double = 0.0,
    /** Regex flags. */
    val flags: String = "",
)

/** The source ended inside a construct: a half-written (streaming) response. */
class IncompleteInput(val at: Int, message: String) : RuntimeException(message)

/** The source can't be read as the language, even with more text. */
class ScriptSyntaxError(val at: Int, message: String) : RuntimeException(message)

/**
 * Reads tokens on demand from any position, so the markup parser and the script parser can hand the
 * source back and forth (markup inside `{…}`, expressions inside markup).
 */
class Lexer(val src: String) {
    var pos = 0

    private fun skipSpace(): Boolean {
        var newline = false
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029' -> { newline = true; pos++ }
                c.isWhitespace() || c == '\uFEFF' -> pos++
                c == '/' && pos + 1 < src.length && src[pos + 1] == '/' -> {
                    while (pos < src.length && src[pos] != '\n') pos++
                }
                c == '/' && pos + 1 < src.length && src[pos + 1] == '*' -> {
                    val end = src.indexOf("*/", pos + 2)
                    if (end < 0) throw IncompleteInput(pos, "Unterminated comment")
                    if (src.substring(pos, end).contains('\n')) newline = true
                    pos = end + 2
                }
                else -> return newline
            }
        }
        return newline
    }

    fun next(regexAllowed: Boolean): Token {
        val newline = skipSpace()
        if (pos >= src.length) return Token(TokenType.EOF, "", pos, pos, newline)
        val start = pos
        val c = src[pos]
        return when {
            isIdentStart(c) -> {
                pos++
                while (pos < src.length && isIdentPart(src[pos])) pos++
                Token(TokenType.IDENT, src.substring(start, pos), start, pos, newline)
            }
            c.isDigit() || (c == '.' && pos + 1 < src.length && src[pos + 1].isDigit()) -> number(start, newline)
            c == '"' || c == '\'' -> Token(TokenType.STRING, string(c), start, pos, newline)
            c == '`' -> { pos++; Token(TokenType.TEMPLATE, "`", start, pos, newline) }
            c == '/' && regexAllowed -> regex(start, newline)
            else -> punct(start, newline)
        }
    }

    private fun number(start: Int, newline: Boolean): Token {
        val c = src[pos]
        if (c == '0' && pos + 1 < src.length && src[pos + 1].lowercaseChar() in "xob") {
            val radix = when (src[pos + 1].lowercaseChar()) { 'x' -> 16; 'o' -> 8; else -> 2 }
            pos += 2
            val digits = StringBuilder()
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) { if (src[pos] != '_') digits.append(src[pos]); pos++ }
            if (pos < src.length && src[pos - 1] == 'n') digits.setLength(digits.length - 1)
            val value = digits.toString().toLongOrNull(radix)?.toDouble() ?: throw ScriptSyntaxError(start, "Invalid number")
            return Token(TokenType.NUMBER, src.substring(start, pos), start, pos, newline, value)
        }
        val digits = StringBuilder()
        fun digitsRun() { while (pos < src.length && (src[pos].isDigit() || src[pos] == '_')) { if (src[pos] != '_') digits.append(src[pos]); pos++ } }
        digitsRun()
        if (pos < src.length && src[pos] == '.') { digits.append('.'); pos++; digitsRun() }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            val save = pos
            val saveLength = digits.length
            digits.append('e'); pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) { digits.append(src[pos]); pos++ }
            val before = digits.length
            digitsRun()
            if (digits.length == before) { pos = save; digits.setLength(saveLength) }
        }
        if (pos < src.length && src[pos] == 'n') pos++ // BigInt literal, read as a number
        if (pos < src.length && isIdentStart(src[pos])) throw ScriptSyntaxError(pos, "Identifier directly after number")
        val value = digits.toString().let { if (it.endsWith('.')) "${it}0" else it }.toDoubleOrNull() ?: throw ScriptSyntaxError(start, "Invalid number")
        return Token(TokenType.NUMBER, src.substring(start, pos), start, pos, newline, value)
    }

    private fun string(quote: Char): String {
        pos++
        val out = StringBuilder()
        while (true) {
            if (pos >= src.length) throw IncompleteInput(pos, "Unterminated string")
            val c = src[pos]
            when {
                c == quote -> { pos++; return out.toString() }
                c == '\\' -> { pos++; escape(out) }
                c == '\n' -> throw ScriptSyntaxError(pos, "Unterminated string")
                else -> { out.append(c); pos++ }
            }
        }
    }

    /** Reads one escape after a backslash into [out]. */
    private fun escape(out: StringBuilder) {
        if (pos >= src.length) throw IncompleteInput(pos, "Unterminated escape")
        val e = src[pos++]
        when (e) {
            'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
            'b' -> out.append('\b'); 'f' -> out.append('\u000C'); 'v' -> out.append('\u000B')
            '0' -> out.append('\u0000')
            '\r' -> { if (pos < src.length && src[pos] == '\n') pos++ }
            '\n', '\u2028', '\u2029' -> Unit
            'x' -> { out.append(hex(2).toChar()) }
            'u' -> {
                if (pos < src.length && src[pos] == '{') {
                    val end = src.indexOf('}', pos)
                    if (end < 0) throw IncompleteInput(pos, "Unterminated escape")
                    val code = src.substring(pos + 1, end).toIntOrNull(16) ?: throw ScriptSyntaxError(pos, "Invalid escape")
                    out.appendCodePoint(code)
                    pos = end + 1
                } else out.append(hex(4).toChar())
            }
            else -> out.append(e)
        }
    }

    private fun hex(count: Int): Int {
        if (pos + count > src.length) throw IncompleteInput(pos, "Unterminated escape")
        val value = src.substring(pos, pos + count).toIntOrNull(16) ?: throw ScriptSyntaxError(pos, "Invalid escape")
        pos += count
        return value
    }

    /**
     * Reads template text after a backtick or a `}` that closed `${…}`. Returns the cooked text and
     * whether the template ended (true) or an expression follows (false).
     */
    fun templateChunk(): Pair<String, Boolean> {
        val out = StringBuilder()
        while (true) {
            if (pos >= src.length) throw IncompleteInput(pos, "Unterminated template")
            val c = src[pos]
            when {
                c == '`' -> { pos++; return out.toString() to true }
                c == '$' && pos + 1 < src.length && src[pos + 1] == '{' -> { pos += 2; return out.toString() to false }
                c == '\\' -> { pos++; escape(out) }
                else -> { out.append(c); pos++ }
            }
        }
    }

    private fun regex(start: Int, newline: Boolean): Token {
        pos++
        var inClass = false
        val body = StringBuilder()
        while (true) {
            if (pos >= src.length) throw IncompleteInput(pos, "Unterminated regular expression")
            val c = src[pos]
            if (c == '\n') throw ScriptSyntaxError(pos, "Unterminated regular expression")
            if (c == '\\') { body.append(c); if (pos + 1 < src.length) body.append(src[pos + 1]); pos += 2; continue }
            if (c == '[') inClass = true
            if (c == ']') inClass = false
            if (c == '/' && !inClass) { pos++; break }
            body.append(c); pos++
        }
        val flagsStart = pos
        while (pos < src.length && src[pos].isLetter()) pos++
        return Token(TokenType.REGEX, body.toString(), start, pos, newline, flags = src.substring(flagsStart, pos))
    }

    private fun punct(start: Int, newline: Boolean): Token {
        for (p in PUNCTUATORS) {
            if (src.startsWith(p, pos)) {
                // `?.` followed by a digit is a conditional with a decimal: `a?.5:b`.
                if (p == "?." && pos + 2 < src.length && src[pos + 2].isDigit()) continue
                pos += p.length
                return Token(TokenType.PUNCT, p, start, pos, newline)
            }
        }
        throw ScriptSyntaxError(pos, "Unexpected character '${src[pos]}'")
    }

    companion object {
        private val PUNCTUATORS = listOf(
            ">>>=", "...", "===", "!==", "**=", "<<=", ">>=", ">>>", "&&=", "||=", "??=",
            "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "/=", "%=",
            "&=", "|=", "^=", "**", "<<", ">>",
            "{", "}", "(", ")", "[", "]", ";", ",", "<", ">", "+", "-", "*", "/", "%", "&", "|", "^", "!", "~", "?", ":", "=", ".", "@", "#",
        )

        fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'
        fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c == '\u200C' || c == '\u200D'
    }
}
