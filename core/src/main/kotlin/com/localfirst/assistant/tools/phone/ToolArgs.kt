package com.localfirst.assistant.tools.phone

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Builds a JSON-schema `object` for tool parameters. */
internal fun objectSchema(build: SchemaBuilder.() -> Unit): JsonObject {
    val builder = SchemaBuilder().apply(build)
    return buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") { builder.properties.forEach { (name, schema) -> put(name, schema) } }
        if (builder.required.isNotEmpty()) {
            putJsonArray("required") { builder.required.forEach { add(JsonPrimitive(it)) } }
        }
    }
}

internal class SchemaBuilder {
    val properties = linkedMapOf<String, JsonObject>()
    val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        add(name, required) {
            put("type", "string")
            put("description", description)
            enum?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
        }

    fun integer(name: String, description: String, min: Int? = null, max: Int? = null, required: Boolean = false) =
        add(name, required) {
            put("type", "integer")
            put("description", description)
            min?.let { put("minimum", it) }
            max?.let { put("maximum", it) }
        }

    fun boolean(name: String, description: String, required: Boolean = false) =
        add(name, required) {
            put("type", "boolean")
            put("description", description)
        }

    fun stringArray(name: String, description: String, enum: List<String>) =
        add(name, required = false) {
            put("type", "array")
            put("description", description)
            putJsonObject("items") {
                put("type", "string")
                putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
            }
        }

    private fun add(name: String, required: Boolean, body: JsonObjectBuilder.() -> Unit) {
        properties[name] = buildJsonObject(body)
        if (required) this.required += name
    }
}

/** Argument readers. They throw [IllegalArgumentException] with a message meant for the model. */
internal object Args {
    fun string(args: JsonObject, name: String, maxLength: Int = 500): String =
        optionalString(args, name, maxLength) ?: throw IllegalArgumentException("Missing required argument '$name'.")

    fun optionalString(args: JsonObject, name: String, maxLength: Int = 500): String? {
        val element = args[name] ?: return null
        if (element is JsonNull) return null
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: throw IllegalArgumentException("'$name' must be a string.")
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > maxLength) throw IllegalArgumentException("'$name' must be at most $maxLength characters.")
        return trimmed
    }

    fun int(args: JsonObject, name: String, range: IntRange): Int =
        optionalInt(args, name, range) ?: throw IllegalArgumentException("Missing required argument '$name'.")

    fun optionalInt(args: JsonObject, name: String, range: IntRange): Int? {
        val element = args[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("'$name' must be an integer from ${range.first} to ${range.last}.")
        val number = primitive.doubleOrNull ?: primitive.content.trim().toDoubleOrNull()
        if (number == null || number % 1.0 != 0.0 || number < range.first || number > range.last) {
            throw IllegalArgumentException("'$name' must be an integer from ${range.first} to ${range.last}.")
        }
        return number.toInt()
    }

    fun boolean(args: JsonObject, name: String): Boolean =
        optionalBoolean(args, name) ?: throw IllegalArgumentException("Missing required argument '$name'.")

    fun optionalBoolean(args: JsonObject, name: String): Boolean? {
        val element = args[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw IllegalArgumentException("'$name' must be true or false.")
        return primitive.booleanOrNull
            ?: when (primitive.content.trim().lowercase()) {
                "true", "on", "yes" -> true
                "false", "off", "no" -> false
                else -> throw IllegalArgumentException("'$name' must be true or false.")
            }
    }

    fun <T> choice(args: JsonObject, name: String, options: Map<String, T>, default: T): T {
        val raw = optionalString(args, name) ?: return default
        return options[raw.lowercase()]
            ?: throw IllegalArgumentException("'$name' must be one of: ${options.keys.joinToString()}.")
    }

    fun stringList(args: JsonObject, name: String): List<String> {
        val element: JsonElement = args[name] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        val array = element as? JsonArray ?: throw IllegalArgumentException("'$name' must be a list of strings.")
        return array.map {
            (it as? JsonPrimitive)?.contentOrNull?.trim() ?: throw IllegalArgumentException("'$name' must be a list of strings.")
        }
    }
}
