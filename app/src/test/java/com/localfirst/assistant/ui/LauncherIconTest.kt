package com.localfirst.assistant.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.localfirst.assistant.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Renders the adaptive launcher icon as launchers mask it: circle, squircle and at small size. */
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],qualifiers="w600dp-h400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    @get:Rule val compose=createComposeRule()
    @Test fun launcherIconRendersInCommonMasks() {
        compose.setContent {
            Row(Modifier.testTag("icons").background(Color(0xFF3A4048)).padding(24.dp),horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                for((size,shape) in listOf(160.dp to CircleShape,160.dp to RoundedCornerShape(36),48.dp to CircleShape)) {
                    // The visible part of a 108dp adaptive layer is the middle 72dp.
                    Box(Modifier.size(size).clip(shape)) {
                        for(layer in listOf(R.drawable.ic_launcher_background,R.drawable.ic_launcher_foreground))
                            Image(painterResource(layer),null,Modifier.requiredSize(size*1.5f))
                    }
                }
            }
        }
        compose.onNodeWithTag("icons").captureRoboImage("build/outputs/redesign/launcher-icon.png")
    }
}
