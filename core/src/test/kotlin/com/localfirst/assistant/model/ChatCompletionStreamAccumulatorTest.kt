package com.localfirst.assistant.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionStreamAccumulatorTest {
    @Test
    fun rebuildsTextFromARealLlamaCppStream() {
        val accumulator = ChatCompletionStreamAccumulator()
        val deltas = fixture("llama-text.txt").mapNotNull(accumulator::acceptLine)

        assertEquals(listOf("Hello", " there", ",", " my", " friend", "."), deltas)
        assertTrue(accumulator.done)
        assertEquals(ModelResponse.TextResponse("Hello there, my friend."), accumulator.result())
    }

    @Test
    fun joinsToolCallFragmentsByIndexFromARealLlamaCppStream() {
        val accumulator = ChatCompletionStreamAccumulator()
        fixture("llama-tool-calls.txt").forEach { accumulator.acceptLine(it) }

        val response = accumulator.result() as ModelResponse.ToolCallResponse
        assertEquals("I'll run those two tasks for you now.", response.text)
        assertEquals(listOf("web_search", "set_media_volume"), response.calls.map { it.name })
        assertEquals("""{"query":"SearXNG"}""", response.calls[0].argumentsJson)
        assertEquals("""{"level":30}""", response.calls[1].argumentsJson)
        assertEquals("xmjlj3MJVbZ1KprX3sDLkuMyJNeMhZyh", response.calls[0].id)
    }

    @Test
    fun keepsTextThatPrecedesToolCallsAndFillsMissingIds() {
        val accumulator = ChatCompletionStreamAccumulator()
        listOf(
            """data: {"choices":[{"delta":{"content":"Let me check."}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"web_","arguments":""}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"search","arguments":"{}"}}]}}]}""",
            "data: [DONE]",
        ).forEach { accumulator.acceptLine(it) }

        val response = accumulator.result() as ModelResponse.ToolCallResponse
        assertEquals("Let me check.", response.text)
        assertEquals("web_search", response.calls.single().name)
        assertEquals("call_0", response.calls.single().id)
    }

    @Test
    fun ignoresCommentsAndBlankLinesAndSurfacesStreamErrors() {
        val accumulator = ChatCompletionStreamAccumulator()
        assertEquals(null, accumulator.acceptLine(": keep-alive"))
        assertEquals(null, accumulator.acceptLine(""))
        assertEquals(null, accumulator.acceptLine("event: message"))

        val error = runCatching {
            accumulator.acceptLine("""data: {"error":{"message":"context length exceeded"}}""")
        }.exceptionOrNull()
        assertTrue(error is ModelProviderException)
        assertEquals("context length exceeded", error!!.message)

        val bad = runCatching { accumulator.acceptLine("data: {not json") }.exceptionOrNull()
        assertTrue(bad is ModelProviderException)
    }

    private fun fixture(name: String): List<String> =
        javaClass.getResource("/sse/$name")!!.readText().lines()
}
