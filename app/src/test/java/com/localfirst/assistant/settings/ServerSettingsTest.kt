package com.localfirst.assistant.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSettingsTest {
    @Test
    fun acceptsAnOpenAiCompatibleRoot() {
        val settings = ServerSettings(
            baseUrl = " http://10.0.2.2:11434/v1 ",
            model = " llama3.2 ",
            timeoutSeconds = 90,
        )
        assertNull(settings.validate())
    }

    @Test
    fun rejectsAMissingModelAndABadTimeout() {
        assertEquals(
            "Enter the model name.",
            ServerSettings(baseUrl = "http://127.0.0.1:11434/v1", model = " ").validate(),
        )
        assertEquals(
            "Timeout must be between 5 and 600 seconds.",
            ServerSettings(
                baseUrl = "http://127.0.0.1:11434/v1",
                model = "llama3.2",
                timeoutSeconds = 1,
            ).validate(),
        )
    }
}
