package com.localfirst.assistant.grounding

import com.localfirst.assistant.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GroundingPrecheckTest {
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
}
