package com.localfirst.assistant.client

import com.localfirst.assistant.genui.Catalog
import com.localfirst.assistant.genui.UiPrompt

fun clientUiCatalog(): Catalog {
    val unsupported = emptySet<String>()
    return Catalog(Catalog.default.components.filter { it.name.lowercase() !in unsupported }.associateBy { it.name }, emptyMap())
}

fun clientInterfacePrompt(): String {
    val catalog = clientUiCatalog()
    return UiPrompt.section(catalog)
        .replace("run on the phone", "run on this client") + "\nUse Markdown for unsupported components. GenUI.issueNewTurn places a follow-up in the composer for the user to send."
}
