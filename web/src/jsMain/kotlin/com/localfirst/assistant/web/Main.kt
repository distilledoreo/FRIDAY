package com.localfirst.assistant.web

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.localfirst.assistant.genui.UiCompiler
import kotlinx.browser.document

/**
 * Browser entry point. The shared engine and presentation layer come from core's JS target; the
 * chat UI and the browser-backed infra (HTTP, storage) are added on top of it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val compiled = UiCompiler.compile("<div>hello</div>", complete = true)
    console.log("FRIDAY web engine ready: compiled ${compiled.nodes.size} node(s)")
    ComposeViewport(document.body!!) {
        App()
    }
}

@Composable
private fun App() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column {
                    Text("FRIDAY")
                }
            }
        }
    }
}
