package com.localfirst.assistant.tools.phoneuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppTreeTest {
    private fun snapshot(elements: List<AppElement>) = AppSnapshot(
        app = PhoneApp("Test", "com.example.test"), elements = elements, width = 100, height = 100,
    )

    @Test
    fun onlyUniversalSecondaryActionsAreLifted() {
        val elements = (0..9).map { AppElement(it, "button", "Button $it", secondaryActions = listOf("Context menu")) }
        val universal = AppTree.simplify(snapshot(elements))
        assertEquals(listOf("Context menu"), universal.commonActions)
        assertEquals(emptyList<String>(), universal.elements.first().secondaryActions)
        assertEquals(universal, AppTree.simplify(universal))
        val partial = AppTree.simplify(snapshot(elements.dropLast(1) + elements.last().copy(secondaryActions = emptyList())))
        assertEquals(emptyList<String>(), partial.commonActions)
        assertEquals(listOf("Context menu"), partial.elements.first().secondaryActions)
        assertEquals(emptyList<String>(), partial.elements.last().secondaryActions)
    }

    @Test
    fun layoutWrappersAreRemovedButActionableContainersStay() {
        val tree = snapshot(listOf(AppElement(0, "window", children = listOf(
            AppElement(1, "group", children = listOf(AppElement(2, "button", "Go"))),
            AppElement(3, "group", secondaryActions = listOf("Context menu")),
            AppElement(4, "group", states = listOf("scrollable")),
        ))))
        val result = AppTree.simplify(tree)
        assertNull(result.element(1))
        assertEquals(listOf(2, 3, 4), result.elements.single().children.map { it.index })
    }

    @Test
    fun fingerprintsIgnoreIndicesButIncludeActionAvailability() {
        val before = snapshot(listOf(AppElement(0, "button", "Go")))
        assertEquals(before.fingerprint(), before.copy(elements = listOf(AppElement(7, "button", "Go"))).fingerprint())
        org.junit.Assert.assertNotEquals(before.fingerprint(), before.copy(elements = listOf(AppElement(0, "button", "Go", secondaryActions = listOf("Menu")))).fingerprint())
    }
}
