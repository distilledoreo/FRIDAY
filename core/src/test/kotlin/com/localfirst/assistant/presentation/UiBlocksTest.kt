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

    @Test
    fun calculatorNeedsDeclaredVariablesAndArithmeticOnly() {
        val calc = UiBlocks.parse("""{"type":"calculator","title":"Tip","vars":[{"name":"bill","label":"Bill","min":0,"max":500,"step":5,"default":100}],"formula":"bill*0.2","result":"Tip"}""") as UiBlock.Calculator
        assertEquals("bill*0.2", calc.formula)
        assertEquals(20.0, UiBlocks.FormulaEval.check("bill*0.2", mapOf("bill" to 100.0)))
        assertEquals("42", UiBlocks.formatNumber(42.0))
        // Undeclared variables, bad characters, and scripting fall back to text.
        assertNull(UiBlocks.parse("""{"type":"calculator","vars":[{"name":"bill","min":0,"max":1}],"formula":"bill+tip"}"""))
        assertNull(UiBlocks.parse("""{"type":"calculator","vars":[{"name":"bill","min":0,"max":1}],"formula":"bill;alert(1)"}"""))
        assertNull(UiBlocks.parse("""{"type":"script","code":"alert(1)"}"""))
    }

    @Test
    fun formulaEvalHandlesPrecedenceAndFailsClosed() {
        val eval = UiBlocks.FormulaEval
        assertEquals(14.0, eval.check("2+3*4", emptyMap()))
        assertEquals(20.0, eval.check("(2+3)*4", emptyMap()))
        assertEquals(-3.0, eval.check("-3", emptyMap()))
        assertEquals(4.0, eval.check("sqrt(16)+abs(0-0)", emptyMap()))
        assertNull(eval.check("1/0", emptyMap()))
        assertNull(eval.check("(", emptyMap()))
        assertNull(eval.check("", emptyMap()))
        assertNull(eval.check("price*2", emptyMap()))
    }

    @Test
    fun chartAndFormParseWithBounds() {
        val chart = UiBlocks.parse("""{"type":"chart","kind":"line","labels":["Mon","Tue"],"series":[{"name":"Steps","values":[3000,5200]}]}""") as UiBlock.Chart
        assertEquals("line", chart.kind)
        assertEquals(listOf(3000.0, 5200.0), chart.series.single().values)
        assertNull(UiBlocks.parse("""{"type":"chart","labels":["Mon"],"series":[{"name":"A","values":[1,2]}]}"""))
        val form = UiBlocks.parse("""{"type":"form","prompt":"Book it","fields":[{"id":"name","label":"Name"},{"id":"city","label":"City","kind":"choice","options":["Leeds","York"]}],"submit":"Book"}""") as UiBlock.Form
        assertEquals(listOf("Name", "City"), form.fields.map { it.label })
        assertNull(UiBlocks.parse("""{"type":"form","fields":[{"id":"city","label":"City","kind":"choice","options":["Only"]}]}"""))
        assertTrue(UiBlocks.plainText("```friday\n{\"type\":\"chart\",\"series\":[{\"name\":\"S\",\"values\":[1]}]}\n```").contains("S: 1.0"))
    }
}
