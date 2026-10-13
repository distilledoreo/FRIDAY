package com.localfirst.assistant.ui.genui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.localfirst.assistant.AssistantPrompts
import com.localfirst.assistant.genui.UiAction
import com.localfirst.assistant.genui.UiCompiler
import com.localfirst.assistant.genui.UiNode
import com.localfirst.assistant.genui.UiRuntime
import com.localfirst.assistant.settings.AppearanceSettings
import com.localfirst.assistant.settings.ThemeMode
import com.localfirst.assistant.ui.theme.AssistantTheme
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Draws interface replies with the native renderer and drives them like a user. PNGs go to build/outputs/genui. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GenUiRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String) {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/genui/$name.png")
    }

    private val reply = """
        ## Team plan estimate
        Drag the slider to see the **monthly price**.
        {@body const [seats, setSeats] = useState(8)}
        {@body const [plan, setPlan] = useState('team')}
        {@body const price = seats * (plan === 'team' ? 29 : 49)}
        <card title="Your estimate" gap={3}>
          <segmented options={[{label: 'Team', value: 'team'}, {label: 'Business', value: 'business'}]} value={plan} onChange={setPlan}/>
          <slider label="Seats" min={1} max={50} value={seats} onChange={setSeats}/>
          <row justify="between"><text color="muted">Monthly</text><title size="xl">${'$'}{price}/mo</title></row>
          <button onClick={() => setSeats(seats + 1)}>Add a seat</button>
          <button variant="outline" onClick={() => GenUI.issueNewTurn(`Set up ${'$'}{plan} for ${'$'}{seats} seats`)}>Set it up</button>
        </card>
        <grid columns={2} gap={2}>
          <stat label="Seats" value={seats} delta="+2" trend={[3, 4, 6, 8]}/>
          <stat label="Yearly" value={'$' + (price * 12).toLocaleString()}/>
        </grid>
        <chart type="bar" title="Cost by seats" labels={['5', '10', '20']} values={[145, 290, 580]} format="currency"/>
        <table columns={['Plan', 'Per seat']} rows={[['Team', '${'$'}29'], ['Business', '${'$'}49']]}/>
        <tabs><tab label="Notes"><text>Billed monthly.</text></tab><tab label="FAQ"><text>Cancel any time.</text></tab></tabs>
        <callout tone="warning" title="Heads up">Prices exclude tax.</callout>
    """.trimIndent()

    @Test
    fun anInterfaceReplyRendersAndRespondsWithoutTheModel() {
        val runtime = UiRuntime()
        var nodes by mutableStateOf<List<UiNode>>(runtime.load(UiCompiler.compile(reply, complete = true)).nodes)
        val actions = mutableListOf<UiAction>()
        val context = GenUiContext(
            dispatch = { cb, args -> val r = runtime.dispatch(cb, args); nodes = r.nodes; actions += r.actions },
            act = { actions += it },
            images = null,
            bindings = emptyMap(),
            animate = false,
        )
        compose.setContent {
            AssistantTheme(preferences = AppearanceSettings(theme = ThemeMode.DARK, reducedMotion = true)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        GenUiContent(nodes, context)
                    }
                }
            }
        }
        compose.onNodeWithText("\$232/mo").assertExists()
        compose.onNodeWithText("Your estimate").assertExists()
        compose.onNodeWithText("Billed monthly.").assertExists()
        capture("plan-estimate")

        compose.onNodeWithText("Add a seat").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("\$261/mo").assertExists()

        compose.onAllNodesWithText("Business")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("\$441/mo").assertExists()

        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("FAQ"))
        compose.onNodeWithText("FAQ").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cancel any time.").assertExists()

        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Set it up"))
        compose.onNodeWithText("Set it up").performClick()
        compose.waitForIdle()
        assertEquals(listOf(UiAction.NewTurn("Set up business for 9 seats")), actions)
        capture("plan-estimate-after")
    }

    @Test
    fun formsGatherNamedFieldsIntoTheUsersReply() {
        val runtime = UiRuntime()
        var nodes by mutableStateOf<List<UiNode>>(runtime.load(UiCompiler.compile("""
            <form submitLabel="Trip details">
              <input name="city" label="City" defaultValue="Lisbon"/>
              <stepper name="nights" label="Nights" defaultValue={3}/>
              <toggle name="flexible" label="Flexible dates"/>
              <button submit>Plan it</button>
            </form>
        """.trimIndent(), complete = true)).nodes)
        val actions = mutableListOf<UiAction>()
        val context = GenUiContext({ cb, args -> nodes = runtime.dispatch(cb, args).nodes }, { actions += it }, null, emptyMap(), false)
        compose.setContent {
            AssistantTheme(preferences = AppearanceSettings(theme = ThemeMode.LIGHT, reducedMotion = true)) {
                Surface(Modifier.fillMaxSize()) { GenUiContent(nodes, context, Modifier.padding(16.dp)) }
            }
        }
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("Plan it").performClick()
        compose.waitForIdle()
        capture("form")
        val sent = (actions.single() as UiAction.NewTurn).text
        assertTrue(sent, sent.startsWith("Trip details"))
        assertTrue(sent, sent.contains("- City: Lisbon"))
        assertTrue(sent, sent.contains("- Nights: 4"))
        assertTrue(sent, sent.contains("- Flexible dates: false"))
    }

    @Test
    fun theSystemPromptTeachesTheInterfaceLanguageOnlyWhenOn() {
        val now = ZonedDateTime.parse("2026-10-11T09:00:00-05:00[America/Chicago]")
        val on = AssistantPrompts.system(now, interactiveAnswers = true)
        val off = AssistantPrompts.system(now, interactiveAnswers = false)
        assertTrue(on.contains("{@body const [seats, setSeats] = useState(8)}"))
        assertTrue(on.contains("- <slider"))
        assertFalse(on.contains("```friday"))
        assertTrue(off.contains("fenced block whose language is friday"))
        assertFalse(off.contains("{@body"))
        // trimIndent still applied: no line keeps the source indentation.
        assertTrue(on.lines().none { it.startsWith("        ") && !it.startsWith("          <") })
    }
    @Test fun enablingInteractiveAnswersUpdatesAnAlreadyOpenReply() {
        var enabled by mutableStateOf(false)
        compose.setContent {
            AssistantTheme(AppearanceSettings(reducedMotion = true)) {
                com.localfirst.assistant.ui.SharedAssistantMessage(
                    com.localfirst.assistant.presentation.TranscriptItem.Assistant(0, "<title>Interactive</title>", false, true),
                    actionsEnabled = true, onCopy = {},
                    interfaceContent = if (enabled) ({ androidx.compose.material3.Text("Interactive controls") }) else null,
                )
            }
        }
        compose.onNodeWithText("Interactive controls").assertDoesNotExist()
        compose.runOnIdle { enabled = true }
        compose.onNodeWithText("Interactive controls").assertIsDisplayed()
        compose.runOnIdle { enabled = false }
        compose.onNodeWithText("Interactive controls").assertDoesNotExist()
    }

}
