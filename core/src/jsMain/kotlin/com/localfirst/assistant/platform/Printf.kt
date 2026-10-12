package com.localfirst.assistant.platform

/** Minimal `String.format` for the patterns the core uses: %d, %0Nd, %0Nx, %s, %%. */
internal object Printf {
    fun format(pattern: String, args: List<Any?>): String {
        val sb = StringBuilder()
        var ai = 0
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c != '%') { sb.append(c); i++; continue }
            i++
            if (i >= pattern.length) { sb.append('%'); break }
            val spec = pattern[i]
            if (spec == '%') { sb.append('%'); i++; continue }
            var zero = false
            var width = 0
            while (i < pattern.length && (pattern[i] == '0' || pattern[i].isDigit())) {
                if (pattern[i] == '0' && width == 0) zero = true else width = width * 10 + (pattern[i] - '0')
                i++
            }
            val conv = if (i < pattern.length) pattern[i] else 's'
            i++
            val arg = args.getOrNull(ai); ai++
            val text = when (conv) {
                'd' -> (arg as? Number)?.toLong()?.toString() ?: arg?.toString() ?: "0"
                'x' -> (arg as? Number)?.toLong()?.toString(16) ?: arg?.toString() ?: "0"
                's' -> arg?.toString() ?: "null"
                else -> arg?.toString() ?: ""
            }
            if (width > 0 && text.length < width) {
                val pad = if (zero) '0' else ' '
                sb.append(pad.toString().repeat(width - text.length)).append(text)
            } else sb.append(text)
        }
        return sb.toString()
    }
}
