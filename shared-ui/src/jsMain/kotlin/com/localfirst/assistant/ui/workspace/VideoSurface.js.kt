package com.localfirst.assistant.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement

/** A browser <video> laid over the space this takes in the canvas, since Compose draws into one canvas. */
@Composable
internal actual fun VideoSurface(url: String, modifier: Modifier) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current.density
    val video = remember(url) {
        (document.createElement("video") as HTMLVideoElement).apply {
            src = url; controls = true; autoplay = true; loop = true
            setAttribute("playsinline", "")
            style.cssText = "position:fixed;z-index:9000;background:#000;object-fit:contain;display:none"
        }
    }
    DisposableEffect(video) {
        document.body?.appendChild(video)
        onDispose { video.pause(); video.removeAttribute("src"); video.load(); video.remove() }
    }
    LaunchedEffect(bounds, density) {
        val b = bounds ?: return@LaunchedEffect
        video.style.left = "${b.left / density}px"; video.style.top = "${b.top / density}px"
        video.style.width = "${b.width / density}px"; video.style.height = "${b.height / density}px"
        video.style.display = "block"
    }
    Box(modifier.onGloballyPositioned { bounds = it.boundsInWindow() })
}
