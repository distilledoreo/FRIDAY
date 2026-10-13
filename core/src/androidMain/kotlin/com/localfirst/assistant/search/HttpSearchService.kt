package com.localfirst.assistant.search

import com.localfirst.assistant.json.JsonCodec
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * POSTs to the desktop search service at `{baseUrl}/search`.
 * It does not know which SearchProvider that service uses.
 */
class HttpSearchService(
    config: SearchServiceConfig,
) : SearchService {
    @Volatile
    var config: SearchServiceConfig = config

    override suspend fun search(request: WebSearchRequest): WebSearchResponse = withContext(Dispatchers.IO) {
        val current = config
        val endpoint = resolveSearchUrl(current.baseUrl)
        val payload = JsonCodec.json.encodeToString(
            JsonElement.serializer(),
            buildSearchRequestBody(request),
        )
        post(endpoint, payload, current)
    }

    private fun post(endpoint: String, payload: String, current: SearchServiceConfig): WebSearchResponse {
        val connection = openConnection(endpoint, current)
        try {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { stream -> stream.write(bytes) }
            val code = connection.responseCode
            val responseStream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = searchErrorSnippet(text)
                val suffix = if (detail.isEmpty()) "" else " $detail"
                throw SearchServiceException("The search service returned HTTP $code.$suffix")
            }
            return try {
                parseSearchResponse(text)
            } catch (e: SearchServiceException) {
                throw e
            } catch (e: Exception) {
                throw SearchServiceException("The search service returned a response this app could not read.", e)
            }
        } catch (e: SearchServiceException) {
            throw e
        } catch (e: UnknownHostException) {
            throw SearchServiceException("Can't reach the search service. Check the search address.", e)
        } catch (e: ConnectException) {
            throw SearchServiceException(
                "Can't reach the search service. Check that it is running and reachable from this device.",
                e,
            )
        } catch (e: SocketTimeoutException) {
            throw SearchServiceException("The search service timed out.", e)
        } catch (e: IOException) {
            throw SearchServiceException(
                "Network error talking to the search service: ${e.message ?: "unknown I/O error"}",
                e,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(endpoint: String, current: SearchServiceConfig): HttpURLConnection {
        val url = try {
            URI(endpoint).toURL()
        } catch (e: Exception) {
            throw SearchServiceException("The search service address is not a valid URL.", e)
        }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = current.connectTimeoutMillis
            readTimeout = current.readTimeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            val key = current.apiKey?.trim().orEmpty()
            if (key.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $key")
            }
        }
    }
}
