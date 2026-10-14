package com.localfirst.assistant.ui

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import com.localfirst.assistant.attachments.AttachmentImporter
import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.model.*
import com.localfirst.assistant.settings.*
import com.localfirst.assistant.tools.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeDraftRecoveryTest {
    private lateinit var vm: ChatViewModel
    private lateinit var store: FileConversationStore
    private val owner = ViewModelStore()
    private val release = CountDownLatch(1)
    private var startupBlocked = false
    private val uploads = java.util.concurrent.atomic.AtomicInteger()
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        val settings = ServerSettingsStore(app)
        settings.save(ServerSettings(baseUrl = "https://example.test/v1", model = "fixture-model", searchBaseUrl = ""))
        store = FileConversationStore(File(app.cacheDir, "draft-test-chats"))
        listOf("a", "b").forEach { id -> store.save(StoredConversation(ConversationSummary(id, id, 1, 1),
            listOf(Message.User("First question"), Message.Assistant("Original answer")))) }
        val model = object : ModelProvider {
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>) = ModelResponse.TextResponse("Fixture answer")
        }
        vm = ChatViewModel(settings, store, ToolRegistry(), { model }, attachments = AttachmentImporter(app) { _, _, _, _ -> uploads.incrementAndGet(); null }, app = app, appearance = {
            check(!startupBlocked) { "Fixture readiness failure" }; com.localfirst.assistant.settings.AppearanceSettings()
        })
        owner.put("fixture", vm)
        vm.openConversation("a"); await { vm.state.value.conversationId == "a" }
    }
    @After fun cleanup() { release.countDown(); owner.clear(); Shadows.shadowOf(Looper.getMainLooper()).idle() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        assertTrue("Expected draft transition", condition())
    }
    private fun image(block: Boolean = false): Pair<Uri, CountDownLatch> {
        val app = RuntimeEnvironment.getApplication()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/png"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("fixture.png")) }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(app, ProviderInfo().apply { authority = "fixture.attachments" })
        ShadowContentResolver.registerProviderInternal("fixture.attachments", provider)
        val uri = Uri.parse("content://fixture.attachments/image")
        val bytes = ByteArrayOutputStream().also {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle()
        }.toByteArray()
        val entered = CountDownLatch(1)
        Shadows.shadowOf(app.contentResolver).registerInputStreamSupplier(uri, Supplier {
            object : InputStream() {
                private val source = ByteArrayInputStream(bytes)
                private fun ready() { entered.countDown(); if (block) check(release.await(8, TimeUnit.SECONDS)) }
                override fun read(): Int { ready(); return source.read() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int { ready(); return source.read(buffer, offset, length) }
            }
        })
        return uri to entered
    }
    private fun attach(): File {
        vm.addAttachments(listOf(image().first))
        await { vm.state.value.draftAttachments.singleOrNull()?.status == DraftStatus.READY }
        return File(vm.state.value.draftAttachments.single().attachment!!.path!!)
    }
    @Test fun switchingAndEditingRetainFilesAndOriginalComposer() {
        vm.onDraftChange("Draft A"); val file = attach()
        vm.openConversation("b"); await { vm.state.value.conversationId == "b" }
        assertTrue(file.exists()); assertEquals("", vm.state.value.draft)
        vm.onDraftChange("Draft B"); vm.openConversation("a"); await { vm.state.value.conversationId == "a" }
        assertEquals("Draft A", vm.state.value.draft); assertEquals(file.path, vm.state.value.draftAttachments.single().attachment!!.path)
        vm.startEditing(0); assertTrue(file.exists()); assertTrue(vm.state.value.draftAttachments.isEmpty())
        vm.onDraftChange("Changed question"); vm.cancelEditing()
        assertEquals("Draft A", vm.state.value.draft); assertTrue(file.exists())
        vm.startEditing(0); vm.onDraftChange("Saved edit"); vm.send()
        await { !vm.state.value.busy && (vm.state.value.messages.firstOrNull() as? Message.User)?.content == "Saved edit" }
        assertEquals("Draft A", vm.state.value.draft); assertTrue(file.exists())
        vm.removeDraftAttachment(vm.state.value.draftAttachments.single().id); assertFalse(file.exists())
    }
    @Test fun lateImportCompletesForItsParkedOwner() {
        val (uri, entered) = image(block = true)
        vm.addAttachments(listOf(uri)); await { entered.count == 0L }
        vm.openConversation("b"); await { vm.state.value.conversationId == "b" }
        release.countDown(); vm.openConversation("a"); await { vm.state.value.conversationId == "a" && vm.state.value.draftAttachments.singleOrNull()?.status == DraftStatus.READY }
        assertTrue(File(vm.state.value.draftAttachments.single().attachment!!.path!!).exists())
    }
    @Test fun deletionAndLifecycleCleanupReleaseOnlyUnsentFiles() {
        vm.onDraftChange("Unsent"); val file = attach()
        vm.openConversation("b"); await { vm.state.value.conversationId == "b" }
        vm.deleteConversation("a"); await { store.load("a") == null }
        assertFalse(file.exists())
        val sent = attach(); vm.send()
        await { !vm.state.value.busy && store.load("b")?.messages?.filterIsInstance<Message.User>()?.any { message -> message.attachments.any { it.path == sent.path } } == true }
        val unsent = attach()
        owner.clear(); assertFalse(unsent.exists()); assertTrue(sent.exists())
    }
    @Test fun dashboardQuickSendPreservesAnUnsentNewChatAndItsFile() {
        vm.newChat(); await { vm.state.value.conversationId == null }
        vm.onDraftChange("Unsent new chat"); val file = attach()
        vm.chatAbout("Dashboard question")
        await { !vm.state.value.busy && vm.state.value.conversationId != null }
        assertTrue(file.exists())
        vm.newChat(); await { vm.state.value.conversationId == null }
        assertEquals("Unsent new chat", vm.state.value.draft)
        assertEquals(file.path, vm.state.value.draftAttachments.single().attachment!!.path)
    }
    @Test fun clearingDuringAnImportCancelsUploadAndDeletesTemporaryFiles() {
        val (uri, entered) = image(block = true)
        vm.addAttachments(listOf(uri)); await { entered.count == 0L }
        val job = vm.viewModelScope.coroutineContext[Job]!!
        owner.clear(); release.countDown(); await { job.isCompleted }
        assertEquals(0, uploads.get())
        assertTrue(File(RuntimeEnvironment.getApplication().filesDir, "attachments").listFiles().orEmpty().isEmpty())
    }
    @Test fun failedStartupKeepsTheEditAndItsOriginalComposer() {
        vm.onDraftChange("Original unsent question"); val file = attach()
        vm.startEditing(0); vm.onDraftChange("Unsent edit"); startupBlocked = true; vm.send()
        assertFalse(vm.state.value.busy); assertEquals("Unsent edit", vm.state.value.draft)
        assertEquals(0, vm.state.value.editingIndex); assertTrue(vm.state.value.error!!.contains("Fixture readiness failure"))
        vm.cancelEditing(); assertEquals("Original unsent question", vm.state.value.draft); assertTrue(file.exists())
    }
    @Test fun privateDraftsNeverReplaceNormalNewChatDrafts() {
        vm.newChat(); await { vm.state.value.conversationId == null }
        vm.onDraftChange("Normal new chat")
        vm.startIncognito(); await { vm.state.value.privacy.incognito }
        vm.onDraftChange("Private one"); vm.startIncognito(); await { vm.state.value.privacy.incognito && vm.state.value.draft.isBlank() }
        vm.onDraftChange("Private two"); vm.newChat(); await { !vm.state.value.privacy.incognito }
        assertEquals("Normal new chat", vm.state.value.draft)
        vm.newProjectChat("p1"); await { vm.state.value.projectId == "p1" }; vm.onDraftChange("Project one")
        vm.newProjectChat("p2"); await { vm.state.value.projectId == "p2" }; vm.onDraftChange("Project two")
        vm.newProjectChat("p1"); await { vm.state.value.projectId == "p1" }; assertEquals("Project one", vm.state.value.draft)
    }
}
