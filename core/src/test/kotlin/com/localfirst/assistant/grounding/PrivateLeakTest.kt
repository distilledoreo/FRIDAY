package com.localfirst.assistant.grounding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateLeakTest {
    private val memories = listOf("The user's sister Priya works at Baylor Scott & White. Contact: priya.k@example.org, 214-555-0182.")

    @Test
    fun everydayWordsAndTheUsersOwnWordsAreFine() {
        assertTrue(PrivateLeak.details("best hospitals near me 2026", memories, "Which hospitals are best near me?").isEmpty())
        assertTrue(PrivateLeak.details("Baylor Scott White reviews", memories, "Is Baylor Scott & White any good?").isEmpty())
        assertTrue(PrivateLeak.details("anything at all", emptyList(), "").isEmpty())
    }

    @Test
    fun detailsFromPrivateContextAreFlagged() {
        assertEquals(listOf("priya"), PrivateLeak.details("Priya nurse schedule", memories, "When does my sister work?"))
        assertEquals(listOf("priya.k@example.org"), PrivateLeak.details("priya.k@example.org", memories, "Look up my sister's email"))
        assertTrue(PrivateLeak.details("who owns 214-555-0182", memories, "whose number is that").contains("214-555-0182"))
    }

    @Test
    fun recalledMemoriesSkipTheIntroduction() {
        val prompt = "System prompt.\n\nRetrieved personal context. Approved memories are user-reviewed facts.\n\nThe user lives in Plano."
        assertEquals("The user lives in Plano.", PrivateLeak.recalled(prompt))
        assertEquals(null, PrivateLeak.recalled("No memories here."))
    }
}
