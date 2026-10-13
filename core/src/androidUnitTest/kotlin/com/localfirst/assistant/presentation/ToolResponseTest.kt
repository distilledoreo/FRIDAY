package com.localfirst.assistant.presentation
import org.junit.Assert.*
import org.junit.Test
class ToolResponseTest {
    @Test fun actualCalendarFieldsBecomeNativeRowsWithoutInventedValues() {
        val content="""{"events":[{"title":"From the connected source","start":"2026-10-08T10:00:00Z"},{"title":"Untimed event"}]}"""
        val value=ToolResponses.parse("read_account_calendar",content)!!
        assertEquals(2,value.total);assertEquals(listOf("start: 2026-10-08T10:00:00Z"),value.entries[0].details)
        assertTrue(value.entries[1].details.isEmpty());assertEquals(content,value.fallback)
    }
    @Test fun unsupportedAndMalformedDataHaveAnOrdinaryTextFallback() {
        assertNull(ToolResponses.parse("unknown_tool","<slider onclick='run()'>"))
        assertTrue(ToolResponses.parse("list_followups","not JSON")!!.entries.isEmpty())
        assertTrue(ToolResponses.parse("list_followups","[]")!!.entries.isEmpty())
        assertEquals(0,ToolResponses.parse("list_followups","[]")!!.total)
    }
}
