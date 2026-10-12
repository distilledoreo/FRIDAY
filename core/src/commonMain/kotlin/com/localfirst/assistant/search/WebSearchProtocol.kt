package com.localfirst.assistant.search

import com.localfirst.assistant.json.JsonCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

internal fun resolveSearchUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) {
        throw SearchServiceException("The search service address is empty.")
    }
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        throw SearchServiceException("The search service address must start with http:// or https://.")
    }
    return if (trimmed.endsWith("/search")) trimmed else "$trimmed/search"
}

internal fun buildSearchRequestBody(request: WebSearchRequest): JsonObject = buildJsonObject {
    put("query", request.query)
    put("limit", request.limit)
    put("fetch_pages", request.fetchPages)
}

internal fun parseSearchResponse(body: String): WebSearchResponse {
    val root = try {
        JsonCodec.json.parseToJsonElement(body)
    } catch (e: Exception) {
        throw SearchServiceException("The search service did not return JSON.")
    }
    if (root !is JsonObject) {
        throw SearchServiceException("The search service did not return a JSON object.")
    }
    val error = primitiveContent(root["error"])?.trim()?.ifEmpty { null }
    val resultsElement = root["results"]
        ?: throw SearchServiceException(error ?: "The search service response did not include results.")
    val results = resultsElement as? JsonArray
        ?: throw SearchServiceException("The search service results were not a list.")
    return WebSearchResponse(
        provider = primitiveContent(root["provider"])?.trim()?.ifEmpty { null },
        fetched = (root["fetched"] as? JsonPrimitive)?.booleanOrNull ?: false,
        results = results.map { parseHit(it) },
    )
}

internal fun searchErrorSnippet(text: String): String {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    if (clean.isEmpty()) return ""
    return if (clean.length <= 300) clean else clean.take(300) + "…"
}

private fun parseHit(element: JsonElement): WebSearchHit {
    val obj = element as? JsonObject
        ?: throw SearchServiceException("A search result was not an object.")
    val url = primitiveContent(obj["url"])?.trim().orEmpty()
    if (url.isEmpty()) {
        throw SearchServiceException("A search result was missing its url.")
    }
    return WebSearchHit(
        title = primitiveContent(obj["title"])?.trim().orEmpty(),
        url = url,
        snippet = primitiveContent(obj["snippet"])?.trim().orEmpty(),
        score = (obj["score"] as? JsonPrimitive)?.doubleOrNull,
        pageText = primitiveContent(obj["page_text"]),
        pageError = primitiveContent(obj["page_error"]),
    )
}

private fun primitiveContent(element: JsonElement?): String? {
    if (element == null || element is JsonNull) return null
    return (element as? JsonPrimitive)?.contentOrNull
}
