package com.localfirst.assistant.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiBlocksTest {
    private val reply = """
        Here's how they compare.

        ```friday
        {"type":"compare","title":"Phones","items":[{"title":"OnePlus 13","badge":"Best battery","points":["6000 mAh"]},{"title":"Pixel 10 Pro","points":["Best camera"]}]}
        ```

        Want more detail?
    """.trimIndent()

    @Test
    fun splitsTextAndComponentsInOrder() {
        val parts = UiBlocks.segments(reply)
        assertEquals(3, parts.size)
        assertTrue(parts[0] is UiSegment.Text)
        val compare = (parts[1] as UiSegment.Block).block as UiBlock.Compare
        assertEquals(listOf("OnePlus 13", "Pixel 10 Pro"), compare.items.map { it.title })
        assertEquals("Best battery", compare.items[0].badge)
        assertTrue(parts[2] is UiSegment.Text)
    }

    @Test
    fun anUnfinishedBlockIsPendingAndLeftOutOfPlainText() {
        val streaming = "Here you go.\n\n```friday\n{\"type\":\"steps\",\"items\":[\"Open"
        assertEquals(UiSegment.Pending, UiBlocks.segments(streaming).last())
        assertEquals("Here you go.", UiBlocks.plainText(streaming))
    }

    @Test
    fun invalidOrUnknownBlocksFallBackToText() {
        assertTrue(UiBlocks.segments("```friday\n{not json}\n```").single() is UiSegment.Invalid)
        assertNull(UiBlocks.parse("""{"type":"script","code":"alert(1)"}"""))
        assertNull(UiBlocks.parse("""{"type":"choices","options":["only one"]}"""))
        val links = UiBlocks.parse("""{"type":"links","items":[{"title":"Bad","url":"javascript:alert(1)"},{"title":"Good","url":"https://example.com"}]}""") as UiBlock.Links
        assertEquals(listOf("Good"), links.items.map { it.title })
    }

    @Test
    fun plainTextReadsComponentsAloud() {
        val text = UiBlocks.plainText(reply)
        assertTrue(text, text.contains("OnePlus 13 (Best battery): 6000 mAh"))
        assertTrue(!text.contains("{"))
        val steps = UiBlocks.plainText("```friday\n{\"type\":\"steps\",\"items\":[{\"title\":\"Preheat\",\"detail\":\"to 400°F\"},\"Bake\"]}\n```")
        assertEquals("1. Preheat — to 400°F", steps)
    }
}
