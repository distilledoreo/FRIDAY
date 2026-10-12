package com.localfirst.assistant.web.data

import com.localfirst.assistant.genui.Catalog
import com.localfirst.assistant.genui.UiPrompt

fun webInterfacePrompt(): String {
    val supported = setOf("box", "row", "column", "grid", "card", "section", "divider", "spacer", "title", "text", "caption", "bold", "italic", "underline", "strike", "code", "link", "markdown", "codeblock", "quote", "list", "item", "badge", "button", "input", "textarea", "checkbox", "switch", "slider", "stat", "table", "tr", "th", "td")
    val catalog = Catalog(Catalog.default.components.filter { it.name in supported }.associateBy { it.name }, emptyMap())
    return UiPrompt.section(catalog).lines().filterNot { "AppBlock" in it || "AsyncImage" in it }.joinToString("\n")
        .replace("run on the phone", "run on this client") + "\nUse Markdown for unsupported components. GenUI.issueNewTurn places a follow-up in the composer for the user to send."
}
