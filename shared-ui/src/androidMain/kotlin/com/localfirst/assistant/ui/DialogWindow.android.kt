package com.localfirst.assistant.ui
import androidx.compose.runtime.Composable
@Composable
actual fun FridayDialogWindow(fullScreen: Boolean) {
    val view=androidx.compose.ui.platform.LocalView.current
    val palette=com.localfirst.assistant.ui.theme.LocalFridayPalette.current
    val window=(view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
    androidx.compose.runtime.SideEffect {
        window?.let {
            if(fullScreen){
                // Full screen and edge to edge like the main window: a dialog window is otherwise fitted below
                // the status bar yet sized to the whole display, so its bottom ran off screen, and it was told
                // the bars take no room, so nothing could pad for them.
                it.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,android.view.WindowManager.LayoutParams.MATCH_PARENT)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it,false)
                if(android.os.Build.VERSION.SDK_INT>=30){
                    val attributes=it.attributes
                    if(attributes.fitInsetsTypes!=0||attributes.layoutInDisplayCutoutMode!=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS){
                        attributes.fitInsetsTypes=0
                        attributes.layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        it.attributes=attributes
                    }
                }
            }
            val bars=androidx.core.view.WindowCompat.getInsetsController(it,it.decorView)
            bars.isAppearanceLightStatusBars=!palette.dark
            bars.isAppearanceLightNavigationBars=!palette.dark
            if(palette.reducedMotion)it.setWindowAnimations(0)
        }
    }
}
