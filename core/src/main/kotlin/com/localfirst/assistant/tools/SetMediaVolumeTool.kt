package com.localfirst.assistant.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Asks the host to set media volume. The platform effect is injected so this
 * class can live with the conversation engine and be tested without Android.
 *
 * [level] is a percentage from 0 (mute) to 100 (maximum), not a raw stream index.
 */
class SetMediaVolumeTool(
    private val setLevelPercent: suspend (Int) -> Unit,
) : Tool {
    override val name: String = NAME
    override val description: String = DESCRIPTION
    override val inputSchema: JsonObject = SCHEMA

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val level = try {
            MediaVolumeScale.parsePercent(arguments)
        } catch (e: IllegalArgumentException) {
            return ToolExecutionResult(
                success = false,
                content = e.message ?: "Invalid volume level.",
            )
        }
        return try {
            setLevelPercent(level)
            ToolExecutionResult(
                success = true,
                content = "Media volume set to $level%.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                content = "Failed to set media volume: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    companion object {
        const val NAME = "set_media_volume"
        const val DESCRIPTION =
            "Set the phone's media (music) volume. level is an integer percentage from 0 (mute) to 100 (maximum)."

        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("level") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("maximum", 100)
                    put(
                        "description",
                        "Media volume percentage from 0 (mute) to 100 (maximum).",
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("level"))
            }
        }
    }
}
