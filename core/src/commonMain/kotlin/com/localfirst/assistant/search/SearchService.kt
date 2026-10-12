package com.localfirst.assistant.search

/**
 * The phone's view of the PC search service. This is not a [com.localfirst.assistant.model.ModelProvider].
 * The model only sees the `web_search` tool; Android calls this service.
 */
interface SearchService {
    suspend fun search(request: WebSearchRequest): WebSearchResponse
}

data class SearchServiceConfig(
    val baseUrl: String,
    /** Sent as a bearer token when set. Independent of the model server's key. */
    val apiKey: String? = null,
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 60_000,
)

data class WebSearchRequest(
    val query: String,
    val limit: Int,
    val fetchPages: Boolean,
)

data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val score: Double? = null,
    val pageText: String? = null,
    val pageError: String? = null,
)

data class WebSearchResponse(
    val results: List<WebSearchHit>,
    val provider: String? = null,
    val fetched: Boolean = false,
)

class SearchServiceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
