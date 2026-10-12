package com.localfirst.assistant.web

import com.localfirst.assistant.genui.UiCompiler

/**
 * Browser entry point. The shared engine and presentation layer come from core's JS target; the
 * chat UI and the browser-backed infra (HTTP, storage) are added on top of it.
 */
fun main() {
    // Smoke check that the shared engine is linked in.
    val compiled = UiCompiler.compile("<div>hello</div>", complete = true)
    console.log("FRIDAY web engine ready: compiled ${compiled.nodes.size} node(s)")
}
