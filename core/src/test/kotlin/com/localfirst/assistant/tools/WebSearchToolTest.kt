package com.localfirst.assistant.tools

import com.localfirst.assistant.search.SearchService
import com.localfirst.assistant.search.SearchServiceException
import com.localfirst.assistant.search.WebSearchHit
import com.localfirst.assistant.search.WebSearchRequest
import com.localfirst.assistant.search.WebSearchResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchToolTest {
    @Test
    fun sendsQueryWithoutFetchingPagesUnlessAsked() = runBlocking {
        val search = FakeSearchService(
            WebSearchResponse(
                provider = "searxng",
                results = listOf(
                    WebSearchHit(
                        title = "Example",
                        url = "https://example.com/a",
                        snippet = "A snippet",
                        score = 1.5,
                    ),
                ),
            ),
        )
        val tool = WebSearchTool(search)
        val registry = ToolRegistry().apply { register(tool) }

        val result = registry.execute(
            ToolCall("1", "web_search", """{"query":"local models"}"""),
        )

        assertTrue(result.success)
        assertEquals(
            listOf(WebSearchRequest(query = "local models", limit = 5, fetchPages = false)),
            search.requests,
        )
        assertTrue(result.content.contains("https://example.com/a"))
        assertTrue(result.content.contains("searxng"))
        assertTrue(result.content.contains("A snippet"))
        assertFalse(result.content.contains("Page text:"))
    }

    @Test
    fun fetchPagesIsOptInAndFailuresStayInTheToolResult() = runBlocking {
        val search = FakeSearchService(
            WebSearchResponse(
                provider = "searxng",
                fetched = true,
                results = listOf(
                    WebSearchHit(
                        title = "Docs",
                        url = "https://example.com/docs",
                        snippet = "Overview",
                        pageText = "Extracted paragraph",
                    ),
                ),
            ),
        )
        val registry = ToolRegistry().apply { register(WebSearchTool(search)) }
        val result = registry.execute(
            ToolCall("1", "web_search", """{"query":"docs","limit":3,"fetch_pages":true}"""),
        )

        assertTrue(result.success)
        assertEquals(true, search.requests.single().fetchPages)
        assertEquals(3, search.requests.single().limit)
        assertTrue(result.content.contains("Page text: Extracted paragraph"))

        search.error = SearchServiceException("Can't reach the search service.")
        val failed = registry.execute(
            ToolCall("2", "web_search", """{"query":"again"}"""),
        )
        assertFalse(failed.success)
        assertEquals("Can't reach the search service.", failed.content)
    }

    @Test
    fun rejectsBlankQueryAndDoesNotCallTheService() = runBlocking {
        val search = FakeSearchService(WebSearchResponse(emptyList()))
        val registry = ToolRegistry().apply { register(WebSearchTool(search)) }
        val result = registry.execute(ToolCall("1", "web_search", """{"query":"  "}"""))
        assertFalse(result.success)
        assertTrue(result.content.contains("blank"))
        assertTrue(search.requests.isEmpty())
    }

    @Test
    fun emptyResultsAreASuccessfulToolResult() = runBlocking {
        val search = FakeSearchService(WebSearchResponse(provider = "searxng", results = emptyList()))
        val result = WebSearchTool(search).execute(buildJsonObject { put("query", "nothing") })
        assertTrue(result.success)
        assertEquals("No web results for \"nothing\".", result.content)
    }

    private class FakeSearchService(
        private val response: WebSearchResponse,
    ) : SearchService {
        val requests = mutableListOf<WebSearchRequest>()
        var error: SearchServiceException? = null

        override suspend fun search(request: WebSearchRequest): WebSearchResponse {
            requests += request
            error?.let { throw it }
            return response
        }
    }
}
