package com.localfirst.assistant.ui.genui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.localfirst.assistant.desktop.desktopRequest
import com.localfirst.assistant.settings.ServerSettings
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Images for interface replies, through the PC: `/images` searches (SearXNG) and `/image` fetches a public
 * image, so the phone never contacts image hosts directly. Results and bytes are cached so a reopened
 * chat shows the same pictures without searching again.
 */
internal class GenUiImageService(context: Context, private val settings: () -> ServerSettings) : GenUiImages {
    private val dir = File(context.cacheDir, "genui-images").apply { mkdirs() }
    private val searches = LruCache<String, List<ImageHit>>(200)
    private val bitmaps = LruCache<String, ImageBitmap>(24)
    private val gate = Semaphore(4)

    private fun key(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    override suspend fun search(query: String, count: Int): List<ImageHit> = withContext(Dispatchers.IO) {
        val normalized = query.trim().lowercase()
        searches.get(normalized)?.let { return@withContext it }
        val cached = File(dir, key("q:$normalized") + ".json")
        val body = if (cached.isFile && System.currentTimeMillis() - cached.lastModified() < WEEK) cached.readText() else {
            val s = settings()
            if (s.searchBaseUrl.isBlank()) return@withContext emptyList()
            val (code, bytes) = gate.withPermit {
                desktopRequest(s.searchBaseUrl, s.searchApiKey, "GET", "/images?n=${count.coerceIn(1, 12)}&q=" + URLEncoder.encode(query.take(300), "UTF-8"), timeoutMs = 20_000)
            }
            if (code != 200) return@withContext emptyList()
            String(bytes).also { cached.writeText(it) }
        }
        val hits = runCatching {
            val results = JSONObject(body).getJSONArray("results")
            (0 until results.length()).map { i ->
                val r = results.getJSONObject(i)
                ImageHit(r.optString("image"), r.optString("thumbnail"), r.optString("title"), r.optString("source"))
            }.filter { it.image.startsWith("https://") }
        }.getOrDefault(emptyList())
        searches.put(normalized, hits)
        hits
    }

    override suspend fun load(url: String, maxEdge: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val memoryKey = "$maxEdge|$url"
        bitmaps.get(memoryKey)?.let { return@withContext it }
        val file = File(dir, key(url) + ".img")
        if (!file.isFile) {
            val s = settings()
            if (s.searchBaseUrl.isBlank()) return@withContext null
            val (code, bytes) = runCatching {
                gate.withPermit { desktopRequest(s.searchBaseUrl, s.searchApiKey, "GET", "/image?url=" + URLEncoder.encode(url, "UTF-8"), timeoutMs = 30_000) }
            }.getOrElse { return@withContext null }
            if (code != 200 || bytes.isEmpty()) return@withContext null
            file.writeBytes(bytes)
            prune()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()?.also { bitmaps.put(memoryKey, it) }
    }

    private fun prune() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) { total += f.length(); if (total > MAX_BYTES) f.delete() }
    }

    private companion object {
        const val WEEK = 7L * 24 * 3600 * 1000
        const val MAX_BYTES = 80L * 1024 * 1024
    }
}
