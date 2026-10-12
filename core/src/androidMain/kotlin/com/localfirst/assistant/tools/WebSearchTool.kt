package com.localfirst.assistant.tools

import com.localfirst.assistant.search.SearchService
import com.localfirst.assistant.search.SearchServiceException
import com.localfirst.assistant.search.WebSearchHit
import com.localfirst.assistant.search.WebSearchRequest
import com.localfirst.assistant.search.WebSearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Phone-owned web search. The model requests this tool; Android calls the PC
 * search service. The service, not the model, talks to SearXNG or a page fetcher.
 */
class WebSearchTool(
    private val search: SearchService,
) : Tool {
    override val name: String = NAME
    override val description: String = DESCRIPTION
    override val inputSchema: JsonObject = SCHEMA

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val request = try {
            WebSearchArguments.parse(arguments)
        } catch (e: IllegalArgumentException) {
            return ToolExecutionResult(
                success = false,
                content = e.message ?: "Invalid search arguments.",
            )
        }
        return try {
            val response = search.search(request)
            ToolExecutionResult(
                success = true,
                content = formatWebSearchResult(request.query, response),
                sources = response.results.map { SourceLink(title = it.title.ifBlank { it.url }, url = it.url, contentRead = !it.pageText.isNullOrBlank()) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SearchServiceException) {
            ToolExecutionResult(
                success = false,
                content = e.message ?: "Web search failed.",
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                content = "Web search failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    companion object {
        const val NAME = "web_search"
        const val DESCRIPTION =
            "Search the web using the user's computer. Returns titles, URLs, and snippets. " +
                "fetch_pages defaults to false. Set it to true only when extracted page text is needed for a citation. " +
                "This does not browse from the model itself."

        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("maxLength", WebSearchArguments.MAX_QUERY_CHARS)
                    put("description", "The search query.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("minimum", WebSearchArguments.MIN_LIMIT)
                    put("maximum", WebSearchArguments.MAX_LIMIT)
                    put("description", "Maximum results to return, from 1 to 10. Defaults to 5.")
                }
                putJsonObject("fetch_pages") {
                    put("type", "boolean")
                    put(
                        "description",
                        "When true, the computer may extract page text for the top results. " +
                            "Leave false to use snippets only.",
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("query"))
            }
        }
    }
}

internal object WebSearchArguments {
    const val DEFAULT_LIMIT = 5
    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 10
    const val MAX_QUERY_CHARS = 500

    fun parse(arguments: JsonObject): WebSearchRequest {
        val rawQuery = arguments["query"]
            ?: throw IllegalArgumentException("Missing required argument 'query'.")
        val query = (rawQuery as? JsonPrimitive)?.contentOrNull?.trim()
            ?: throw IllegalArgumentException("'query' must be a string.")
        if (query.isEmpty()) {
            throw IllegalArgumentException("'query' must not be blank.")
        }
        if (query.length > MAX_QUERY_CHARS) {
            throw IllegalArgumentException("'query' must be at most $MAX_QUERY_CHARS characters.")
        }
        return WebSearchRequest(
            query = query,
            limit = parseLimit(arguments["limit"]),
            fetchPages = parseFetchPages(arguments["fetch_pages"]),
        )
    }

    private fun parseLimit(element: JsonElement?): Int {
        if (element == null || element is JsonNull) return DEFAULT_LIMIT
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("'limit' must be an integer from $MIN_LIMIT to $MAX_LIMIT.")
        val number = primitive.doubleOrNull
            ?: primitive.content.trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("'limit' must be an integer from $MIN_LIMIT to $MAX_LIMIT.")
        if (number.isNaN() || number < MIN_LIMIT || number > MAX_LIMIT || number % 1.0 != 0.0) {
            throw IllegalArgumentException("'limit' must be an integer from $MIN_LIMIT to $MAX_LIMIT.")
        }
        return number.toInt()
    }

    private fun parseFetchPages(element: JsonElement?): Boolean {
        if (element == null || element is JsonNull) return false
        val primitive = element as? JsonPrimitive ?: throw IllegalArgumentException("'fetch_pages' must be a boolean.")
        return primitive.booleanOrNull
            ?: throw IllegalArgumentException("'fetch_pages' must be a boolean.")
    }
}

internal fun formatWebSearchResult(query: String, response: WebSearchResponse): String {
    if (response.results.isEmpty()) {
        return "No web results for \"$query\"."
    }
    val provider = response.provider?.takeIf { it.isNotBlank() } ?: "search"
    return buildString {
        append("Web results from ")
        append(provider)
        append(" for \"")
        append(query)
        append("\":")
        response.results.forEachIndexed { index, hit ->
            appendHit(index, hit)
        }
    }
}

private fun StringBuilder.appendHit(index: Int, hit: WebSearchHit) {
    append("\n")
    append(index + 1)
    append(". ")
    append(hit.title.ifBlank { hit.url })
    append("\n")
    append(hit.url)
    if (hit.snippet.isNotBlank()) {
        append("\n")
        append(hit.snippet.trim())
    }
    if (hit.score != null) {
        append("\nScore: ")
        append(hit.score)
    }
    val page = hit.pageText?.trim().orEmpty()
    if (page.isNotEmpty()) {
        append("\nPage text: ")
        append(page.take(MAX_PAGE_TEXT_IN_TOOL))
    }
    val error = hit.pageError?.trim().orEmpty()
    if (error.isNotEmpty()) {
        append("\nPage fetch failed: ")
        append(error)
    }
}

private const val MAX_PAGE_TEXT_IN_TOOL = 2_000
