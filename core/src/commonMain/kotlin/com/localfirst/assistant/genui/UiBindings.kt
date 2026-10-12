package com.localfirst.assistant.genui

import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.platform.PlatformMessageDigest
import com.localfirst.assistant.platform.PlatformUri
import com.localfirst.assistant.platform.encodeUtf8
import com.localfirst.assistant.platform.formatString
import com.localfirst.assistant.platform.putIfAbsent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Tool results the interface may reference by id, as ChatGPT binds `turn…product0` fields: the model
 * writes `{web_3f9a2c.title}` or `<Cite ref="web_3f9a2c"/>` and the value shown comes from the
 * search result itself, not the model's copy of it.
 */
object UiBindings {
    /** A short id that is the same for a URL in every conversation. */
    fun ref(url: String): String {
        val digest = PlatformMessageDigest.sha1(encodeUtf8(url))
        return "web_" + digest.take(3).joinToString("") { formatString("%02x", it) }
    }

    /** Adds a `ref` to each search evidence item (and to a fetched page), so the model can cite it. */
    fun withRefs(json: String): String = runCatching {
        val root = JsonCodec.json.parseToJsonElement(json) as? JsonObject ?: return json
        fun tag(item: JsonElement): JsonElement {
            val obj = item as? JsonObject ?: return item
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull ?: return item
            return JsonObject(linkedMapOf<String, JsonElement>("ref" to JsonPrimitive(ref(url))) + obj)
        }
        val updated = root.toMutableMap()
        (root["evidence"] as? JsonArray)?.let { updated["evidence"] = JsonArray(it.map(::tag)) }
        (root["results"] as? JsonArray)?.let { updated["results"] = JsonArray(it.map(::tag)) }
        if (root["url"] is JsonPrimitive && root["evidence"] == null && root["results"] == null) return tag(root).toString()
        JsonObject(updated).toString()
    }.getOrDefault(json)

    /** Every referenced result in [messages], by ref, as plain data for the runtime. */
    fun from(messages: List<Message>): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for (result in messages.filterIsInstance<Message.ToolResult>()) {
            if (!result.success || result.name !in setOf("web_search", "fetch_page")) continue
            val root = runCatching { JsonCodec.json.parseToJsonElement(result.content) }.getOrNull() as? JsonObject ?: continue
            val items = (root["evidence"] as? JsonArray ?: root["results"] as? JsonArray)?.toList() ?: listOf(root)
            for (item in items) {
                val obj = item as? JsonObject ?: continue
                val url = (obj["url"] as? JsonPrimitive)?.contentOrNull ?: continue
                val data = plain(obj) as Map<*, *>
                val domain = runCatching { PlatformUri(url).host?.removePrefix("www.") }.getOrNull()
                out[ref(url)] = linkedMapOf<String, Any?>().apply {
                    data.forEach { (k, v) -> put(k.toString(), v) }
                    put("ref", ref(url))
                    putIfAbsent("domain", domain)
                    putIfAbsent("snippet", ((obj["passages"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull)
                }
            }
        }
        return out
    }

    private fun plain(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else e.content.toBooleanStrictOrNull() ?: e.content.toDoubleOrNull()
        is JsonArray -> e.map(::plain)
        is JsonObject -> e.mapValues { plain(it.value) }
    }
}
