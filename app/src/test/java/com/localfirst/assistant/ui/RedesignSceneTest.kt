package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.localfirst.assistant.presentation.*
import com.localfirst.assistant.settings.*
import com.localfirst.assistant.ui.theme.AssistantTheme
import com.localfirst.assistant.voice.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Isolated validation fixtures; these examples are never shipped as user data or app suggestions. */
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],qualifiers="w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
class RedesignSceneTest {
    @get:Rule val compose=createComposeRule()
    private fun capture(name:String) { val roots=compose.onAllNodes(isRoot(),useUnmergedTree=true);roots[roots.fetchSemanticsNodes().size-1].captureRoboImage("build/outputs/redesign/$name.png") }
    @Test fun nativeResponsesShowTablesAndKeepExpandableContentAndCopyWorking() {
        val table="| Option | Status |\n| --- | --- |\n| Source result | Ready |"
        val long="This is a long response in the isolated renderer test. ".repeat(40)
        var copies=0
        compose.setContent { AssistantTheme(preferences=AppearanceSettings(reducedMotion=true)) { Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            AssistantMessage(TranscriptItem.Assistant(1,"A short answer.",false,false),true,{copies++},{})
            ResponseRenderer(table,ResponseLayout.choose(table)) { copies++ }
            ResponseRenderer(long,ResponseLayout.choose(long)) { copies++ }
        } } } }
        capture("conversation-rich")
        compose.onNodeWithText("Source result").assertIsDisplayed()
        compose.onNodeWithText("Expand response").performClick()
        compose.onNodeWithText("Response",substring=false).assertIsDisplayed()
        compose.onNodeWithText("Copy",substring=false).performClick()
        assertEquals(1,copies)
        capture("response-expanded")
        compose.onNodeWithContentDescription("Back to conversation").performClick()
        compose.onNodeWithText("Expand response").assertIsDisplayed()
    }
    @Test fun voiceStatesStayInlineAndControlsInvokeTheExistingActions() {
        var phase by mutableStateOf(VoicePhase.LISTENING)
        var ends=0;var pauses=0;var interrupts=0
        compose.setContent { AssistantTheme(preferences=AppearanceSettings(reducedMotion=true)) { Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) { Column(Modifier.padding(top=400.dp)) {
            InlineVoice(VoiceUiState(phase=phase,heard="Live transcription from the isolated test",level=.7f,note=if(phase==VoicePhase.PAUSED)"Microphone unavailable. Check permission and resume."else null),{pauses++},{phase=VoicePhase.LISTENING},{interrupts++},{ends++})
        } } } }
        compose.onNodeWithText("Live transcription from the isolated test").assertIsDisplayed()
        compose.onNodeWithText("Pause",substring=false).performClick();assertEquals(1,pauses)
        capture("voice-listening")
        compose.runOnIdle { phase=VoicePhase.THINKING };capture("voice-thinking")
        compose.onNodeWithText("Interrupt").performClick();assertEquals(1,interrupts)
        compose.runOnIdle { phase=VoicePhase.SPEAKING };capture("voice-speaking")
        compose.runOnIdle { phase=VoicePhase.PAUSED };capture("voice-paused-error")
        compose.onNodeWithText("Resume").performClick()
        compose.onNodeWithText("Listening",substring=false).assertIsDisplayed()
        compose.onNodeWithContentDescription("End voice conversation").performClick();assertEquals(1,ends)
    }
    @Test fun nativeCatalogUsesActualToolRowsAndAnExpandablePlainTextFallback() {
        val json="""{"events":[{"title":"Calendar test source","start":"2026-10-08T14:00:00Z"}]}"""
        compose.setContent { AssistantTheme(preferences=AppearanceSettings(reducedMotion=true)) { Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) { Column(Modifier.padding(20.dp)) { ToolResultSurface("read_account_calendar",json,"test-call") } } } }
        compose.onNodeWithText("Calendar test source").assertIsDisplayed()
        compose.onNodeWithText("View result · 1 item").performClick()
        compose.onNodeWithText("Complete source result").assertIsDisplayed()
        compose.onNodeWithText(json,substring=false).assertIsDisplayed()
        capture("tool-result-expanded")
    }
    @Test fun wordmarkMorphAndLargeTextUseTheSameNativeComponents() {
        var preferences by mutableStateOf(AppearanceSettings(reducedMotion=true))
        compose.setContent { AssistantTheme(preferences=preferences,onPreferencesChange={preferences=it}) { Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) { Column {
            Row { FridayBrand(0f,{});FridayBrand(.5f,{}) }
            FridayBrand(1f,{})
            CompositionLocalProvider(LocalDensity provides Density(1f,1.8f)) { Box(Modifier.width(360.dp).weight(1f)) { AppearancePage() } }
        } } } }
        capture("wordmark-large-type")
        compose.onNodeWithText("Dark",substring=false).assertIsDisplayed()
        compose.onNodeWithText("Light",substring=false).assertIsDisplayed()
        compose.onNodeWithText("Follow system",substring=false).assertIsDisplayed()
        compose.onNodeWithText("Light",substring=false).performClick()
        compose.runOnIdle { assertEquals(ThemeMode.LIGHT,preferences.theme) }
        capture("appearance-large-type-light")
    }
    @Test fun narrowLandscapeWithLargeTextKeepsTheComposerAndNavigationUsable() {
        val vm=(org.robolectric.RuntimeEnvironment.getApplication() as com.localfirst.assistant.AssistantApp).chat()
        compose.setContent {
            val config=android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply { screenWidthDp=360;screenHeightDp=411 }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides config,LocalDensity provides Density(1f,1.8f)) {
                AssistantTheme(preferences=AppearanceSettings(reducedMotion=true)) { Box(Modifier.size(360.dp,411.dp).testTag("landscape")) { ChatScreen(vm) } }
            }
        }
        compose.onNodeWithText("Message FRIDAY…").performTextInput("A longer multiline draft still leaves all controls accessible")
        compose.onNodeWithContentDescription("Start voice conversation").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        compose.onNodeWithContentDescription("FRIDAY. Open navigation").assertIsDisplayed()
        compose.onNodeWithTag("landscape").captureRoboImage("build/outputs/redesign/composer-narrow-landscape-large.png")
    }
    @Test fun connectedButEmptyDashboardShowsSourceBackedEmptyStates() {
        val vm=(org.robolectric.RuntimeEnvironment.getApplication() as com.localfirst.assistant.AssistantApp).chat()
        val snapshot=org.json.JSONObject("""{"date":"2026-10-08","timezone":"UTC","sections":[{"kind":"calendar","status":"available","events":[]},{"kind":"weather","status":"not_configured"}]}""")
        compose.setContent { AssistantTheme(preferences=AppearanceSettings(reducedMotion=true)) { Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surfaceContainerLow) {
            PersonalDashboard(ChatUiState(dashboard=DashboardState(loaded=true,snapshot=snapshot)),vm,onClose={})
        } } }
        compose.onNodeWithText("No events returned for today.").assertIsDisplayed()
        compose.onNodeWithText("No saved follow-ups.").assertIsDisplayed()
        compose.onNodeWithText("Nothing relevant to suggest right now.").performScrollTo().assertIsDisplayed()
        capture("dashboard-connected-empty")
    }

    @Test fun startingAnActualConversationMovesTheComposerToTheBottom() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val settings=com.localfirst.assistant.settings.ServerSettingsStore(app)
        settings.save(com.localfirst.assistant.settings.ServerSettings(baseUrl="https://example.test/v1",model="isolated-test-model"))
        val model=object:com.localfirst.assistant.model.ModelProvider {
            override suspend fun sendConversation(messages:List<com.localfirst.assistant.conversation.Message>,tools:List<com.localfirst.assistant.tools.ToolDefinition>)=com.localfirst.assistant.model.ModelResponse.TextResponse("The isolated test reply.")
        }
        val vm=ChatViewModel(settings,com.localfirst.assistant.conversation.FileConversationStore(java.io.File(app.cacheDir,"redesign-isolated-chat")),com.localfirst.assistant.tools.ToolRegistry(),{model})
        val owner=androidx.lifecycle.ViewModelStore().apply { put("ui-test",vm) }
        try {
            compose.setContent { AssistantTheme(preferences=AppearanceSettings(reducedMotion=true)) { ChatScreen(vm) } }
            val initial=compose.onNodeWithContentDescription("Start voice conversation").fetchSemanticsNode().boundsInRoot.bottom
            compose.onNodeWithText("Message FRIDAY…").performTextInput("Explain a triangle simply")
            compose.onNodeWithContentDescription("Send").performClick()
            compose.waitUntil(5000) { vm.state.value.messages.filterIsInstance<com.localfirst.assistant.conversation.Message.Assistant>().any { it.content=="The isolated test reply." } }
            compose.onNodeWithText("The isolated test reply.").assertIsDisplayed()
            val final=compose.onNodeWithContentDescription("Start voice conversation").fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("Composer moves below its home position",final>initial+150f)
            capture("conversation-composer-bottom")
        } finally { owner.clear() }
    }

    @Test fun fullDashboardUsesTheNativePartialAndExpandedSheetStates() {
        var visible by mutableStateOf(true)
        val vm=(org.robolectric.RuntimeEnvironment.getApplication() as com.localfirst.assistant.AssistantApp).chat()
        val snapshot=org.json.JSONObject("""{"date":"2026-10-08","timezone":"UTC","sections":[{"kind":"calendar","status":"available","events":[]}]}""")
        compose.setContent { AssistantTheme(preferences=AppearanceSettings(reducedMotion=false)) {
            if(visible)FridaySheet(onDismiss={visible=false}) { expanded,toggle,close ->
                PersonalDashboard(ChatUiState(dashboard=DashboardState(loaded=true,snapshot=snapshot)),vm,onClose=close,expanded=expanded,onToggle=toggle)
            }
        } }
        compose.onNodeWithText("Expand",substring=false).assertIsDisplayed()
        capture("dashboard-native-motion-partial")
        compose.onNodeWithText("Expand",substring=false).performClick()
        compose.onNodeWithText("Collapse",substring=false).assertIsDisplayed()
        capture("dashboard-native-motion-expanded")
        compose.onNodeWithText("Collapse",substring=false).performClick()
        compose.onNodeWithText("Expand",substring=false).assertIsDisplayed()
        compose.onNodeWithText("Close",substring=false).performClick()
        compose.onNodeWithText("Your dashboard").assertDoesNotExist()
    }

    @Test fun unsupportedInteractiveMarkupRemainsVisibleTextInsteadOfADeadWidget() {
        val text="<widget>Source payload</widget>"
        compose.setContent { AssistantTheme(preferences=AppearanceSettings(reducedMotion=true)) { Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) { Column(Modifier.padding(20.dp)) { ResponseRenderer(text,ResponseLayout.choose(text),{}) } } } }
        compose.onNodeWithText(text,substring=false).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

}
