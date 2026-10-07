package com.localfirst.assistant.model

import com.localfirst.assistant.conversation.Message
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiStreamingProviderTest {
    @Test
    fun streamsDeltasAndAsksForAnEventStream() = runBlocking {
        val body = AtomicReference("")
        val accept = AtomicReference("")
        val server = localServer { exchange ->
            body.set(exchange.requestBody.bufferedReader().readText())
            accept.set(exchange.requestHeaders.getFirst("Accept"))
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { out ->
                listOf("Hel", "lo", "!").forEach { piece ->
                    out.write("""data: {"choices":[{"delta":{"content":"$piece"}}]}""" + "\n\n")
                    out.flush()
                }
                out.write("data: [DONE]\n\n")
            }
        }
        try {
            val deltas = Collections.synchronizedList(mutableListOf<String>())
            val response = provider(server).streamConversation(listOf(Message.User("Hi")), emptyList()) {
                deltas += it
            }
            assertEquals(listOf("Hel", "lo", "!"), deltas)
            assertEquals(ModelResponse.TextResponse("Hello!"), response)
            assertTrue(body.get().contains("\"stream\":true"))
            assertEquals("text/event-stream", accept.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fallsBackWhenTheServerAnswersWithPlainJson() = runBlocking {
        val server = localServer { exchange ->
            val bytes = """{"choices":[{"message":{"content":"Whole answer"}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        try {
            val deltas = mutableListOf<String>()
            val response = provider(server).streamConversation(listOf(Message.User("Hi")), emptyList()) {
                deltas += it
            }
            assertEquals(listOf("Whole answer"), deltas)
            assertEquals(ModelResponse.TextResponse("Whole answer"), response)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun httpErrorsBecomeProviderExceptions() = runBlocking {
        val server = localServer { exchange ->
            val bytes = """{"detail":"invalid or missing token"}""".toByteArray()
            exchange.sendResponseHeaders(401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        try {
            val error = runCatching {
                provider(server).streamConversation(listOf(Message.User("Hi")), emptyList()) {}
            }.exceptionOrNull()
            assertTrue(error is ModelProviderException)
            assertTrue(error!!.message!!.contains("HTTP 401"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancellingStopsABlockedReadPromptly() = runBlocking {
        val release = CountDownLatch(1)
        val server = localServer { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody.bufferedWriter()
            out.write("""data: {"choices":[{"delta":{"content":"partial"}}]}""" + "\n\n")
            out.flush()
            // Simulate a model that goes quiet mid-answer.
            release.await(20, TimeUnit.SECONDS)
            runCatching { out.close() }
        }
        try {
            val firstDelta = CompletableDeferred<String>()
            val job = async(Dispatchers.Default) {
                provider(server).streamConversation(listOf(Message.User("Hi")), emptyList()) {
                    firstDelta.complete(it)
                }
            }
            assertEquals("partial", withTimeout(5_000) { firstDelta.await() })
            val started = System.nanoTime()
            job.cancel()
            withTimeout(5_000) { runCatching { job.await() } }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 3_000)
            assertTrue(job.isCancelled)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    private fun provider(server: HttpServer) = OpenAiCompatibleModelProvider(
        OpenAiCompatibleConfig(
            baseUrl = "http://127.0.0.1:${server.address.port}/v1",
            model = "test",
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 30_000,
        ),
    )

    private fun localServer(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r, "stream-test-http").apply { isDaemon = true } }
            createContext("/v1/chat/completions") { exchange -> exchange.use(handler) }
            start()
        }
}
