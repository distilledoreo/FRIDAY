package com.localfirst.assistant.grounding

import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.platform.PlatformUri
import com.localfirst.assistant.tools.SourceLink
import com.localfirst.assistant.tools.ToolExecutionResult
import kotlinx.serialization.json.*

/** Reads the PC's grounded search and page replies (`/grounding`, `/grounding/page`) the same way on every client. */
object GroundedEvidence {
    fun searchRequest(query: String, limit: Int = 5, fetchPages: Boolean = true) = buildJsonObject {
        put("query", query.take(500)); put("limit", limit); put("fetch_pages", fetchPages)
    }

    /** A search reply: successful when it found evidence; each public source becomes a link the chat can show. */
    fun search(text: String): ToolExecutionResult {
        val evidence = (JsonCodec.json.parseToJsonElement(text).jsonObject["evidence"] as? JsonArray).orEmpty()
        val sources = evidence.mapNotNull { item ->
            val value = item as? JsonObject ?: return@mapNotNull null
            val url = (value["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (!safePublicUrl(url)) null else SourceLink((value["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { url }, url,
                contentRead = (value["status"] as? JsonPrimitive)?.contentOrNull == "retrieved" && (value["passages"] as? JsonArray)?.isNotEmpty() == true)
        }
        // Each result carries a ref id the interface language can bind and cite.
        return ToolExecutionResult(evidence.isNotEmpty(), com.localfirst.assistant.genui.UiBindings.withRefs(text), sources)
    }

    fun page(text: String): ToolExecutionResult {
        val result = JsonCodec.json.parseToJsonElement(text).jsonObject
        val finalUrl = result.getValue("url").jsonPrimitive.content
        require(safePublicUrl(finalUrl))
        return ToolExecutionResult(true, com.localfirst.assistant.genui.UiBindings.withRefs(text), listOf(SourceLink((result["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { finalUrl }, finalUrl,
            contentRead = (result["passages"] as? JsonArray)?.isNotEmpty() == true)))
    }

    fun safePublicUrl(url: String): Boolean = runCatching {
        val value = PlatformUri(url)
        url.length <= 4096 && value.scheme == "https" && value.host != null && value.rawUserInfo == null && value.port in listOf(-1, 443) && '\\' !in url
    }.getOrDefault(false)
}
