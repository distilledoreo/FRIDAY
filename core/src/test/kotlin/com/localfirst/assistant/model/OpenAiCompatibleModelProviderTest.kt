package com.localfirst.assistant.model

import com.localfirst.assistant.conversation.ConversationSession
import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.conversation.TurnOutcome
import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.tools.SetMediaVolumeTool
import com.localfirst.assistant.tools.ToolRegistry
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleModelProviderTest {
    @Test
    fun textChatThenVolumeToolRoundTrip() = runBlocking {
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val hits = AtomicInteger()
        val server = localServer()
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
            bodies += body
            val response = when (hits.incrementAndGet()) {
                1 -> """{"choices":[{"message":{"role":"assistant","content":"Hello"}}]}"""
                2 -> """
                    {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                      {"id":"call_1","type":"function","function":{"name":"set_media_volume","arguments":{"level":30}}}
                    ]}}]}
                """.trimIndent()
                else -> """{"choices":[{"message":{"role":"assistant","content":"Done. Volume is 30%."}}]}"""
            }
            write(exchange, 200, response)
        }
        server.start()
        try {
            val levels = mutableListOf<Int>()
            val session = ConversationSession(
                modelProvider = provider(server.address.port),
                toolRegistry = ToolRegistry().apply {
                    register(SetMediaVolumeTool { levels += it })
                },
                systemPrompt = "Be brief.",
            )

            val hello = session.submitUserMessage("Hello")
            assertTrue(hello is TurnOutcome.Completed)
            assertEquals(Message.Assistant("Hello"), session.snapshot().last())

            val volume = session.submitUserMessage("Set the volume to 30%.")
            assertTrue(volume is TurnOutcome.Completed)
            assertEquals(listOf(30), levels)
            assertEquals("Done. Volume is 30%.", (session.snapshot().last() as Message.Assistant).content)
            assertTrue(session.snapshot().any { it is Message.ToolResult && it.success })

            assertEquals(3, bodies.size)
            val toolRequest = JsonCodec.json.parseToJsonElement(bodies[2]).jsonObject
            assertEquals("test-model", toolRequest["model"]!!.jsonPrimitive.content)
            val messages = toolRequest["messages"]!!.jsonArray
            val roles = messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }
            assertEquals(
                listOf("system", "user", "assistant", "user", "assistant", "tool"),
                roles,
            )
            assertEquals(
                "Media volume set to 30%.",
                messages.last().jsonObject["content"]!!.jsonPrimitive.content,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun sendsOptionalApiKey() = runBlocking {
        var authorization: String? = null
        val server = localServer()
        server.createContext("/v1/chat/completions") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            write(exchange, 200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        server.start()
        try {
            val session = ConversationSession(
                modelProvider = provider(server.address.port, apiKey = "secret"),
                toolRegistry = ToolRegistry(),
                systemPrompt = "",
            )
            assertTrue(session.submitUserMessage("Hi") is TurnOutcome.Completed)
            assertEquals("Bearer secret", authorization)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun httpErrorAndUnreachableHostLeaveTheUserMessageInPlace() = runBlocking {
        val server = localServer()
        server.createContext("/v1/chat/completions") { exchange ->
            write(exchange, 500, """{"error":{"message":"overloaded"}}""")
        }
        server.start()
        try {
            val session = ConversationSession(
                modelProvider = provider(server.address.port),
                toolRegistry = ToolRegistry().apply { register(SetMediaVolumeTool { }) },
                systemPrompt = "Be brief.",
            )
            val failed = session.submitUserMessage("Hello") as TurnOutcome.Failed
            assertTrue(failed.error.contains("HTTP 500"))
            assertEquals(listOf(Message.User("Hello")), session.snapshot())
        } finally {
            server.stop(0)
        }

        val refused = ConversationSession(
            modelProvider = OpenAiCompatibleModelProvider(
                OpenAiCompatibleConfig(
                    baseUrl = "http://127.0.0.1:1/v1",
                    model = "test-model",
                    connectTimeoutMillis = 1_000,
                    readTimeoutMillis = 1_000,
                ),
            ),
            toolRegistry = ToolRegistry(),
            systemPrompt = "Be brief.",
        )
        val unreachable = refused.submitUserMessage("Hello") as TurnOutcome.Failed
        assertTrue(unreachable.error.contains("Can't reach"))
        assertEquals(listOf(Message.User("Hello")), refused.snapshot())
    }

    @Test
    fun malformedSuccessBodyDoesNotDropHistory() = runBlocking {
        val server = localServer()
        server.createContext("/v1/chat/completions") { exchange ->
            write(exchange, 200, "not-json")
        }
        server.start()
        try {
            val session = ConversationSession(
                modelProvider = provider(server.address.port),
                toolRegistry = ToolRegistry(),
                systemPrompt = "",
            )
            val failed = session.submitUserMessage("Hello") as TurnOutcome.Failed
            assertTrue(failed.error, failed.error.contains("did not return a JSON object"))
            assertEquals(listOf(Message.User("Hello")), session.snapshot())
        } finally {
            server.stop(0)
        }
    }

    private fun provider(port: Int, apiKey: String? = null): OpenAiCompatibleModelProvider {
        return OpenAiCompatibleModelProvider(
            OpenAiCompatibleConfig(
                baseUrl = "http://127.0.0.1:$port/v1",
                model = "test-model",
                apiKey = apiKey,
                connectTimeoutMillis = 2_000,
                readTimeoutMillis = 2_000,
            ),
        )
    }

    private fun localServer(): HttpServer {
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "test-http").apply { isDaemon = true }
            }
        }
    }

    private fun write(
        exchange: HttpExchange,
        code: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
