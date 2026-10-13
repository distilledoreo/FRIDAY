package com.localfirst.assistant.ui.genui
import com.localfirst.assistant.client.clientUiCatalog
import kotlin.test.*
class SharedRendererContractTest {
    @Test fun everyAdvertisedComponentHasTheSameRendererOnBothClients() {
        val missing = clientUiCatalog().components.map { it.name }.filterNot { it in GenUiRegistry.renderers || it in GenUiRegistry.rawRenderers }
        assertEquals(emptyList(), missing)
    }
    @Test fun calendarDatePickerUsesUtcAndRejectsImpossibleDates() {
        assertEquals(1709251200000L, isoDateMillis("2024-03-01"))
        assertNull(isoDateMillis("2024-02-30"))
        assertNull(isoDateMillis("not a date"))
    }
    @Test fun displayNumbersUseGroupingAndFixedDecimalsWithoutFormatterSuffixes() {
        assertEquals("8", formatUiDecimal(8.0, 0))
        assertEquals("1,250", formatUiDecimal(1250.0, 0))
        assertEquals("12.50", formatUiDecimal(12.5, 2))
        assertEquals("123.4568", formatUiDecimal(123.456789, 4, false))
    }

}
