package com.localfirst.assistant.workspace

import org.junit.Assert.*
import org.junit.Test

class ImageSettingsTest {
    @Test fun manualMaximumAndStepBudgetAreEnforced() {
        assertNull(ImageSettings(automatic = false, width = 2000, height = 2000, steps = 8).validate())
        assertNotNull(ImageSettings(automatic = false, width = 2000, height = 2000, steps = 12).validate())
        assertNotNull(ImageSettings(automatic = false, width = 2016).validate())
        assertNotNull(ImageSettings(automatic = false, width = 777).validate())
    }
    @Test fun automaticIgnoresUnusedManualFieldsButLimitsReferences() {
        assertNull(ImageSettings(automatic = true, width = 0).validate())
        assertNotNull(ImageSettings(references = listOf("a", "b", "c")).validate())
    }
}
