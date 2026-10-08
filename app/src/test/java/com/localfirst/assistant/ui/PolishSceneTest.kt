package com.localfirst.assistant.ui

import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.localfirst.assistant.MainActivity
import com.localfirst.assistant.settings.AppearanceSettings
import com.localfirst.assistant.settings.AppearanceSettingsStore
import com.localfirst.assistant.settings.ThemeMode
import org.junit.Rule
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Renders screens for visual review; writes PNGs under build/outputs/polish. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PolishSceneTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun theme(mode: ThemeMode) {
        compose.runOnIdle { AppearanceSettingsStore(compose.activity).apply { save(AppearanceSettings(theme = mode, reducedMotion = true)); close() } }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/polish/$name.png")
    }

    @Test fun home() {
        theme(ThemeMode.DARK); capture("home-dark")
        theme(ThemeMode.LIGHT); capture("home-light")
    }
}
