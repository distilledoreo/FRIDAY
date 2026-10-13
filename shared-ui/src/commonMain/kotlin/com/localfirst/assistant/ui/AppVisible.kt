package com.localfirst.assistant.ui

import androidx.compose.runtime.Composable

/** True while the app is in front of the user: a resumed Android screen, or a visible browser tab. */
@Composable
expect fun rememberAppVisible(): Boolean
