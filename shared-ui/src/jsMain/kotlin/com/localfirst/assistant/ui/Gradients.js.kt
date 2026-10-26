package com.localfirst.assistant.ui

internal actual val gradientsDithered: Boolean = false

internal actual val blurAvailable: Boolean = true

internal actual fun androidx.compose.ui.graphics.Paint.dither() { asFrameworkPaint().isDither = true }
