package com.localfirst.assistant.client

import kotlin.test.*

class SessionDraftsTest {
    private data class File(val id: String, val ready: Boolean = false)
    @Test fun stableChatsAndProjectDraftsHaveIndependentOwnership() {
        val drafts = SessionDrafts<File>()
        drafts.put("a", "p1", DraftSnapshot(ComposerDraft("A", listOf(File("a")))))
        drafts.put(null, "p1", DraftSnapshot(ComposerDraft("New one")))
        drafts.put(null, "p2", DraftSnapshot(ComposerDraft("New two")))
        assertEquals("A", drafts.take("a", "changed")!!.composer.text)
        assertNull(drafts.take("a", "p1"))
        assertEquals("New one", drafts.take(null, "p1")!!.composer.text)
        assertEquals("New two", drafts.take(null, "p2")!!.composer.text)
    }
    @Test fun lateImportsReachParkedDraftsAndTheirEditBackups() {
        val drafts = SessionDrafts<File>()
        drafts.put("a", null, DraftSnapshot(ComposerDraft("A", listOf(File("a")))))
        drafts.put("b", null, DraftSnapshot(ComposerDraft("Edited"), 0, ComposerDraft("Original", listOf(File("b")))))
        assertTrue(drafts.updateAttachment({ it.id == "a" }, { it.copy(ready = true) }))
        assertTrue(drafts.updateAttachment({ it.id == "b" }, { it.copy(ready = true) }))
        assertTrue(drafts.take("a", null)!!.composer.attachments.single().ready)
        assertTrue(drafts.take("b", null)!!.beforeEdit!!.attachments.single().ready)
        assertFalse(drafts.updateAttachment({ it.id == "b" }, { it }))
    }
    @Test fun cleanupReturnsOwnershipAndRejectsLateResults() {
        val drafts = SessionDrafts<File>()
        drafts.put("a", null, DraftSnapshot(ComposerDraft("A", listOf(File("a")))))
        drafts.put("b", null, DraftSnapshot(ComposerDraft("Edit"), 0, ComposerDraft("Before", listOf(File("b")))))
        assertEquals(setOf("a", "b"), drafts.attachments().map { it.id }.toSet())
        assertEquals(2, drafts.clear().size)
        assertTrue(drafts.attachments().isEmpty())
        assertFalse(drafts.updateAttachment({ true }, { it }))
    }
}
