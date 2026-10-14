package com.localfirst.assistant.attachments

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DocumentImportCleanupTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val dir get() = File(app.filesDir, "attachments")
    private fun input(stream: () -> InputStream = { ByteArrayInputStream("fixture bytes".toByteArray()) }): Uri {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "text/plain"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("fixture.txt")) }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(app, ProviderInfo().apply { authority = "fixture.documents" })
        ShadowContentResolver.registerProviderInternal("fixture.documents", provider)
        val uri = Uri.parse("content://fixture.documents/file")
        Shadows.shadowOf(app.contentResolver).registerInputStreamSupplier(uri, Supplier(stream))
        return uri
    }
    private fun importer(body: String, code: Int = 200, upload: suspend (File, String, Boolean, String?) -> String? = { _, _, _, _ -> null }) =
        AttachmentImporter(app, documentExtractor = { bytes, name, mime ->
            assertEquals("fixture bytes", String(bytes)); assertEquals("fixture.txt", name); assertEquals("text/plain", mime)
            code to body.toByteArray()
        }, fileUploader = upload)

    @Test fun successfulImportsRetainOwnedOriginalAndPagesUntilRemoved() = runBlocking {
        val importer = importer("""{"text":"Extracted fixture","images":["eA=="]}""")
        val attachment = importer.importDocument(input(), "https://example.test", null)
        assertEquals("Extracted fixture", attachment.text)
        assertTrue(File(attachment.path!!).exists()); assertEquals(1, attachment.pageImages.size)
        assertTrue(File(attachment.pageImages.single()).exists())
        importer.delete(listOf(attachment)); assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
    @Test fun unreadableDocumentsDoNotLeaveAnOriginalBehind() = runBlocking {
        try { importer("""{"text":"","images":[]}""").importDocument(input(), "https://example.test", null); fail("Unreadable document accepted") }
        catch (_: IOException) { assertTrue(dir.listFiles().orEmpty().isEmpty()) }
    }
    @Test fun workspaceFallbackStillKeepsTheOriginalForAnalysis() = runBlocking {
        val importer = importer("""{"detail":"Unsupported preview"}""", 415) { _, _, _, _ -> "fixture-remote" }
        val attachment = importer.importDocument(input(), "https://example.test", null)
        assertEquals("fixture-remote", attachment.remoteFileId); assertNotNull(attachment.note)
        assertTrue(File(attachment.path!!).exists())
        importer.delete(listOf(attachment)); assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
    @Test fun cancellationDuringUploadDeletesOriginalAndStopsPageWrites() = runBlocking {
        val started = CompletableDeferred<File>(); val release = CompletableDeferred<Unit>()
        val importer = importer("""{"text":"Extracted","images":["eA=="]}""") { file, _, _, _ ->
            started.complete(file); withContext(NonCancellable) { release.await() }; "fixture-remote"
        }
        val job = launch { importer.importDocument(input(), "https://example.test", null) }
        val original = withTimeout(8000) { started.await() }; assertTrue(original.exists())
        job.cancel(); release.complete(Unit); job.join()
        assertTrue(job.isCancelled); assertFalse(original.exists()); assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
    @Test fun cancellationDuringReadDoesNotStartExtractionOrUpload() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        var extracts = 0; var uploads = 0
        val uri = input { object : ByteArrayInputStream("fixture bytes".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { entered.complete(Unit); check(release.await(8, TimeUnit.SECONDS)); return super.read(buffer, offset, length) }
        } }
        val importer = AttachmentImporter(app, documentExtractor = { _, _, _ -> extracts++; 200 to "{}".toByteArray() }, fileUploader = { _, _, _, _ -> uploads++; null })
        val job = launch { importer.importDocument(uri, "https://example.test", null) }
        try { withTimeout(8000) { entered.await() }; job.cancel() } finally { release.countDown() }
        job.join(); assertTrue(job.isCancelled); assertEquals(0, extracts); assertEquals(0, uploads)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
