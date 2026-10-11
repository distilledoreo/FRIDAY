package com.localfirst.assistant.ui.code

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.localfirst.assistant.settings.AppearanceSettings
import com.localfirst.assistant.settings.ThemeMode
import com.localfirst.assistant.ui.theme.AssistantTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** The Code workspace against a fake PC: parsing, flows and how its screens draw. PNGs go to build/outputs/code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CodeWorkspaceTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun close() { scope.cancel() }

    private class FakePc {
        val calls = mutableListOf<Triple<String, String, JSONObject?>>()
        var failStart = false
        suspend fun request(path: String, method: String, body: JSONObject?): String {
            calls += Triple(path, method, body)
            return when {
                path == "/workspace/code/projects" && method == "GET" -> """[{"id":"p1","repo":"octo/demo","default_branch":"main","trusted":0,"open_tasks":1}]"""
                path == "/workspace/code/tasks" && method == "GET" -> "[$TASK]"
                path == "/workspace/code/tasks" && method == "POST" ->
                    if (failStart) throw java.io.IOException("""Computer returned HTTP 409: {"detail":"Four PC tasks are already active"}""") else TASK
                path.endsWith("/branches") -> """{"default":"main","branches":["main","develop"]}"""
                path == "/workspace/code/tasks/0123456789ab" -> VIEW
                path == "/workspace/code/tasks/0123456789ab/diff" -> DIFF
                else -> "{}"
            }
        }
    }

    @Test fun parsesTasksViewsAndDiffs() {
        val view = CodeTaskView.from(JSONObject(VIEW))
        assertEquals("waiting", view.task.state)
        assertTrue(view.waiting)
        assertEquals("Needs you", codeStateLabel(view.task))
        assertEquals(listOf("user", "tool", "tool", "assistant"), view.items.map { if (it.kind == "tool") "tool" else it.role })
        assertEquals(CodeStats(files = 2, additions = 5, deletions = 1, commits = 1, uncommitted = 1), view.changes.stats)
        assertEquals("Add a greeting option", taskRequest(view.items.first().text))
        val pr = CodePr.from(JSONObject("""{"url":"https://github.com/octo/demo/pull/7","state":"open","draft":false,"merged":false,"checks":{"total":3,"failed":1,"pending":0}}"""))!!
        assertEquals("Pull request open", pr.label)
        assertEquals(1, pr.failed)
        val lines = UnifiedDiff.parse(CodeChanges.from(JSONObject(DIFF)).files.first { it.path == "app.py" }.patch!!)
        assertEquals(listOf(DiffLine.Kind.HUNK, DiffLine.Kind.CONTEXT, DiffLine.Kind.REMOVED, DiffLine.Kind.ADDED, DiffLine.Kind.ADDED, DiffLine.Kind.NOTE), lines.map { it.kind })
        assertEquals(listOf(1, 2, null, null), lines.subList(1, 5).map { it.old }.let { listOf(it[0], it[1], it[2], it[3]) })
        assertEquals(listOf(1, null, 2, 3), lines.subList(1, 5).map { it.new })
        assertEquals("Four PC tasks are already active", CodeWorkspace.friendly(java.io.IOException("""Computer returned HTTP 409: {"detail":"Four PC tasks are already active"}"""), "fallback"))
        assertEquals("fallback", CodeWorkspace.friendly(java.io.IOException("timeout"), "fallback"))
    }

    @Test fun startingATaskOpensItAndErrorsExplainThemselves() {
        val pc = FakePc()
        val code = CodeWorkspace(scope, pc::request)
        code.open()
        compose.waitForIdle()
        assertEquals("p1", code.state.value.project?.id)
        assertEquals(listOf("main", "develop"), code.state.value.branches)
        code.selectBase("develop")
        pc.failStart = true
        code.startTask("Add a greeting option")
        compose.waitForIdle()
        assertEquals("Four PC tasks are already active", code.state.value.error)
        assertNull(code.state.value.taskId)
        pc.failStart = false
        var cleared = false
        code.startTask("Add a greeting option") { cleared = true }
        compose.waitForIdle()
        assertTrue(cleared)
        val start = pc.calls.last { it.first == "/workspace/code/tasks" && it.second == "POST" }.third!!
        assertEquals("develop", start.getString("base"))
        assertEquals("0123456789ab", code.state.value.taskId)
        assertEquals("waiting", code.state.value.view?.task?.state)
        code.replyPermission("per_1", "once", false)
        compose.waitForIdle()
        assertTrue(pc.calls.any { it.first == "/workspace/pc/permissions/per_1" && it.third?.getString("reply") == "once" })
    }

    @Test fun homeAndTaskScreensDraw() {
        val pc = FakePc()
        val code = CodeWorkspace(scope, pc::request)
        compose.setContent {
            AssistantTheme(preferences = AppearanceSettings(theme = ThemeMode.DARK, reducedMotion = true)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by code.state.collectAsState()
                    if (state.taskId != null) CodeTaskScreen(state, code) else CodeHome(state, code)
                }
            }
        }
        code.open()
        compose.waitForIdle()
        compose.onNodeWithText("Add a greeting option").assertExists()
        compose.onNodeWithText("demo").assertExists()
        capture("code-home")
        compose.onNodeWithText("Describe a coding task").performTextInput("Fix the flaky test")
        compose.onNodeWithContentDescription("Start task").assertExists()
        compose.onNodeWithText("Add a greeting option").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Run the tests", substring = true).assertExists()
        compose.onNodeWithText("FRIDAY needs your permission").assertExists()
        capture("code-task-chat")
        compose.onNodeWithText("Changes").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("app.py").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("print(\"hi\")")).assertExists()
        capture("code-task-changes")
    }

    private fun capture(name: String) {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/code/$name.png")
    }

    private companion object {
        const val TASK = """{"id":"0123456789ab","project":"p1","repo":"octo/demo","title":"Add a greeting option","branch":"friday/add-a-greeting-option-012345","base":"main","state":"busy","pr_url":null,"created":1760000000,"stats":{"files":2,"additions":5,"deletions":1,"commits":1,"uncommitted":1}}"""
        val VIEW = """{"id":"0123456789ab","project":"p1","repo":"octo/demo","title":"Add a greeting option","branch":"friday/add-a-greeting-option-012345","base":"main","state":"busy","pr_url":null,"created":1760000000,"stats":{},
            "session":{"id":"ses_1","busy":true,"state":"waiting","model":"qwen3-coder:free","items":[
              {"id":"i1","role":"user","kind":"text","text":"Repository: octo/demo on GitHub. Base branch: main. Your branch: friday/add-a-greeting-option-012345 (already checked out in this worktree).\n\nTask:\nAdd a greeting option"},
              {"id":"i2","role":"assistant","kind":"tool","tool":"read","status":"completed","title":"app.py","output":"print(\"hello\")"},
              {"id":"i3","role":"assistant","kind":"tool","tool":"edit","status":"completed","title":"Edit app.py","output":""},
              {"id":"i4","role":"assistant","kind":"text","text":"I added a **greeting** option. Run the tests next."}],
             "permissions":[{"id":"per_1","session":"ses_1","kind":"bash","detail":"python -m pytest","always":["python -m pytest *"],"sudo":false,"diff":null}],"questions":[]},
            "changes":{"files":[{"path":"app.py","status":"modified","binary":false,"additions":2,"deletions":1},{"path":"docs/notes.md","status":"added","binary":false,"additions":3,"deletions":0,"untracked":true}],"additions":5,"deletions":1,"uncommitted":1,"commits":1},
            "pr":null}"""
        val DIFF = """{"files":[{"path":"app.py","status":"modified","binary":false,"additions":2,"deletions":1,"patch":"diff --git a/app.py b/app.py\nindex 1..2 100644\n--- a/app.py\n+++ b/app.py\n@@ -1,2 +1,3 @@ def main\n import sys\n-print(\"hello\")\n+print(\"hello\")\n+print(\"hi\")\n\\ No newline at end of file\n","truncated":false},
            {"path":"docs/notes.md","status":"added","binary":false,"untracked":true,"additions":3,"deletions":0,"patch":"--- /dev/null\n+++ b/docs/notes.md\n@@ -0,0 +1,3 @@\n+# Notes\n+one\n+two\n","truncated":false}],"additions":5,"deletions":1,"uncommitted":1,"commits":1}"""
    }
}
