package com.localfirst.assistant.ui
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Styles the dialog window this is called in; [fullScreen] makes it cover the whole screen, bars included, like the main window. */
@Composable
expect fun FridayDialogWindow(fullScreen: Boolean = false)

/** The navigation bar's height as the app's main window sees it; 0 where there is none (the browser). */
val LocalAppNavigationBar = staticCompositionLocalOf<Dp> { 0.dp }

/**
 * Keeps a full-screen dialog's content clear of the navigation bar. Android
 * hands these dialog windows no bottom inset even though they reach the bottom
 * of the screen, so the last rows sat under the bar; this pads whatever the
 * main window reports and the dialog itself doesn't.
 */
@Composable
fun Modifier.dialogNavigationBarPadding(): Modifier {
    val own = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return padding(bottom = (LocalAppNavigationBar.current - own).coerceAtLeast(0.dp))
}
