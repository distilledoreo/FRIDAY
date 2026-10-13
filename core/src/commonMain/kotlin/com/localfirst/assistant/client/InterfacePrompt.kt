package com.localfirst.assistant.client

import com.localfirst.assistant.genui.Catalog
import com.localfirst.assistant.genui.UiPrompt

fun clientUiCatalog(): Catalog {
    val unsupported = setOf("appblock", "asyncimage", "image", "imagegallery", "gallery")
    return Catalog(Catalog.default.components.filter { it.name.lowercase() !in unsupported }.associateBy { it.name }, emptyMap())
}

fun clientInterfacePrompt(): String {
    val catalog = clientUiCatalog()
    return UiPrompt.section(catalog).lines().filterNot { "AppBlock" in it || "AsyncImage" in it }.joinToString("\n")
        .replace("run on the phone", "run on this client") + "\nUse Markdown for unsupported components. GenUI.issueNewTurn places a follow-up in the composer for the user to send."
}
