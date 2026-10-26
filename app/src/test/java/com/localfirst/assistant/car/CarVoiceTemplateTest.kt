package com.localfirst.assistant.car

import androidx.car.app.messaging.model.ConversationItem
import androidx.car.app.model.Row
import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.settings.ServerSettings
import com.localfirst.assistant.ui.ChatUiState
import com.localfirst.assistant.voice.VoicePhase
import com.localfirst.assistant.voice.VoiceUiState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CarVoiceTemplateTest {
    private fun template(state: ChatUiState = ChatUiState(), voice: VoiceUiState? = null) =
        carVoiceTemplate(state, voice, {}, {}, {}, {}, {})

    private fun titles(voice: VoiceUiState?) = template(voice = voice).singleList!!.items
        .filterIsInstance<Row>().map { it.title.toString() }

    @Test fun controlsFollowSessionState() {
        assertEquals(listOf("Talk to FRIDAY"), titles(null))
        assertEquals(listOf("Talk to FRIDAY", "Pause", "Stop voice"), titles(VoiceUiState(VoicePhase.LISTENING)))
        assertEquals(listOf("Resume", "Stop voice"), titles(VoiceUiState(VoicePhase.PAUSED)))
    }

    @Test fun disabledIntegrationExposesNoVoiceOrReplyControls() {
        val items = template(ChatUiState(settings = ServerSettings(androidAuto = false))).singleList!!.items
        assertEquals(1, items.size)
        assertNull((items.single() as Row).onClickDelegate)
    }

    @Test fun conversationBoundsMessagesAndOmitsSystemPrompts() {
        val messages = listOf(Message.System("private system prompt")) + (1..9).map { Message.User("message $it") }
        val conversation = template(ChatUiState(messages = messages)).singleList!!.items.filterIsInstance<ConversationItem>().single()
        assertEquals(5, conversation.messages.size)
        assertEquals("message 5", conversation.messages.first().body.toString())
        assertEquals("message 9", conversation.messages.last().body.toString())
        assertNotNull(conversation.conversationCallbackDelegate)
    }

    @Test fun customButtonsDispatchOwnVoiceCommandsAndReplyStaysSeparate() {
        val events = mutableListOf<String>()
        val screen = carVoiceTemplate(ChatUiState(), VoiceUiState(VoicePhase.LISTENING),
            { events += "talk" }, { events += "pause" }, { events += "stop" },
            { events += "read" }, { events += "reply:$it" })
        val done = object : androidx.car.app.OnDoneCallback {}
        screen.singleList!!.items.filterIsInstance<Row>().forEach { it.onClickDelegate!!.sendClick(done) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf("talk", "pause", "stop"), events)
        val conversation = screen.singleList!!.items.filterIsInstance<ConversationItem>().single()
        conversation.conversationCallbackDelegate.sendTextReply("hello", done)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("reply:hello", events.last())
    }

    @Test fun phoneMicSessionRefusesPlatformRecognitionFallback() = kotlinx.coroutines.runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val engines = com.localfirst.assistant.voice.VoiceEngines(context)
        try {
            engines.select(ServerSettings(voiceEngine = ServerSettings.VOICE_PHONE, searchBaseUrl = ""), forcePhoneMic = true)
            fail("A car session must not fall back to Android's uncontrolled microphone route")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("search service address"))
        }
    }

    @Test fun startupAndRouteFailuresRemainVisible() {
        assertEquals("Android blocked mic", carVoiceStatus(VoiceUiState(), "Android blocked mic"))
        assertEquals("Wrong microphone route", carVoiceStatus(VoiceUiState(VoicePhase.PAUSED, note = "Wrong microphone route"), null))
        assertEquals("Microphone muted", carVoiceStatus(VoiceUiState(VoicePhase.LISTENING, micMuted = true), null))
    }
}
