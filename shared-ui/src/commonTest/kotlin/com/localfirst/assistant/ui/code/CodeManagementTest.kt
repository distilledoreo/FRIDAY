package com.localfirst.assistant.ui.code

import com.localfirst.assistant.ui.wire.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.*

class CodeManagementTest {
    @Test fun controlSetupCollaborationAndExtensionActionsUseTheSamePortableContract() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<Triple<String, String, JSONObject?>>()
        val project = """{"id":"p","repo":"local/demo","kind":"local"}"""
        val task = """{"id":"task","project":"p","repo":"local/demo","kind":"local","session":{"busy":true,"items":[]},"queue":{"paused":false,"messages":[{"id":1,"prompt":"Next","state":"queued"}]},"conflicts":{"merging":true,"files":["app.py"]}}"""
        val code = CodeWorkspace(scope, { path, method, body ->
            calls += Triple(path, method, body)
            when {
                path == "/workspace/code/projects" -> "[$project]"
                path.endsWith("/projects/new") || path.endsWith("/projects/remote") -> project
                path.endsWith("/branches") -> """{"branches":["main"]}"""
                path == "/workspace/code/tasks" && method == "GET" -> "[]"
                path.endsWith("/environment") -> """{"steps":[],"environment":{}}"""
                path.endsWith("/collaboration") -> """{"head":"${"a".repeat(40)}","threads":[{"id":"thread","path":"app.py","line":2,"body":"Review","resolved":false}],"jobs":[{"name":"Unit","state":"failure","url":"https://example.invalid/job"}]}"""
                path.endsWith("/fork") -> """{"id":"child","parent":"task","relation":"fork","project":"p"}"""
                path.endsWith("/child") -> """{"id":"child","project":"p"}"""
                path == "/workspace/code/tasks/task" -> task
                path.endsWith("/extensions") -> """{"extensions":[],"mcp":[]}"""
                path.endsWith("/history") -> """{"items":[{"id":"part","role":"user","kind":"text","text":"Original brief"}],"before":null}"""
                else -> "{}"
            }
        })
        try {
            code.open(); code.openTask("task")
            assertEquals("queued", code.state.value.view!!.queue.messages.single().state)
            assertEquals(listOf("app.py"), code.state.value.view!!.conflicts.files)
            code.queue("Next task"); code.steer("Change direction"); code.pauseQueue(true); code.removeQueued(1)
            assertTrue(calls.any { it.first.endsWith("/queue") && it.second == "POST" && it.third!!.getString("prompt") == "Next task" })
            assertTrue(calls.any { it.first.endsWith("/steer") && it.third!!.getString("prompt") == "Change direction" })
            assertTrue(calls.any { it.first.endsWith("/queue") && it.second == "PUT" && it.third!!.optBoolean("paused") })
            assertTrue(calls.any { it.first.endsWith("/queue/1") && it.second == "DELETE" })
            code.loadCollaboration(); code.reviewAction("thread", "reply", "First\nSecond"); code.repairCi()
            assertEquals("a".repeat(40), calls.first { it.first.endsWith("/collaboration") && it.second == "POST" }.third!!.getString("head"))
            assertEquals("First\nSecond", calls.first { it.first.endsWith("/collaboration") && it.second == "POST" }.third!!.getString("body"))
            code.merge("ours", "app.py"); code.prepare()
            code.loadEnvironment("p"); assertNotNull(code.state.value.environmentRecipe)
            code.createProject("/tmp/project", "python") {}
            code.saveEnvironment("p", JSONObject("""{"steps":[],"environment":{}}""")) {}
            code.loadExtensions(); code.extensionAction("ext", "approve", "b".repeat(64))
            code.saveConnection("service", JSONObject("""{"type":"remote","url":"https://example.invalid/mcp"}""")) {}
            assertEquals("b".repeat(64), calls.first { it.first.endsWith("/ext/approve") }.third!!.getString("digest"))
            code.loadHistory("task"); assertEquals("Original brief", code.state.value.history.single().text)
            code.fork(); assertEquals("child", code.state.value.taskId)
        } finally { scope.cancel() }
    }
}
