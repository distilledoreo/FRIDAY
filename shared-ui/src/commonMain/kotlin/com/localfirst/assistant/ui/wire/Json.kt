package com.localfirst.assistant.ui.wire

import kotlinx.serialization.json.*

/** Portable mutable wire objects for the PC controls migrated from Android. Child objects are live views. */
class JSONObject private constructor(private val values: MutableMap<String, Any?>) {
    constructor() : this(mutableMapOf())
    constructor(text: String) : this(Json.parseToJsonElement(text).jsonObject.mapValues { fromJson(it.value) }.toMutableMap())
    fun put(key: String, value: Any?): JSONObject { values[key] = if (value is JsonElement) fromJson(value) else value; return this }
    fun has(key: String) = key in values
    fun optString(key: String, fallback: String = "") = values[key]?.toString() ?: fallback
    fun getString(key: String) = values.getValue(key)?.toString() ?: error("Missing $key")
    fun optInt(key: String, fallback: Int = 0) = (values[key] as? Number)?.toInt() ?: values[key]?.toString()?.toIntOrNull() ?: fallback
    fun optLong(key: String, fallback: Long = 0) = (values[key] as? Number)?.toLong() ?: values[key]?.toString()?.toLongOrNull() ?: fallback
    fun getLong(key: String) = (values.getValue(key) as? Number)?.toLong() ?: values.getValue(key).toString().toLong()
    fun optDouble(key: String, fallback: Double = Double.NaN) = (values[key] as? Number)?.toDouble() ?: values[key]?.toString()?.toDoubleOrNull() ?: fallback
    fun optBoolean(key: String, fallback: Boolean = false) = values[key] as? Boolean ?: values[key]?.toString()?.toBooleanStrictOrNull() ?: fallback
    fun optJSONObject(key: String) = values[key] as? JSONObject
    fun getJSONObject(key: String) = optJSONObject(key) ?: error("Missing $key")
    fun optJSONArray(key: String) = values[key] as? JSONArray
    fun getJSONArray(key: String) = optJSONArray(key) ?: error("Missing $key")
    fun opt(key: String): Any? = values[key]
    fun json() = JsonObject(values.mapValues { toJson(it.value) })
    override fun toString() = json().toString()
    fun toString(indent: Int) = Json { prettyPrint = indent > 0 }.encodeToString(JsonObject.serializer(), json())
}
class JSONArray private constructor(private val values: MutableList<Any?>) {
    constructor() : this(mutableListOf())
    constructor(text: String) : this(Json.parseToJsonElement(text).jsonArray.map(::fromJson).toMutableList())
    constructor(items: Collection<*>) : this(items.map { if (it is JsonElement) fromJson(it) else it }.toMutableList())
    fun put(value: Any?): JSONArray { values.add(if (value is JsonElement) fromJson(value) else value); return this }
    fun length() = values.size
    fun optString(index: Int) = values.getOrNull(index)?.toString().orEmpty()
    fun getString(index: Int) = values[index]?.toString() ?: error("Missing $index")
    fun optJSONObject(index: Int) = values.getOrNull(index) as? JSONObject
    fun getJSONObject(index: Int) = optJSONObject(index) ?: error("Missing object $index")
    fun json() = JsonArray(values.map(::toJson))
    override fun toString() = json().toString()
}
private fun fromJson(value: JsonElement): Any? = when(value) {
    JsonNull -> null
    is JsonObject -> JSONObject(value.toString())
    is JsonArray -> JSONArray(value.toString())
    is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
}
private fun toJson(value: Any?): JsonElement = when(value) {
    null -> JsonNull
    is JsonElement -> value
    is JSONObject -> value.json()
    is JSONArray -> value.json()
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Collection<*> -> JsonArray(value.map(::toJson))
    else -> JsonPrimitive(value.toString())
}
