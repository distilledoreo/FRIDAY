package com.localfirst.assistant.ui.theme
import androidx.compose.runtime.*
import kotlinx.browser.window
@Composable
internal actual fun rememberSystemReducedMotion(): Boolean {
    val media = remember { window.matchMedia("(prefers-reduced-motion: reduce)") }
    var reduced by remember { mutableStateOf(media.matches) }
    DisposableEffect(media) {
        val listener: (dynamic) -> Unit = { reduced = media.matches }
        media.addListener(listener)
        onDispose { media.removeListener(listener) }
    }
    return reduced
}
