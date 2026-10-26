package com.localfirst.assistant.ui

internal actual val gradientsDithered: Boolean = true

// Android keeps the stacked-stroke bloom: it dithers gradients itself, and the blurred bloom is only checked in the browser.
internal actual val blurAvailable: Boolean = false

internal actual fun androidx.compose.ui.graphics.Paint.dither() { asFrameworkPaint().isDither = true }
