package com.localfirst.assistant.presentation
import org.junit.Assert.*
import org.junit.Test
class ResponsePartsTest {
    @Test fun tablesPreserveProseRowsAndEscapedPipes() {
        val parts=ResponseParts.parse("Before\n| Name | Value |\n| --- | :---: |\n| A \\| B | `x|y` |\n\nAfter")
        assertEquals(3,parts.size)
        val table=parts[1] as ResponsePart.Table
        assertEquals(listOf("A | B","`x|y`"),table.rows.single())
        assertTrue((parts.last() as ResponsePart.Prose).markdown.contains("After"))
    }
    @Test fun codeFencesAndMalformedTablesRemainOrdinaryMarkdown() {
        assertEquals(1,ResponseParts.parse("```\n| A | B |\n| --- | --- |\n```").size)
        assertTrue(ResponseParts.parse("| A | B |\n| - | - |").single() is ResponsePart.Prose)
    }
}
