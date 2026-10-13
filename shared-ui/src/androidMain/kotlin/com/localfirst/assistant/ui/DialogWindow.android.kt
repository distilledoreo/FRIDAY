package com.localfirst.assistant.ui
import androidx.compose.runtime.Composable
@Composable
actual fun FridayDialogWindow() {
    val view=androidx.compose.ui.platform.LocalView.current
    val palette=com.localfirst.assistant.ui.theme.LocalFridayPalette.current
    val window=(view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
    androidx.compose.runtime.SideEffect {
        window?.let {
            val bars=androidx.core.view.WindowCompat.getInsetsController(it,it.decorView)
            bars.isAppearanceLightStatusBars=!palette.dark
            bars.isAppearanceLightNavigationBars=!palette.dark
            if(palette.reducedMotion)it.setWindowAnimations(0)
        }
    }
}
