package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.localfirst.assistant.settings.AppearanceSettings
import com.localfirst.assistant.settings.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Renders individual pieces for visual review; writes PNGs under build/outputs/polish. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PolishComponentTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String) {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/polish/$name.png")
    }

    @Test fun dashboard() {
        val vm = (org.robolectric.RuntimeEnvironment.getApplication() as com.localfirst.assistant.AssistantApp).chat()
        val snapshot = org.json.JSONObject("""{"date":"2026-10-08","timezone":"America/New_York","sections":[
            {"kind":"calendar","status":"available","events":[
              {"title":"Team sync","start":{"dateTime":"2026-10-08T10:00:00-04:00"},"end":{"dateTime":"2026-10-08T10:30:00-04:00"},"location":"Google Meet"},
              {"title":"Design review","start":{"dateTime":"2026-10-08T13:00:00-04:00"},"end":{"dateTime":"2026-10-08T14:00:00-04:00"},"location":"Figma"},
              {"title":"Gym","start":{"dateTime":"2026-10-08T15:30:00-04:00"},"end":{"dateTime":"2026-10-08T16:30:00-04:00"}}]},
            {"kind":"weather","status":"available","temperature_2m_max":72,"conditions":"Clear sky","location":"Plano"}]}""")
        val followups = listOf(org.json.JSONObject("""{"id":"f1","title":"Call the insurance company","due":1791500000}"""))
        val proposals = listOf(org.json.JSONObject("""{"id":"p1","kind":"followup","source":{"title":"Prepare for design review","detail":"Here are key points from your notes"}}"""))
        var mode by androidx.compose.runtime.mutableStateOf(ThemeMode.DARK)
        compose.setContent {
            com.localfirst.assistant.ui.theme.AssistantTheme(preferences = AppearanceSettings(theme = mode, reducedMotion = true)) {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow) {
                    PersonalDashboard(ChatUiState(dashboard = DashboardState(loaded = true, snapshot = snapshot, followups = followups, proposals = proposals)), vm, onClose = {})
                }
            }
        }
        capture("dashboard-dark")
        compose.runOnIdle { mode = ThemeMode.LIGHT }
        capture("dashboard-light")
    }


    @Test fun voice() {
        var phase by androidx.compose.runtime.mutableStateOf(com.localfirst.assistant.voice.VoicePhase.LISTENING)
        compose.setContent {
            com.localfirst.assistant.ui.theme.AssistantTheme(preferences = AppearanceSettings(reducedMotion = true)) {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
                        InlineVoice(com.localfirst.assistant.voice.VoiceUiState(phase = phase, heard = "What's on my calendar this afternoon?", speaking = "You have a design review at one and the gym at three thirty.", level = .7f), {}, {}, {}, {})
                        Composer(draft = "", busy = false, editing = false, onDraftChange = {}, onSend = {}, onStop = {}, onCancelEdit = {}, onVoice = {}, voiceActive = true, sendEnabled = false, onDictate = {})
                    }
                }
            }
        }
        capture("voice-listening")
        compose.runOnIdle { phase = com.localfirst.assistant.voice.VoicePhase.SPEAKING }
        capture("voice-speaking")
    }

    @Test fun components() {
        val f = "```friday"
        val a = "Here's how they compare for what you care about.\n\n$f\n" +
            "{\"type\":\"compare\",\"items\":[{\"title\":\"OnePlus 13\",\"badge\":\"Best battery\",\"subtitle\":\"6000 mAh · 100 W charging\",\"points\":[\"Two-day battery\",\"Hasselblad tuning\"]},{\"title\":\"Pixel 10 Pro\",\"badge\":\"Best camera\",\"subtitle\":\"5050 mAh · 45 W\",\"points\":[\"Best low light\",\"7 years of updates\"]}]}\n```\n\n$f\n" +
            "{\"type\":\"choices\",\"prompt\":\"Which matters more?\",\"options\":[\"Battery\",\"Camera\",\"Both equally\"]}\n```"
        val b = "Here's a simple way to make cold brew.\n\n$f\n" +
            "{\"type\":\"steps\",\"items\":[{\"title\":\"Grind coarsely\",\"detail\":\"1 cup beans, like sea salt\"},{\"title\":\"Steep 12–18 hours\",\"detail\":\"4 cups cold water, in the fridge\"},{\"title\":\"Strain and dilute\",\"detail\":\"1:1 with water or milk\"}]}\n```\n\n$f\n" +
            "{\"type\":\"facts\",\"items\":[{\"label\":\"Ratio\",\"value\":\"1:4\",\"note\":\"grounds to water\"},{\"label\":\"Steep\",\"value\":\"12–18 h\"}]}\n```"
        compose.setContent {
            com.localfirst.assistant.ui.theme.AssistantTheme(preferences = AppearanceSettings(reducedMotion = true)) {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp)) {
                        AssistantMessage(com.localfirst.assistant.presentation.TranscriptItem.Assistant(1, a, false, true), true, {}, {}, {})
                        AssistantMessage(com.localfirst.assistant.presentation.TranscriptItem.Assistant(2, b, false, false), true, {}, {}, {})
                    }
                }
            }
        }
        capture("components")
    }

    @Test fun ambientMoves() {
        var at by androidx.compose.runtime.mutableFloatStateOf(0f)
        compose.setContent {
            com.localfirst.assistant.ui.theme.AssistantTheme(preferences = AppearanceSettings(reducedMotion = false)) {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    FridayAmbient(null, 0f, false, androidx.compose.ui.Modifier.fillMaxSize(), fixedTime = at)
                }
            }
        }
        capture("ambient-t0")
        compose.runOnIdle { at = 2f }
        capture("ambient-t2")
        compose.runOnIdle { at = 4f }
        capture("ambient-t4")
    }

    @Test fun brand() {
        compose.setContent {
            com.localfirst.assistant.ui.theme.AssistantTheme(preferences = AppearanceSettings(reducedMotion = true)) {
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
                        FridayBrand(0f, {}); FridayBrand(.5f, {}); FridayBrand(1f, {})
                    }
                }
            }
        }
        capture("brand-stages")
    }
}
