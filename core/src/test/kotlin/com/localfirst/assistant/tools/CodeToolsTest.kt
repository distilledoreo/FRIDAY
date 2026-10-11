package com.localfirst.assistant.tools

import com.localfirst.assistant.conversation.ChatPrivacy
import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.presentation.Transcript
import com.localfirst.assistant.presentation.TranscriptItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CodeToolsTest {
    private class Gateway : WorkspaceGateway {
        val calls = mutableListOf<Triple<String, String, String?>>()
        override suspend fun toolRequest(path: String, method: String, body: String?): String {
            calls += Triple(path, method, body)
            return when (path) {
                "/workspace/code/projects" -> if (method == "POST") """{"id":"p1","repo":"octo/demo"}""" else """[{"id":"p1","repo":"octo/demo"}]"""
                "/workspace/code/tasks" -> if (method == "POST") """{"id":"0123456789ab","title":"Fix login","repo":"octo/demo","branch":"friday/fix-login-012345","state":"busy"}"""
                    else """[{"id":"0123456789ab","title":"Fix login","repo":"octo/demo","state":"idle","pr_url":"https://github.com/octo/demo/pull/7"}]"""
                else -> VIEW
            }
        }
    }

    @Test fun startingATaskAddsTheRepositoryThenStartsOnABranch() = runBlocking {
        val gateway = Gateway()
        val registry = ToolRegistry().apply { codeTools(gateway).forEach(::register) }
        val call = ToolCall("c1", CodeToolNames.START, """{"repo":"https://github.com/octo/demo.git","prompt":"Fix the login bug","base":"develop"}""")
        registry.execute(call, ToolConfirmer { false })
        assertTrue(gateway.calls.isEmpty())
        val result = registry.execute(call, ToolConfirmer { request -> assertTrue(request.prompt.contains("octo/demo")); true })
        assertTrue(result.success)
        assertEquals(listOf("/workspace/code/projects" to "POST", "/workspace/code/tasks" to "POST"), gateway.calls.map { it.first to it.second })
        assertEquals("octo/demo", Json.parseToJsonElement(gateway.calls[0].third!!).jsonObject["repo"]!!.jsonPrimitive.content)
        val body = Json.parseToJsonElement(gateway.calls[1].third!!).jsonObject
        assertEquals(listOf("p1", "Fix the login bug", "develop"), listOf("project", "prompt", "base").map { body[it]!!.jsonPrimitive.content })
    }

    @Test fun badRepositoriesAndIdsNeverReachThePc() = runBlocking {
        val gateway = Gateway()
        val tools = codeTools(gateway).associateBy { it.name }
        for (repo in listOf("demo", "octo/../x", "a/b/c", "octo/de mo")) {
            assertTrue(runCatching { tools.getValue(CodeToolNames.START).execute(buildJsonObject { put("repo", repo); put("prompt", "x") }) }.isFailure)
        }
        for (id in listOf("../projects", "0123456789ab?x=1", "")) {
            assertTrue(runCatching { tools.getValue(CodeToolNames.GET).execute(buildJsonObject { put("id", id) }) }.isFailure)
        }
        assertTrue(gateway.calls.isEmpty())
        assertTrue(codeTools(gateway).none { ChatPrivacy(incognito = true).allowsTool(it.name) })
        assertFalse(tools.getValue(CodeToolNames.LIST).requiresConfirmation)
    }

    @Test fun readingATaskGivesTheModelTheEssentials() = runBlocking {
        val result = codeTools(Gateway()).first { it.name == CodeToolNames.GET }.execute(buildJsonObject { put("id", "0123456789ab") })
        val json = Json.parseToJsonElement(result.content).jsonObject
        assertEquals("Done: the login form now validates emails.", json["summary"]!!.jsonPrimitive.content)
        assertEquals("waiting", json["state"]!!.jsonPrimitive.content)
        assertEquals(1, json["waiting_for_user"]!!.jsonPrimitive.int)
        assertEquals(listOf("modified src/login.ts +4 -1"), json["files"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(result.content.contains("npm test output"))
        val listed = codeTools(Gateway()).first { it.name == CodeToolNames.LIST }.execute(JsonObject(emptyMap())).content
        assertTrue(listed.contains("octo/demo") && listed.contains("pull/7"))
    }

    @Test fun startedTasksShowAsCodeCardsInTheTranscript() {
        val content = """{"id":"0123456789ab","title":"Fix login"}"""
        val messages = listOf(
            Message.User("Fix the login bug in octo/demo"),
            Message.ToolCall("c1", CodeToolNames.START, """{"repo":"octo/demo","prompt":"Fix"}"""),
            Message.ToolResult("c1", CodeToolNames.START, content, success = true),
        )
        val step = Transcript.items(messages, busy = false).filterIsInstance<TranscriptItem.ToolActivity>().single().steps.single()
        assertEquals(content, step.codeTask)
        assertNull(step.resultContent)
        assertEquals("Started coding task", step.label)
    }

    private companion object {
        val VIEW = """{"id":"0123456789ab","title":"Fix login","repo":"octo/demo","branch":"friday/fix-login-012345","base":"main","state":"busy",
            "session":{"state":"waiting","items":[{"kind":"text","role":"user","text":"Repository: octo/demo"},{"kind":"tool","title":"npm test","output":"npm test output"},
            {"kind":"text","role":"assistant","text":"Done: the login form now validates emails."}],"permissions":[{"id":"per_1"}],"questions":[]},
            "changes":{"additions":4,"deletions":1,"commits":1,"uncommitted":0,"files":[{"path":"src/login.ts","status":"modified","additions":4,"deletions":1}]},"pr":null}"""
    }
}
