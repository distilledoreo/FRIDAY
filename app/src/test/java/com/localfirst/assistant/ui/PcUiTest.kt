package com.localfirst.assistant.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.localfirst.assistant.MainActivity
import com.localfirst.assistant.settings.AppearanceSettings
import com.localfirst.assistant.settings.ThemeMode
import com.localfirst.assistant.ui.theme.AssistantTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Only owned synthetic task data. These fixtures never enter the runtime app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PcUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val permission=JSONObject("""{"id":"p_owned","kind":"bash","detail":"printf FRIDAY_PC_TEST","always":["printf *"],"sudo":false}""")
    private var reply:Triple<String,String,Boolean>?=null
    private var stopped=false
    private var answered:JSONArray?=null
    private fun render(questions:JSONArray=JSONArray(),scale:Float=1f,theme:ThemeMode=ThemeMode.DARK) {
        val task=JSONObject().put("id","ses_owned").put("title","Synthetic PC test").put("state","waiting").put("busy",true).put("model","Test provider")
            .put("permissions",if(questions.length()==0)JSONArray().put(permission)else JSONArray()).put("questions",questions).put("items",JSONArray())
        compose.runOnIdle {
            compose.activity.setContent {
                AssistantTheme(AppearanceSettings(theme=theme,reducedMotion=true)) {
                    CompositionLocalProvider(LocalDensity provides Density(1f,scale)) {
                        PcTaskContent(task,false,null,{}, {},{stopped=true},{id,value,remember->reply=Triple(id,value,remember)}, {_,answers->answered=answers},{})
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun capture(name:String) {
        val roots=compose.onAllNodes(isRoot(),useUnmergedTree=true)
        roots[roots.fetchSemanticsNodes().size-1].captureRoboImage("build/outputs/computer/$name.png")
    }
    @Test fun approvalChoicesAndStopUseHumanCallbacks() {
        render()
        compose.onNodeWithText("printf FRIDAY_PC_TEST").assertIsDisplayed()
        compose.onNodeWithText("Allow once").performClick()
        assertEquals(Triple("p_owned","once",false),reply)
        compose.onNodeWithContentDescription("Remember for future tasks").performClick()
        compose.onNodeWithText("Always for task").performClick()
        assertEquals(Triple("p_owned","always",true),reply)
        compose.onNodeWithText("Deny").performClick()
        assertEquals(Triple("p_owned","reject",false),reply)
        compose.onNodeWithText("Stop task").performClick()
        assertTrue(stopped)
        compose.onNodeWithText("Continue this PC task").assertIsNotEnabled()
        capture("approval-dark")
    }
    @Test fun questionAnswersAreExplicitAndLightThemeRenders() {
        val questions=JSONArray("""[{"id":"q_owned","questions":[{"header":"Destination","question":"Which owned fixture?","options":[{"label":"Fixture A","description":"Synthetic option"},{"label":"Fixture B","description":"Synthetic option"}],"custom":false}]}]""")
        render(questions,theme=ThemeMode.LIGHT)
        compose.onNodeWithText("Answer").assertIsNotEnabled()
        compose.onNodeWithText("Fixture B").performClick()
        compose.onNodeWithText("Answer").performClick()
        assertEquals("Fixture B",answered!!.getJSONArray(0).getString(0))
        capture("question-light")
    }
    @Test fun largeFontsKeepApprovalActionsReachable() {
        render(scale=1.6f)
        compose.onNodeWithText("Deny").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(Triple("p_owned","reject",false),reply)
        compose.onNodeWithText("Allow once").performScrollTo().assertIsDisplayed()
        capture("approval-large-font")
    }
}
