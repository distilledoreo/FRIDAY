package com.localfirst.assistant.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GroundedWebToolsTest {
    @Test fun completeProvenanceAndActualReadStateReachTheModelAndUi() = runBlocking {
        var path = ""; var body = ""
        val gateway = object : WorkspaceGateway {
            override suspend fun toolRequest(p: String, method: String, b: String?): String {
                path = p; body = b.orEmpty()
                return """{"truth_verified":false,"usable_pages":1,"evidence":[{"url":"https://example.org/source","title":"Source","status":"retrieved","passages":[{"text":"Exact supported passage."}]},{"url":"https://example.org/snippet","title":"Snippet","status":"snippet_only"}]}"""
            }
        }
        val result = GroundedWebSearchTool(gateway).execute(buildJsonObject { put("query", "synthetic"); put("fetch_pages", true) })
        assertEquals("/grounding", path);assertTrue(Json.parseToJsonElement(body).jsonObject["fetch_pages"]!!.jsonPrimitive.boolean)
        assertTrue(result.success);assertTrue(result.sources[0].contentRead);assertFalse(result.sources[1].contentRead)
        assertTrue(result.content.contains("Exact supported passage"));assertTrue(result.content.contains("truth_verified"))
    }
    @Test fun pageReadsUsePinnedRouteWithExactQueryAndRejectUnsafeUrlShapes() = runBlocking {
        var path = ""; var body = ""
        val gateway = object : WorkspaceGateway {
            override suspend fun toolRequest(p: String, method: String, b: String?): String { path = p;body = b.orEmpty();return """{"url":"https://example.org/source","title":"Source","passages":[{"text":"Synthetic"}]}""" }
        }
        val tool = PublicPageTool(gateway)
        val args = buildJsonObject { put("url", "https://example.org/source");put("query", "specific topic") }
        val result = tool.execute(args);assertEquals("/grounding/page",path);assertEquals(args,Json.parseToJsonElement(body));assertTrue(result.sources.single().contentRead)
        for (url in listOf("http://example.org", "https://user:secret@example.org", "https://example.org:8700")) {
            try { tool.execute(buildJsonObject { put("url",url) });fail("Unsafe URL accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
