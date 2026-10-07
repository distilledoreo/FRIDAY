package com.localfirst.assistant.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaVolumeToolTest {
    @Test
    fun parsesPercentAndMapsOntoStreamSteps() {
        assertEquals(0, MediaVolumeScale.parsePercent(level(0)))
        assertEquals(30, MediaVolumeScale.parsePercent(level(30)))
        assertEquals(30, MediaVolumeScale.parsePercent(level("30")))
        assertEquals(100, MediaVolumeScale.parsePercent(level(99.6)))

        assertEquals(0, MediaVolumeScale.toStreamIndex(0, 15))
        assertEquals(5, MediaVolumeScale.toStreamIndex(30, 15))
        assertEquals(8, MediaVolumeScale.toStreamIndex(50, 15))
        assertEquals(15, MediaVolumeScale.toStreamIndex(100, 15))

        val missing = runCatching { MediaVolumeScale.parsePercent(buildJsonObject { }) }
        assertTrue(missing.exceptionOrNull() is IllegalArgumentException)
        val high = runCatching { MediaVolumeScale.parsePercent(level(140)) }
        assertTrue(high.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun schemaRequiresLevelFrom0To100() {
        val level = SetMediaVolumeTool.SCHEMA["properties"]!!.jsonObject["level"]!!.jsonObject
        assertEquals("integer", level["type"]!!.jsonPrimitive.content)
        assertEquals(0, level["minimum"]!!.jsonPrimitive.int)
        assertEquals(100, level["maximum"]!!.jsonPrimitive.int)
        assertEquals(
            "level",
            SetMediaVolumeTool.SCHEMA["required"]!!.jsonObjectOrArrayFirst(),
        )
    }

    @Test
    fun executeReportsSuccessAndDeviceFailures() = runBlocking {
        val applied = mutableListOf<Int>()
        val tool = SetMediaVolumeTool { applied += it }
        val registry = ToolRegistry().apply { register(tool) }

        val success = registry.execute(ToolCall("1", "set_media_volume", """{"level":30}"""))
        assertTrue(success.success)
        assertEquals("Media volume set to 30%.", success.content)
        assertEquals(listOf(30), applied)

        val denied = SetMediaVolumeTool {
            throw IllegalStateException("Android denied the media volume change.")
        }
        val failure = denied.execute(level(10))
        assertFalse(failure.success)
        assertTrue(failure.content.contains("Android denied"))

        val bad = registry.execute(ToolCall("2", "set_media_volume", """{"level":"loud"}"""))
        assertFalse(bad.success)
        assertEquals(listOf(30), applied)
    }

    private fun level(value: Int) = buildJsonObject { put("level", value) }

    private fun level(value: Double) = buildJsonObject { put("level", value) }

    private fun level(value: String) = buildJsonObject { put("level", value) }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrArrayFirst(): String {
        val array = this as kotlinx.serialization.json.JsonArray
        return (array.first() as JsonPrimitive).content
    }
}
