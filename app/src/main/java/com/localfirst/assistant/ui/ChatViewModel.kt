package com.localfirst.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.localfirst.assistant.AssistantPrompts
import com.localfirst.assistant.conversation.ConversationSession
import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.conversation.TurnOutcome
import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.OpenAiCompatibleConfig
import com.localfirst.assistant.model.OpenAiCompatibleModelProvider
import com.localfirst.assistant.settings.ServerSettings
import com.localfirst.assistant.settings.ServerSettingsStore
import com.localfirst.assistant.tools.AndroidMediaVolume
import com.localfirst.assistant.tools.SetMediaVolumeTool
import com.localfirst.assistant.tools.ToolRegistry
import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val busy: Boolean = false,
    val status: String = "Set the server address",
    val error: String? = null,
    val settings: ServerSettings = ServerSettings(),
    val showSettings: Boolean = false,
    val settingsError: String? = null,
)

class ChatViewModel(
    private val settingsStore: ServerSettingsStore,
    toolRegistry: ToolRegistry,
    private val providers: (ServerSettings) -> ModelProvider,
) : ViewModel() {
    private val session = ConversationSession(
        modelProvider = providers(settingsStore.load()),
        toolRegistry = toolRegistry,
        systemPrompt = AssistantPrompts.SYSTEM,
    )

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    fun onDraftChange(value: String) {
        _state.update { it.copy(draft = value) }
    }

    fun openSettings() {
        _state.update {
            it.copy(
                showSettings = true,
                settings = settingsStore.load(),
                settingsError = null,
            )
        }
    }

    fun dismissSettings() {
        _state.update { it.copy(showSettings = false, settingsError = null) }
    }

    fun saveSettings(settings: ServerSettings) {
        val error = settings.validate()
        if (error != null) {
            _state.update { it.copy(settingsError = error, showSettings = true) }
            return
        }
        val normalized = settings.copy(
            baseUrl = settings.baseUrl.trim(),
            model = settings.model.trim(),
            apiKey = settings.apiKey.trim(),
        )
        settingsStore.save(normalized)
        session.modelProvider = providers(normalized)
        _state.update {
            it.copy(
                settings = normalized,
                showSettings = false,
                settingsError = null,
                error = null,
                status = statusFor(normalized, error = null, busy = it.busy),
            )
        }
    }

    fun send() {
        val current = _state.value
        val draft = current.draft
        if (current.busy || draft.isBlank()) return
        val settingsError = current.settings.validate()
        if (settingsError != null) {
            _state.update { it.copy(error = settingsError, showSettings = true, settingsError = settingsError) }
            return
        }
        _state.update { it.copy(draft = "", busy = true, error = null, status = "Sending…") }
        viewModelScope.launch {
            try {
                val outcome = session.submitUserMessage(draft) { messages ->
                    _state.update { state ->
                        state.copy(messages = messages, status = statusWhile(messages))
                    }
                }
                applyOutcome(outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = e.message ?: "Something went wrong.",
                        status = "Not connected",
                    )
                }
            }
        }
    }

    fun retry() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, status = "Sending…") }
        viewModelScope.launch {
            try {
                val outcome = session.retry { messages ->
                    _state.update { state ->
                        state.copy(messages = messages, status = statusWhile(messages))
                    }
                }
                applyOutcome(outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = e.message ?: "Something went wrong.",
                        status = "Not connected",
                    )
                }
            }
        }
    }

    fun clearConversation() {
        viewModelScope.launch {
            session.clear()
            _state.update {
                it.copy(
                    messages = emptyList(),
                    error = null,
                    busy = false,
                    status = statusFor(it.settings, error = null, busy = false),
                )
            }
        }
    }

    private fun applyOutcome(outcome: TurnOutcome) {
        when (outcome) {
            is TurnOutcome.Completed -> _state.update {
                it.copy(
                    messages = outcome.messages,
                    busy = false,
                    error = null,
                    status = "Connected",
                )
            }
            is TurnOutcome.Failed -> _state.update {
                it.copy(
                    messages = outcome.messages,
                    busy = false,
                    error = outcome.error,
                    status = "Not connected",
                )
            }
            is TurnOutcome.EmptyInput -> _state.update {
                it.copy(
                    busy = false,
                    status = statusFor(it.settings, it.error, busy = false),
                )
            }
        }
    }

    private fun initialState(): ChatUiState {
        val settings = settingsStore.load()
        return ChatUiState(
            messages = session.snapshot(),
            settings = settings,
            status = statusFor(settings, error = null, busy = false),
        )
    }

    private fun statusWhile(messages: List<Message>): String {
        return when (messages.lastOrNull()) {
            is Message.ToolCall -> "Running tool…"
            is Message.ToolResult -> "Waiting for the model…"
            else -> "Waiting for the model…"
        }
    }

    private fun statusFor(settings: ServerSettings, error: String?, busy: Boolean): String {
        if (busy) return "Sending…"
        if (error != null) return "Not connected"
        if (settings.validate() != null) return "Set the server address"
        return "Ready"
    }

    companion object {
        fun openAiProvider(settings: ServerSettings): ModelProvider {
            return OpenAiCompatibleModelProvider(
                OpenAiCompatibleConfig(
                    baseUrl = settings.baseUrl.trim(),
                    model = settings.model.trim(),
                    apiKey = settings.apiKey.trim().ifEmpty { null },
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                    readTimeoutMillis = settings.timeoutSeconds.coerceIn(
                        ServerSettings.MIN_TIMEOUT_SECONDS,
                        ServerSettings.MAX_TIMEOUT_SECONDS,
                    ) * 1000,
                ),
            )
        }

        private const val CONNECT_TIMEOUT_MILLIS = 10_000
    }
}

class ChatViewModelFactory(
    private val app: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val volume = AndroidMediaVolume(app)
        val registry = ToolRegistry().apply {
            register(SetMediaVolumeTool { level -> volume.setPercent(level) })
        }
        return ChatViewModel(
            settingsStore = ServerSettingsStore(app),
            toolRegistry = registry,
            providers = ChatViewModel.Companion::openAiProvider,
        ) as T
    }
}
