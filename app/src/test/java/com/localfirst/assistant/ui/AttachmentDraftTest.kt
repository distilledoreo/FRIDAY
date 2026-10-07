package com.localfirst.assistant.ui

import com.localfirst.assistant.conversation.Attachment
import com.localfirst.assistant.conversation.AttachmentKind
import com.localfirst.assistant.conversation.Message
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentDraftTest {
    private val image = Attachment("photo", AttachmentKind.IMAGE, "photo.jpg", "image/jpeg", path = "/photo.jpg")
    private val ready = DraftAttachment("draft", "photo.jpg", AttachmentKind.IMAGE, DraftStatus.READY, image)

    @Test
    fun attachmentOnlyDraftIsSendableOnceAllFilesAreReady() {
        assertFalse(ChatUiState().canSend)
        assertTrue(ChatUiState(draftAttachments = listOf(ready)).canSend)
        assertFalse(ChatUiState(draft = "Describe this", draftAttachments = listOf(ready.copy(status = DraftStatus.READING))).canSend)
        assertFalse(ChatUiState(draft = "Describe this", draftAttachments = listOf(ready, ready.copy(id = "failed", status = DraftStatus.FAILED))).canSend)
        assertFalse(ChatUiState(draftAttachments = listOf(ready.copy(attachment = null))).canSend)
        assertTrue(ChatUiState(draft = "Describe this").canSend)
    }

    @Test
    fun editingCanRemoveAllWordsWhileKeepingTheOriginalImage() {
        val editing = ChatUiState(messages = listOf(Message.User("Describe this", listOf(image))), editingIndex = 0)
        assertTrue(editing.canSend)
        assertFalse(editing.copy(busy = true).canSend)
        assertFalse(editing.copy(messages = listOf(Message.User("Describe this"))).canSend)
        assertFalse(editing.copy(editingIndex = null).canSend)
    }
}
