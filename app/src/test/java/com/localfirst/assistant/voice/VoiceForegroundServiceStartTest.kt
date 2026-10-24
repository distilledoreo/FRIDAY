package com.localfirst.assistant.voice

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class VoiceForegroundServiceStartTest {
    private var starts = 0
    private val blockedContext get() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun startForegroundService(service: Intent): ComponentName? {
            starts++
            throw SecurityException("Background microphone is blocked")
        }
    }

    @After fun reset() {
        VoiceForegroundService.active.value = false
        VoiceForegroundService.onStopVoice = null
    }

    @Test fun preparedSessionCanResumeWithoutAnotherBackgroundServiceStart() = runBlocking {
        VoiceForegroundService.active.value = true
        var stopped = false
        VoiceForegroundService.start(blockedContext) { stopped = true }
        assertEquals(0, starts)
        VoiceForegroundService.onStopVoice!!.invoke()
        assertTrue(stopped)
    }

    @Test fun blockedStartupPropagatesToCallerForRecoveryMessage() = runBlocking {
        VoiceForegroundService.active.value = false
        try {
            VoiceForegroundService.start(blockedContext) {}
            fail("Blocked start must be reported")
        } catch (e: SecurityException) {
            assertEquals("Background microphone is blocked", e.message)
        }
        assertFalse(VoiceForegroundService.active.value)
    }
}
