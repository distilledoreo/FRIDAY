package com.localfirst.assistant.grounding

import com.localfirst.assistant.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GroundingPrecheckTest {
    @Test fun composerSearchIntentTriggersRetrievalWithoutBypassingOptOutOrPrivateReads() {
        assertTrue(GroundingPrecheck.needsSearch("Search the web for: how rainbows form"))
        assertFalse(GroundingPrecheck.needsSearch("Search the web for: "))
        assertFalse(GroundingPrecheck.needsSearch("Search the web for: my inbox"))
        assertFalse(GroundingPrecheck.needsSearch("Search the web for: without search explain rainbows"))
    }

    @Test fun boundedEnvelopeKeepsAllPassagesAndLabelsOversizeFallback() {
        val bounded = "x".repeat(25000) + "final supporting passage"
        assertTrue(GroundingPrecheck.note(ToolExecutionResult(true, bounded)).endsWith("final supporting passage"))
        val oversized = GroundingPrecheck.note(ToolExecutionResult(true, "x".repeat(31000)))
        assertTrue(oversized.contains("Evidence context was shortened"))
        assertTrue(oversized.length < 32000)
    }
    @Test fun flagsChangingQuestionsAndLeavesStableQuestionsAlone() {
        listOf("Who is the CEO of OpenAI?", "What is the weather tomorrow?", "Recommend a laptop", "What changed in 2026?", "What is the latest Android version?").forEach { assertTrue(it, GroundingPrecheck.needsSearch(it)) }
        listOf("How does human memory work?", "What is 2 plus 2?", "hello", "Explain recursion", "Translate this news article into French").forEach { assertFalse(it, GroundingPrecheck.needsSearch(it)) }
        assertFalse(GroundingPrecheck.needsSearch("Don't search: explain how weather forecasting works"))
    }
    @Test fun searchesBeforeReturningGroundingContext() = runBlocking {
        var query: String? = null
        val tool = object : Tool {
            override val name = "web_search"
            override val description = "test"
            override val inputSchema = buildJsonObject { }
            override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
                query = arguments.getValue("query").jsonPrimitive.content
                return ToolExecutionResult(true, "Source https://example.org/current", sources = listOf(SourceLink("Source", "https://example.org/current")))
            }
        }
        val result = GroundingPrecheck.run("What's the weather today?", tool)!!
        assertEquals("What's the weather today?", query)
        assertTrue(GroundingPrecheck.note(result).contains("untrusted"))
        assertTrue(GroundingPrecheck.note(result).contains("https://example.org/current"))
        query = null; assertNull(GroundingPrecheck.run("Explain recursion", tool)); assertNull(query)
    }
    @Test fun failureDoesNotClaimVerificationOrInventLinks() {
        val note = GroundingPrecheck.note(ToolExecutionResult(false, "failed"))
        assertTrue(note.contains("could not be verified")); assertFalse(note.contains("https://"))
    }
    @Test fun privateQuestionsAndExplicitOfflineRequestsNeverAutoSearch() = runBlocking {
        val tool = object : Tool {
            override val name = "web_search";override val description = "test";override val inputSchema = buildJsonObject { }
            override suspend fun execute(arguments: JsonObject): ToolExecutionResult { fail("Private/offline query sent to search");return ToolExecutionResult(false, "") }
        }
        for (query in listOf("What's in my inbox today?", "Check my calendar for tomorrow", "Research my emails", "What did John say in that email today?", "Don't browse: latest events", "Offline only: CEO today")) assertNull(GroundingPrecheck.run(query,tool))
        assertTrue(GroundingPrecheck.needsSearch("Read this source https://example.org/report"))
        assertTrue(GroundingPrecheck.largerResearch("Research current battery evidence"))
    }
    @Test fun citationCoverageDistinguishesReadPagesFromSnippetsAndNeverProvesTruth() {
        val sources = listOf(SourceLink("Read", "https://example.org/source", true), SourceLink("Snippet", "https://example.org/snippet"))
        val check = GroundingPrecheck.audit("Supported [claim](https://EXAMPLE.org/source#section). [Unlisted](https://example.org/snippet)", sources)
        assertEquals(1,check.readSources);assertEquals(1,check.matchedLinks);assertEquals(listOf("https://example.org/snippet"),check.unlistedLinks)
        val supported = GroundingPrecheck.audit("[Source](https://example.org/source)",sources)
        assertTrue(supported.status.contains("still needs checking"))
        assertTrue(GroundingPrecheck.mergeSources(listOf(SourceLink("Snippet", "https://example.org/source"), sources[0])).single().contentRead)
        assertTrue(GroundingPrecheck.audit("No citations.",sources).status.contains("no matching"))
        assertEquals(1,GroundingPrecheck.audit("[Source](https://example.org/topic(test))",listOf(SourceLink("Title","https://example.org/topic(test)",true))).matchedLinks)
    }
}
