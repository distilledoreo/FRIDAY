package com.localfirst.assistant.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)
