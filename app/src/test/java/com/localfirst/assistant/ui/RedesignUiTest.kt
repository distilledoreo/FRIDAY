package com.localfirst.assistant.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.localfirst.assistant.MainActivity
import com.localfirst.assistant.settings.AppearanceSettings
import com.localfirst.assistant.settings.AppearanceSettingsStore
import com.localfirst.assistant.settings.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Native Compose renders on isolated Android runtime; no accounts, models or demo app data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RedesignUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun stable(theme:ThemeMode=ThemeMode.DARK) {
        compose.runOnIdle { AppearanceSettingsStore(compose.activity).useForTest { save(AppearanceSettings(theme=theme,reducedMotion=true)) } }
        compose.waitForIdle()
    }
    private fun capture(name:String) {
        val roots=compose.onAllNodes(isRoot(),useUnmergedTree=true)
        val count=roots.fetchSemanticsNodes().size
        roots[count-1].captureRoboImage("build/outputs/redesign/$name.png")
    }
    @Test fun emptyHomeHasOnlyTheComposerAndDiscoverableNavigation() {
        stable()
        compose.onNodeWithText("Message FRIDAY…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start voice conversation").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open personal dashboard").assertIsDisplayed()
        compose.onNodeWithText("Your dashboard").assertDoesNotExist()
        compose.onNodeWithText("Good afternoon").assertDoesNotExist()
        compose.onNodeWithText("Your chats will appear here.").assertIsNotDisplayed()
        capture("home-dark")
        stable(ThemeMode.LIGHT)
        capture("home-light")
    }
    @Test fun microphoneRemainsVisibleWhenTextIsReadyAndPlusMenuPreservesFeatures() {
        stable()
        compose.onNodeWithText("Message FRIDAY…").performTextInput("Draft kept while using voice")
        compose.onNodeWithContentDescription("Start voice conversation").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        capture("composer-text")
        compose.onNodeWithContentDescription("Attachments and tools").performClick()
        compose.onNodeWithText("Photos").assertIsDisplayed()
        compose.onNodeWithText("Create image").assertIsDisplayed()
        compose.onNodeWithText("Search the web").assertIsDisplayed()
        compose.onNodeWithText("Model and voice").assertIsDisplayed()
        capture("composer-menu")
    }
    @Test fun sidebarPreservesDestinationsAndHasAWorkingCloseControl() {
        stable()
        compose.onNodeWithContentDescription("FRIDAY. Open navigation").performClick()
        compose.onNodeWithText("Library").assertIsDisplayed()
        compose.onNodeWithText("Images").assertIsDisplayed()
        compose.onNodeWithText("FRIDAY").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Search chats").assertIsDisplayed()
        capture("sidebar-dark")
        compose.onNodeWithContentDescription("FRIDAY. Close navigation").performClick()
        compose.onNodeWithText("Search chats").assertIsNotDisplayed()
    }
    @Test fun dashboardStartsCollapsedAndCanExpandCollapseAndDismissWithoutGestures() {
        stable()
        compose.onNodeWithContentDescription("Open personal dashboard").performClick()
        compose.onNodeWithText("Your dashboard").assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Dashboard unavailable").fetchSemanticsNodes().isNotEmpty() }
        capture("dashboard-unavailable-partial")
        compose.onNodeWithText("Expand",substring=false).performClick()
        compose.onNodeWithText("Collapse",substring=false).assertIsDisplayed()
        capture("dashboard-unavailable-expanded")
        compose.onNodeWithText("Collapse",substring=false).performClick()
        compose.onNodeWithText("Expand",substring=false).assertIsDisplayed()
        compose.onNodeWithText("Close",substring=false).performClick()
        compose.onNodeWithText("Your dashboard").assertDoesNotExist()
    }
    @Test fun allExistingWorkspaceDestinationsUseNativeScreensAndAppearancePersists() {
        stable()
        val vm=(compose.activity.application as com.localfirst.assistant.AssistantApp).chat()
        for(destination in listOf(WorkspaceDestination.SETTINGS,WorkspaceDestination.APPEARANCE,WorkspaceDestination.PROJECTS,WorkspaceDestination.FILES,WorkspaceDestination.IMAGES,WorkspaceDestination.ACTIVITY,WorkspaceDestination.ACCOUNTS,WorkspaceDestination.MEMORY,WorkspaceDestination.DATA,WorkspaceDestination.BRIEF)) {
            compose.runOnIdle { vm.openWorkspace(destination) }
            compose.waitForIdle()
            capture("screen-${destination.name.lowercase()}")
            compose.onNodeWithContentDescription("Back",substring=false).performClick()
        }
        compose.runOnIdle { vm.openWorkspace(WorkspaceDestination.APPEARANCE) }
        compose.onNodeWithText("Light",substring=false).performClick()
        compose.runOnIdle { AppearanceSettingsStore(compose.activity).useForTest { org.junit.Assert.assertEquals(ThemeMode.LIGHT,state.value.theme) } }
        compose.onNodeWithContentDescription("Blue accent").performClick()
        compose.runOnIdle { AppearanceSettingsStore(compose.activity).useForTest { org.junit.Assert.assertEquals("88ADD0",state.value.accentHex) } }
        capture("appearance-light-blue")
    }

    @Test fun leftEdgeSwipeOpensTheSidebarWhileCentralSwipesAndAndroidBackBehaveNormally() {
        stable()
        compose.onRoot().performTouchInput { swipe(androidx.compose.ui.geometry.Offset(200f,180f),androidx.compose.ui.geometry.Offset(300f,180f),240) }
        compose.onNodeWithText("Search chats").assertIsNotDisplayed()
        compose.onRoot().performTouchInput { swipe(androidx.compose.ui.geometry.Offset(12f,180f),androidx.compose.ui.geometry.Offset(180f,180f),240) }
        compose.onNodeWithText("Search chats").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Search chats").assertIsNotDisplayed()
    }
    @Test fun keyboardInsetsKeepTheComposerAboveTheKeyboard() {
        stable()
        compose.onNodeWithText("Message FRIDAY…").performTextInput("Keyboard test")
        compose.runOnIdle {
            val insets=android.view.WindowInsets.Builder().setInsets(android.view.WindowInsets.Type.ime(),android.graphics.Insets.of(0,0,0,300)).setVisible(android.view.WindowInsets.Type.ime(),true).build()
            compose.activity.window.decorView.dispatchApplyWindowInsets(insets)
        }
        compose.waitForIdle()
        val mic=compose.onNodeWithContentDescription("Start voice conversation").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Composer must sit above the 300px keyboard: $mic",mic.bottom<=591f)
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        capture("composer-keyboard-insets")
    }
    @Test fun incognitoRetainsItsExplicitExitAndDarkHeaderInLightMode() {
        stable(ThemeMode.LIGHT)
        compose.onNodeWithContentDescription("FRIDAY. Open navigation").performClick()
        compose.onNodeWithText("Incognito · won’t be saved").performClick()
        compose.onNodeWithText("Exit",substring=false).assertIsDisplayed()
        compose.onNodeWithText("Incognito · Won’t be saved").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertFalse(androidx.core.view.WindowCompat.getInsetsController(compose.activity.window,compose.activity.window.decorView).isAppearanceLightStatusBars) }
        capture("incognito-light")
        compose.onNodeWithText("Exit",substring=false).performClick()
        compose.waitUntil(5000) { !(compose.activity.application as com.localfirst.assistant.AssistantApp).chat().state.value.privacy.incognito }
        compose.onNodeWithText("Exit",substring=false).assertDoesNotExist()
    }
    @Test fun voiceAndServerUsesTheSharedFullscreenFormAndBackNavigation() {
        stable()
        compose.runOnIdle { (compose.activity.application as com.localfirst.assistant.AssistantApp).chat().openSettings() }
        compose.onNodeWithText("Voice and server",substring=false).assertIsDisplayed()
        compose.onNodeWithText("Server address").assertIsDisplayed()
        capture("screen-voice-server")
        compose.onNodeWithContentDescription("Back",substring=false).performClick()
        compose.onNodeWithText("Voice and server",substring=false).assertDoesNotExist()
    }

    @Test fun ordinaryMotionSupportsTheNativeDrawerAndCompactDashboardDismissal() {
        stable()
        compose.runOnIdle {
            android.provider.Settings.Global.putFloat(compose.activity.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)
            AppearanceSettingsStore(compose.activity).useForTest { save(AppearanceSettings(reducedMotion=false)) }
        }
        compose.onNodeWithContentDescription("FRIDAY. Open navigation").performClick()
        compose.onNodeWithText("Search chats").assertIsDisplayed()
        compose.onNodeWithContentDescription("FRIDAY. Close navigation").performClick()
        compose.onNodeWithContentDescription("Open personal dashboard").performClick()
        compose.onNodeWithText("Your dashboard").assertIsDisplayed()
        capture("dashboard-native-motion-compact")
        compose.onNodeWithText("Close",substring=false).performClick()
        compose.onNodeWithText("Your dashboard").assertDoesNotExist()
    }

    @Test fun leavingTheDashboardForSettingsDoesNotReopenItOnReturn() {
        stable()
        compose.runOnIdle { AppearanceSettingsStore(compose.activity).useForTest { save(AppearanceSettings(reducedMotion=false)) } }
        compose.onNodeWithContentDescription("Open personal dashboard").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Dashboard unavailable").fetchSemanticsNodes().isNotEmpty() }
        val settings=compose.onAllNodesWithText("Settings",substring=false)
        settings[settings.fetchSemanticsNodes().size-1].performClick()
        compose.onNodeWithText("Personalization").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back",substring=false).performClick()
        compose.onNodeWithText("Your dashboard").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open personal dashboard").assertIsDisplayed()
    }

}

private fun AppearanceSettingsStore.useForTest(block:AppearanceSettingsStore.()->Unit) { try { block() } finally { close() } }
