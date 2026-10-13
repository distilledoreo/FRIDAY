package com.localfirst.assistant.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class NoticePlannerTest {
    private var jobs = buildJsonArray {}
    private var sessions = buildJsonArray { add(buildJsonObject { put("id", "s1"); put("state", "busy"); put("title", "Clean downloads") }) }
    private var now = 1_000_000L
    private val seen = mutableMapOf<String, String>()
    private val planner = NoticePlanner({ path, _, _ ->
        when (path.substringBefore('?')) {
            "/workspace/jobs" -> jobs
            "/workspace/agent/tasks" -> buildJsonArray {}
            "/workspace/pc/status" -> buildJsonObject { put("enabled", true) }
            "/workspace/pc/approvals" -> buildJsonObject { put("permissions", buildJsonArray { add(buildJsonObject { put("id", "p1"); put("detail", "Run rm?") }) }) }
            "/workspace/pc/sessions" -> sessions
            else -> error("offline")
        }
    }, seen, { now })

    @Test fun firstPollOnlyRecordsThenEachChangeIsAnnouncedOnce() = runTest {
        jobs = buildJsonArray { add(buildJsonObject { put("id", "old"); put("status", "completed"); put("result", "Old result"); put("prompt", "Old") }) }
        assertEquals(emptyList(), planner.poll(false))
        jobs = buildJsonArray {
            add(buildJsonObject { put("id", "old"); put("status", "completed"); put("result", "Old result"); put("prompt", "Old") })
            add(buildJsonObject { put("id", "new"); put("status", "completed"); put("result", "Done!"); put("prompt", "Summarize") })
            add(buildJsonObject { put("id", "chat"); put("status", "waiting"); put("item_id", "c1"); put("pending", buildJsonObject { put("call_id", "k"); put("title", "Remember this?") }) })
        }
        sessions = buildJsonArray { add(buildJsonObject { put("id", "s1"); put("state", "idle"); put("title", "Clean downloads") }) }
        val notices = planner.poll(false)
        assertEquals(listOf("new", "approval-k", "pc-s1"), notices.map { it.key })
        assertEquals("chat:c1", notices.single { it.key == "approval-k" }.open)
        assertEquals(emptyList(), planner.poll(false))
        // Unanswered PC permissions remind after two hours.
        now += NoticePlanner.REMIND_MS
        assertEquals(listOf("pcperm-p1"), planner.poll(false).map { it.key })
    }
}
