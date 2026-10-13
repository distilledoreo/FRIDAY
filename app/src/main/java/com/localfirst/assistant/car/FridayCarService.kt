package com.localfirst.assistant.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.messaging.model.CarMessage
import androidx.car.app.messaging.model.ConversationCallback
import androidx.car.app.messaging.model.ConversationItem
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.app.Person
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.localfirst.assistant.AssistantApp
import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.ui.ChatViewModel
import com.localfirst.assistant.ui.ChatUiState
import com.localfirst.assistant.voice.VoicePhase
import com.localfirst.assistant.voice.VoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Separate controls from Android Auto's system Reply action, which owns its own mic route. */
class FridayCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.Builder(this)
        .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
        .build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            val chat = (application as AssistantApp).chat()
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) { chat.stopCarVoice() }
            })
            return FridayCarScreen(carContext, chat)
        }
    }
}

internal fun carVoiceStatus(voice: VoiceUiState?, error: String?): String = error ?: voice?.note ?: when (voice?.phase) {
    null -> "Ready • phone microphone"
    VoicePhase.STARTING -> "Starting phone microphone…"
    VoicePhase.LISTENING -> if (voice.micMuted) "Microphone muted" else "Listening on phone"
    VoicePhase.THINKING -> "Thinking…"
    VoicePhase.SPEAKING -> "Speaking…"
    VoicePhase.CONFIRMING -> "Waiting for your spoken approval"
    VoicePhase.PAUSED -> "Paused • tap Resume"
}

internal class FridayCarScreen(context: CarContext, private val chat: ChatViewModel) : Screen(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                scope.launch {
                    // Avoid refreshing for audio levels and partial transcripts on every frame.
                    combine(chat.state, chat.voiceState) { state, voice ->
                        Triple(state.settings.androidAuto, carVoiceStatus(voice.takeIf { chat.carVoiceActive }, state.voiceError),
                            state.messages.takeLast(5))
                    }.distinctUntilChanged().collect { snapshot ->
                        if (!snapshot.first) chat.stopCarVoice()
                        invalidate()
                    }
                }
            }
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
        })
    }

    override fun onGetTemplate(): Template = carVoiceTemplate(
        chat.state.value, chat.voiceState.value.takeIf { chat.carVoiceActive },
        talk = { chat.startCarVoice() }, pause = { chat.pauseVoiceFromChat() }, stop = { chat.closeVoice() },
        markRead = { CarMessaging.markRead(carContext) }, reply = { text ->
            CarMessaging.showSent(carContext, text)
            CarTurnService.start(carContext)
            chat.receiveCarMessage(text)
        },
    )
}

internal fun carVoiceTemplate(
    state: ChatUiState,
    voice: VoiceUiState?,
    talk: () -> Unit,
    pause: () -> Unit,
    stop: () -> Unit,
    markRead: () -> Unit,
    reply: (String) -> Unit,
): ListTemplate {
    val list = ItemList.Builder()
    if (!state.settings.androidAuto) {
        list.addItem(Row.Builder().setTitle("Android Auto is off")
            .addText("Enable it in FRIDAY's Voice and server settings while parked.").build())
    } else {
        val paused = voice?.phase == VoicePhase.PAUSED
        list.addItem(Row.Builder().setTitle(if (paused) "Resume" else "Talk to FRIDAY")
            .addText(carVoiceStatus(voice, state.voiceError).take(220))
            .setOnClickListener { talk() }.build())
        if (voice != null) {
            if (!paused) list.addItem(Row.Builder().setTitle("Pause")
                .setOnClickListener { pause() }.build())
            list.addItem(Row.Builder().setTitle("Stop voice")
                .setOnClickListener { stop() }.build())
        }
        val me = Person.Builder().setName("You").setKey("user").build()
        val assistant = Person.Builder().setName("FRIDAY").setKey("assistant").build()
        val messages = state.messages.mapNotNull { message ->
            val sender: Person
            val body: String
            when (message) {
                is Message.User -> { sender = me; body = message.content }
                is Message.Assistant -> { sender = assistant; body = message.content }
                else -> return@mapNotNull null
            }
            if (body.isBlank()) return@mapNotNull null
            CarMessage.Builder().setSender(sender).setBody(CarText.create(body.take(500)))
                .setReceivedTimeEpochMillis(0).setRead(true).build()
        }.takeLast(5).ifEmpty {
            listOf(CarMessage.Builder().setSender(assistant)
                .setBody(CarText.create("Use Talk to FRIDAY for the phone mic. Reply uses the stereo mic."))
                .setReceivedTimeEpochMillis(0).setRead(true).build())
        }
        list.addItem(ConversationItem.Builder().setId("friday").setTitle(CarText.create("FRIDAY"))
            .setSelf(me).setGroupConversation(false).setMessages(messages)
            .setConversationCallback(object : ConversationCallback {
                override fun onMarkAsRead() { markRead() }
                override fun onTextReply(replyText: String) {
                    // This remains the host-owned system reply flow, separate from Talk.
                    reply(replyText)
                }
            }).build())
    }
    return ListTemplate.Builder().setHeader(Header.Builder().setTitle("FRIDAY").setStartHeaderAction(Action.APP_ICON).build())
        .setSingleList(list.build()).build()
}
