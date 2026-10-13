package com.localfirst.assistant.presentation

import com.localfirst.assistant.conversation.Message

/** A saved chat as readable Markdown: answers keep their text, components become sentences. */
object ChatExport {
    fun markdown(title: String, messages: List<Message>): String = buildString {
        appendLine("# $title")
        for (message in messages) {
            when (message) {
                is Message.User -> {
                    appendLine()
                    appendLine("## You")
                    appendLine(message.content.trim())
                    message.attachments.forEach { appendLine("- [Attachment] ${it.name}") }
                }
                is Message.Assistant -> {
                    val text = UiBlocks.plainText(message.content).trim()
                    if (text.isNotEmpty()) {
                        appendLine()
                        appendLine("## FRIDAY")
                        appendLine(text)
                    }
                }
                else -> Unit
            }
        }
    }.trim() + "\n"
}
