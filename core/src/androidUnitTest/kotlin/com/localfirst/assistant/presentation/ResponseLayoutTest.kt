package com.localfirst.assistant.presentation

import org.junit.Assert.*
import org.junit.Test

class ResponseLayoutTest {
    @Test fun normalRepliesStayBubblesAndActualTablesUseAvailableWidth() {
        assertEquals(ResponsePattern.BUBBLE,ResponseLayout.choose("Hello. Here's a [source](https://example.org)."))
        assertEquals(ResponsePattern.FULL_WIDTH,ResponseLayout.choose("Comparison:\nName | Value\n--- | ---:\nOne | Two\n\nRemaining explanatory text."))
        assertEquals(ResponsePattern.BUBBLE,ResponseLayout.choose("a | b\nThis is prose, not a table."))
    }
    @Test fun codeDoesNotBecomeDataAndUnknownMarkupFallsBackToText() {
        assertEquals(ResponsePattern.FULL_WIDTH,ResponseLayout.choose("```text\nA | B\n--- | ---\n```"))
        assertEquals(ResponsePattern.BUBBLE,ResponseLayout.choose("<unsupported-ui>arbitrary text</unsupported-ui>"))
        assertEquals(ResponsePattern.BUBBLE,ResponseLayout.choose("A | B\n- | -\ninvalid separators"))
    }
    @Test fun longRepliesExpandWithoutLosingOrCorruptingUnicode() {
        val content="x".repeat(419)+"😀"+"y".repeat(1800)
        assertEquals(ResponsePattern.EXPANDABLE,ResponseLayout.choose(content))
        val preview=ResponseLayout.preview(content)
        assertFalse(preview.dropLast(1).last().isHighSurrogate())
        assertTrue(preview.endsWith("…"))
        assertEquals("Whole short reply",ResponseLayout.preview("Whole short reply"))
    }
    @Test fun unsupportedMarkupHasAVisibleTextFallbackWhileCodeAndAutolinksRemainMarkdown() {
        assertTrue(ResponseLayout.requiresTextFallback("<widget>Source payload</widget>"))
        assertFalse(ResponseLayout.requiresTextFallback("```html\n<widget>Source payload</widget>\n```"))
        assertFalse(ResponseLayout.requiresTextFallback("See <https://example.org/page>"))
    }

}
