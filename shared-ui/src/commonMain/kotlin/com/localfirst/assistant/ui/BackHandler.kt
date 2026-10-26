package com.localfirst.assistant.ui

import androidx.compose.runtime.Composable

/** The system back gesture closes an overlay (Android); browsers have no system back for in-page overlays. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
