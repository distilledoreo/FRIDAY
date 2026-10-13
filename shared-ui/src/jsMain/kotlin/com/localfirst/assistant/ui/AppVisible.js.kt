package com.localfirst.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.w3c.dom.events.Event

@Composable
actual fun rememberAppVisible(): Boolean {
    var visible by remember { mutableStateOf(document.asDynamic().hidden != true) }
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { visible = document.asDynamic().hidden != true }
        document.addEventListener("visibilitychange", listener)
        onDispose { document.removeEventListener("visibilitychange", listener) }
    }
    return visible
}
