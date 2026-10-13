package com.localfirst.assistant.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class PortableSpeechTest {
    @Test fun markdownCanBeSpokenOnEveryPlatform() {
        assertEquals("Hello from the fixture.", SpeechText.fromMarkdown("Hello from the fixture."))
        assertEquals("Read the docs. The code is on screen.", SpeechText.fromMarkdown("Read **[the docs](https://example.com)**.\n```kotlin\nval x = 1\n```"))
    }
}
