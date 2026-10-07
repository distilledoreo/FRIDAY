package com.localfirst.assistant.search

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpSearchServiceTest {
    @Test
    fun postsSearchWithoutInvolvingAModelEndpoint() = runBlocking {
        val path = AtomicReference("")
        val body = AtomicReference("")
        val server = localServer()
        server.createContext("/search") { exchange ->
            path.set(exchange.requestURI.path)
            body.set(exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val response = """
                {
                  "provider": "searxng",
                  "fetched": false,
                  "results": [
                    {
                      "title": "Example",
                      "url": "https://example.com",
                      "snippet": "Snippet",
                      "score": 0.5,
                      "page_text": null
                    }
                  ]
                }
            """.trimIndent()
            write(exchange, 200, response)
        }
        server.createContext("/v1/chat/completions") { exchange ->
            write(exchange, 500, """{"error":"model endpoint should not be called"}""")
        }
        server.start()
        try {
            val service = HttpSearchService(
                SearchServiceConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    connectTimeoutMillis = 2_000,
                    readTimeoutMillis = 2_000,
                ),
            )
            val response = service.search(
                WebSearchRequest(query = "local assistant", limit = 2, fetchPages = false),
            )
            assertEquals("/search", path.get())
            val sent = com.localfirst.assistant.json.JsonCodec.json.parseToJsonElement(body.get()).jsonObject
            assertEquals("local assistant", sent["query"]!!.jsonPrimitive.content)
            assertEquals(2, sent["limit"]!!.jsonPrimitive.content.toInt())
            assertFalse(sent["fetch_pages"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("searxng", response.provider)
            assertEquals("https://example.com", response.results.single().url)
            assertEquals(0.5, response.results.single().score)
            assertEquals(null, response.results.single().pageText)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun httpFailureAndUnreachableHostBecomeSearchErrors() = runBlocking {
        val server = localServer()
        server.createContext("/search") { exchange ->
            write(exchange, 502, """{"error":"Can't reach SearXNG."}""")
        }
        server.start()
        try {
            val service = HttpSearchService(
                SearchServiceConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}/",
                    connectTimeoutMillis = 2_000,
                    readTimeoutMillis = 2_000,
                ),
            )
            val error = runCatching {
                service.search(WebSearchRequest("q", 5, false))
            }.exceptionOrNull()
            assertTrue(error is SearchServiceException)
            assertTrue(error!!.message!!.contains("HTTP 502"))
        } finally {
            server.stop(0)
        }

        val refused = HttpSearchService(
            SearchServiceConfig(
                baseUrl = "http://127.0.0.1:1",
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000,
            ),
        )
        val unreachable = runCatching {
            refused.search(WebSearchRequest("q", 5, false))
        }.exceptionOrNull()
        assertTrue(unreachable is SearchServiceException)
        assertTrue(unreachable!!.message!!.contains("Can't reach"))
    }

    @Test
    fun resolvesSearchUrlAndParsesPageText() {
        assertEquals(
            "http://10.0.2.2:8765/search",
            resolveSearchUrl("http://10.0.2.2:8765/"),
        )
        assertEquals(
            "http://127.0.0.1:8765/search",
            resolveSearchUrl("http://127.0.0.1:8765/search"),
        )
        val parsed = parseSearchResponse(
            """
            {"provider":"searxng","fetched":true,"results":[
              {"title":"A","url":"https://a.example","snippet":"s","page_text":"body","page_error":null}
            ]}
            """.trimIndent(),
        )
        assertTrue(parsed.fetched)
        assertEquals("body", parsed.results.single().pageText)
    }

    private fun localServer(): HttpServer {
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "search-test-http").apply { isDaemon = true }
            }
        }
    }

    private fun write(
        exchange: com.sun.net.httpserver.HttpExchange,
        code: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
