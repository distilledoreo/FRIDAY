package com.localfirst.assistant.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.localfirst.assistant.conversation.Attachment
import com.localfirst.assistant.conversation.AttachmentKind
import com.localfirst.assistant.desktop.desktopRequest
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Turns picked, shared, or photographed files into [Attachment]s stored in the
 * app's private files. Images are shrunk to [MAX_EDGE] px and saved as JPEG.
 * Documents go to the computer's /extract, which returns their text (or page
 * images for scans).
 */
class AttachmentImporter(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "attachments").apply { mkdirs() }

    fun isImage(uri: Uri): Boolean = runCatching {
        app.contentResolver.getType(uri)?.startsWith("image/") == true
    }.getOrDefault(false)

    fun displayName(uri: Uri): String = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    suspend fun importImage(uri: Uri, name: String = displayName(uri)): Attachment = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val out = File(dir, "$id.jpg")
        val bitmap = decodeScaled(uri) ?: throw IOException("That image couldn't be opened.")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        val workspace = com.localfirst.assistant.workspace.WorkspaceClient(app) {
            com.localfirst.assistant.settings.ServerSettingsStore(app).load()
        }
        val remote = runCatching { workspace.upload(out, name) }.getOrNull()
        Attachment(id = id, kind = AttachmentKind.IMAGE, name = name, mimeType = "image/jpeg", path = out.absolutePath, remoteFileId = remote)
    }

    suspend fun importDocument(uri: Uri, baseUrl: String, apiKey: String?): Attachment = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw IOException("Set the search service address to read documents on your computer.")
        val name = displayName(uri)
        val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = input.readNBytesCompat(MAX_DOCUMENT_BYTES + 1)
            if (buffer.size > MAX_DOCUMENT_BYTES) throw IOException("$name is larger than 25 MB.")
            buffer
        } ?: throw IOException("$name couldn't be opened.")
        val (code, body) = try {
            desktopRequest(
                baseUrl, apiKey, "POST", "/extract", bytes, mime,
                headers = mapOf("X-Filename" to URLEncoder.encode(name, "UTF-8").replace("+", "%20")),
                timeoutMs = 120_000,
            )
        } catch (e: IOException) {
            throw IOException("Couldn't reach your computer to read $name.")
        }
        val original = File(dir, "${UUID.randomUUID()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        original.writeBytes(bytes)
        val workspace = com.localfirst.assistant.workspace.WorkspaceClient(app) {
            com.localfirst.assistant.settings.ServerSettingsStore(app).load()
        }
        val remoteId = runCatching { workspace.upload(original, name) }.getOrNull()
        val json = runCatching { JSONObject(String(body)) }.getOrNull()
        if (code != 200 || json == null) {
            if (remoteId != null) return@withContext Attachment(id = UUID.randomUUID().toString(), kind = AttachmentKind.DOCUMENT,
                name = name, mimeType = mime, path = original.absolutePath, remoteFileId = remoteId,
                note = "Use execute_python to read this workspace file; text preview unavailable.")
            original.delete()
            throw IOException(json?.optString("detail")?.takeIf { it.isNotBlank() }?.replaceFirstChar(Char::uppercase) ?: "Couldn't read $name (HTTP $code).")
        }
        val id = UUID.randomUUID().toString()
        val images = json.optJSONArray("images")
        val pages = (0 until (images?.length() ?: 0)).map { i ->
            val file = File(dir, "$id-p${i + 1}.jpg")
            file.writeBytes(Base64.decode(images!!.getString(i), Base64.DEFAULT))
            file.absolutePath
        }
        if (json.optString("text").isBlank() && pages.isEmpty()) throw IOException("No readable text or pages found in $name.")
        val notes = listOfNotNull(
            json.optString("note").takeIf { it.isNotBlank() },
            if (json.optBoolean("truncated")) "Text cut at 60,000 characters." else null,
        )
        Attachment(
            id = id,
            kind = AttachmentKind.DOCUMENT,
            name = name,
            mimeType = mime,
            path = original.absolutePath,
            remoteFileId = remoteId,
            text = json.optString("text").takeIf { it.isNotBlank() },
            pageImages = pages,
            note = notes.joinToString(" ").ifBlank { null },
        )
    }

    /** Deletes the stored files of attachments that are no longer needed. */
    fun delete(attachments: List<Attachment>) {
        for (a in attachments) {
            (listOfNotNull(a.path) + a.pageImages).forEach { path ->
                File(path).takeIf { it.parentFile == dir }?.delete()
            }
        }
    }

    private fun decodeScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val longest = max(decoded.width, decoded.height)
        val scaled = if (longest > MAX_EDGE) {
            val f = MAX_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(decoded, (decoded.width * f).roundToInt().coerceAtLeast(1), (decoded.height * f).roundToInt().coerceAtLeast(1), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        val rotation = runCatching {
            app.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        if (rotation == 0f) return scaled
        val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(rotation) }, true)
        if (rotated !== scaled) scaled.recycle()
        return rotated
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_EDGE = 1280
        const val JPEG_QUALITY = 85
        const val MAX_DOCUMENT_BYTES = 25 * 1024 * 1024
    }
}
