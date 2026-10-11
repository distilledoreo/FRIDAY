package com.localfirst.assistant.presentation

import com.localfirst.assistant.genui.UiCompiler
import com.localfirst.assistant.genui.UiFallback

/** A reply as plain Markdown, for copying, speech, Android Auto, notifications and search. */
object ReplyText {
    fun plain(text: String, complete: Boolean = true): String = when {
        UiCompiler.isInterface(text) -> runCatching { UiFallback.markdown(text, complete) }.getOrDefault(text)
        UiBlocks.contains(text) -> UiBlocks.plainText(text)
        else -> text
    }
}
