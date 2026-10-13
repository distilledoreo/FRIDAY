package com.localfirst.assistant.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.support.v4.media.session.PlaybackStateCompat
import com.localfirst.assistant.voice.VoicePhase
import com.localfirst.assistant.voice.VoiceUiState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CarMediaControlsTest {
    @Test fun commandsDispatchWithoutSendingSearchTextToChat() {
        val events = mutableListOf<String>()
        val controls = CarMediaControls({ true }, { events += "play" }, { events += "talk" },
            { events += "pause" }, { events += "stop" })
        controls.onPrepare()
        assertTrue(events.isEmpty())
        controls.onPlayFromMediaId("unknown", null)
        controls.onCustomAction("unknown", null)
        assertTrue(events.isEmpty())
        controls.onPlayFromMediaId(CAR_VOICE_ITEM, null)
        controls.onCustomAction(CAR_TALK_ACTION, null)
        controls.onPause()
        controls.onPlayFromSearch("untrusted query text", null)
        controls.onStop()
        assertEquals(listOf("play", "talk", "pause", "play", "stop"), events)
    }

    @Test fun disabledOrUntrustedControllerCannotActivateMicrophone() {
        var commands = 0
        val command = { commands++; Unit }
        val controls = CarMediaControls({ false }, command, command, command, command)
        controls.onPlay()
        controls.onPlayFromMediaId(CAR_VOICE_ITEM, null)
        controls.onPlayFromSearch("FRIDAY", null)
        controls.onCustomAction(CAR_TALK_ACTION, null)
        controls.onPause()
        controls.onStop()
        assertEquals(0, commands)
    }

    @Test fun browsingStartsStoppedAndAdvertisesTalkWithoutPretendingToPlayAudio() {
        val state = carMediaPlayback(null, null, enabled = true)
        assertEquals(PlaybackStateCompat.STATE_STOPPED, state.state)
        assertEquals(PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, state.position)
        assertTrue(state.actions and PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID != 0L)
        assertEquals(listOf(CAR_TALK_ACTION), state.customActions.map { it.action })
    }

    @Test fun voicePhaseAndErrorsUpdatePlaybackControls() {
        assertEquals(PlaybackStateCompat.STATE_PLAYING, carMediaPlayback(VoiceUiState(VoicePhase.SPEAKING), null, true).state)
        assertEquals(PlaybackStateCompat.STATE_PAUSED, carMediaPlayback(VoiceUiState(VoicePhase.PAUSED), null, true).state)
        val error = carMediaPlayback(null, "Prepare voice on the phone", true)
        assertEquals(PlaybackStateCompat.STATE_ERROR, error.state)
        assertEquals("Prepare voice on the phone", error.errorMessage.toString())
        val disabled = carMediaPlayback(null, null, false)
        assertEquals(0L, disabled.actions)
        assertTrue(disabled.customActions.isEmpty())
    }

    @Test fun exportedBrowserHasMediaIntentAndTemplateServiceIsDisabled() {
        val context = RuntimeEnvironment.getApplication()
        val services = context.packageManager.queryIntentServices(
            Intent("android.media.browse.MediaBrowserService").setPackage(context.packageName), 0)
        assertTrue(services.any { it.serviceInfo.name == CarMediaService::class.java.name && it.serviceInfo.exported })
        val templated = context.packageManager.queryIntentServices(
            Intent("androidx.car.app.CarAppService").setPackage(context.packageName), 0)
        assertFalse(templated.any { it.serviceInfo.name == FridayCarService::class.java.name })
    }

    @Test fun clientValidationRejectsUnknownUidAndPackageSpoofing() {
        val context = RuntimeEnvironment.getApplication()
        val clients = TrustedCarMediaClients(context)
        assertTrue(clients.allows(context.packageName, context.applicationInfo.uid))
        assertFalse(clients.allows("spoofed.package", context.applicationInfo.uid))
        val other = PackageInfo().apply {
            packageName = "untrusted.media.client"
            applicationInfo = ApplicationInfo().apply { packageName = "untrusted.media.client"; uid = 23456 }
        }
        Shadows.shadowOf(context.packageManager).installPackage(other)
        assertFalse(clients.allows(other.packageName, 23456))
    }
}
