package com.localfirst.assistant.ui

import android.app.Application
import android.net.Uri
import com.localfirst.assistant.attachments.AttachmentImporter
import com.localfirst.assistant.conversation.Attachment
import com.localfirst.assistant.conversation.AttachmentKind
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.localfirst.assistant.AssistantPrompts
import com.localfirst.assistant.conversation.ConversationSession
import com.localfirst.assistant.conversation.ConversationStore
import com.localfirst.assistant.conversation.ConversationSummary
import com.localfirst.assistant.conversation.ConversationTitles
import com.localfirst.assistant.conversation.FileConversationStore
import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.conversation.StoredConversation
import com.localfirst.assistant.conversation.TurnOutcome
import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.OpenAiCompatibleConfig
import com.localfirst.assistant.model.OpenAiCompatibleModelProvider
import com.localfirst.assistant.search.HttpSearchService
import com.localfirst.assistant.search.SearchServiceConfig
import com.localfirst.assistant.settings.ServerSettings
import com.localfirst.assistant.settings.ServerSettingsStore
import com.localfirst.assistant.phone.AndroidPhoneActions
import com.localfirst.assistant.tools.AndroidMediaVolume
import com.localfirst.assistant.tools.SetMediaVolumeTool
import com.localfirst.assistant.tools.ToolConfirmer
import com.localfirst.assistant.tools.ToolRegistry
import com.localfirst.assistant.tools.WebSearchTool
import com.localfirst.assistant.tools.phone.phoneTools
import com.localfirst.assistant.voice.VoiceController
import com.localfirst.assistant.voice.VoiceEngines
import com.localfirst.assistant.voice.VoiceUiState
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    /** Null until the first message of a new chat is saved. */
    val conversationId: String? = null,
    val title: String = ConversationTitles.NEW_CHAT,
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    /** Index of the user message being edited, if any. */
    val editingIndex: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val conversations: List<ConversationSummary> = emptyList(),
    val settings: ServerSettings = ServerSettings(),
    val showSettings: Boolean = false,
    val settingsError: String? = null,
    /** A tool call waiting for the user to approve or deny it. */
    val pendingApproval: PendingApproval? = null,
    /** Files attached to the message being written. */
    val draftAttachments: List<DraftAttachment> = emptyList(),
) {
    val canSend: Boolean get() {
        val editingAttachments = (messages.getOrNull(editingIndex ?: -1) as? Message.User)?.attachments.orEmpty()
        return !busy && draftAttachments.all { it.status == DraftStatus.READY && it.attachment != null } &&
            (draft.isNotBlank() || draftAttachments.isNotEmpty() || editingAttachments.isNotEmpty())
    }
}

enum class DraftStatus { READING, READY, FAILED }

data class DraftAttachment(
    val id: String,
    val name: String,
    val kind: AttachmentKind,
    val status: DraftStatus,
    val attachment: Attachment? = null,
    val error: String? = null,
)

data class PendingApproval(val toolName: String, val prompt: String)

class ChatViewModel(
    private val settingsStore: ServerSettingsStore,
    private val conversationStore: ConversationStore,
    private val toolRegistry: ToolRegistry,
    private val providers: (ServerSettings) -> ModelProvider,
    private val onSettingsSaved: (ServerSettings) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val voiceEngines: VoiceEngines? = null,
    private val attachments: AttachmentImporter? = null,
) : ViewModel() {
    private var approval: CompletableDeferred<Boolean>? = null

    /** Suspends the turn until the user answers the approval card. Stop cancels it. */
    private val confirmer = ToolConfirmer { request ->
        val answer = CompletableDeferred<Boolean>()
        approval = answer
        _state.update { it.copy(pendingApproval = PendingApproval(request.toolName, request.prompt)) }
        try {
            answer.await()
        } finally {
            approval = null
            _state.update { it.copy(pendingApproval = null) }
        }
    }

    private var provider: ModelProvider = providers(settingsStore.load())
    private var session = newSession(emptyList())
    private var turnJob: Job? = null
    private var createdAt: Long = 0
    private val _state = MutableStateFlow(ChatUiState(settings = settingsStore.load()))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val voice = voiceEngines?.let { engines ->
        VoiceController(
            scope = viewModelScope,
            selectIo = { engines.select(_state.value.settings) },
            chat = state,
            submit = ::submitSpoken,
            resubmit = ::resubmitSpoken,
            stopTurn = ::stop,
            answerApproval = ::answerApproval,
            bargeInEnabled = { _state.value.settings.voiceBargeIn },
        )
    }

    /** Null when voice mode is closed. */
    val voiceState: StateFlow<VoiceUiState?> = voice?.state ?: MutableStateFlow(null)

    init {
        refreshConversationList()
    }

    fun onDraftChange(value: String) {
        _state.update { it.copy(draft = value) }
    }

    // ---- sending -------------------------------------------------------------

    fun send() {
        val current = _state.value
        val draft = current.draft
        val ready = current.draftAttachments.mapNotNull { it.attachment }
        if (!current.canSend) return
        if (!settingsReady()) return
        val editing = current.editingIndex
        _state.update { it.copy(draft = "", editingIndex = null, draftAttachments = emptyList()) }
        if (editing != null) {
            runTurn { onUpdate -> session.editUserMessage(editing, draft, onUpdate) }
        } else {
            runTurn { onUpdate -> session.submitUserMessage(draft, ready, onUpdate) }
        }
    }

    // ---- attachments -----------------------------------------------------------

    /** Adds picked or shared files: images are attached directly, documents are read on the computer. */
    fun addAttachments(uris: List<Uri>, deleteSource: () -> Unit = {}) {
        val importer = attachments
        val current = _state.value
        val remaining = (MAX_ATTACHMENTS - current.draftAttachments.size).coerceAtLeast(0)
        if (importer == null || current.busy || current.editingIndex != null || remaining == 0 || uris.isEmpty()) {
            deleteSource()
            return
        }
        if (uris.size > remaining) _state.update { it.copy(error = "You can attach up to $MAX_ATTACHMENTS files per message.") }
        for (uri in uris.take(remaining)) {
            val image = importer.isImage(uri)
            val draft = DraftAttachment(
                id = UUID.randomUUID().toString(),
                name = importer.displayName(uri),
                kind = if (image) AttachmentKind.IMAGE else AttachmentKind.DOCUMENT,
                status = DraftStatus.READING,
            )
            _state.update { it.copy(draftAttachments = it.draftAttachments + draft) }
            viewModelScope.launch {
                val settings = _state.value.settings
                val result = try {
                    runCatching {
                        if (image) {
                            importer.importImage(uri, draft.name)
                        } else {
                            importer.importDocument(uri, settings.searchBaseUrl, settings.searchApiKey.trim().ifEmpty { null })
                        }
                    }.also { result ->
                        val error = result.exceptionOrNull()
                        if (error is CancellationException) throw error
                    }
                } finally {
                    deleteSource()
                }
                _state.update { state ->
                    state.copy(
                        draftAttachments = state.draftAttachments.map { d ->
                            if (d.id != draft.id) {
                                d
                            } else {
                                result.fold(
                                    { d.copy(status = DraftStatus.READY, attachment = it) },
                                    { e -> d.copy(status = DraftStatus.FAILED, error = e.message ?: "Couldn't attach ${d.name}.") },
                                )
                            }
                        },
                    )
                }
                // Removed while it was being read: drop the stored files.
                if (_state.value.draftAttachments.none { it.id == draft.id }) result.getOrNull()?.let { importer.delete(listOf(it)) }
            }
        }
    }

    fun removeDraftAttachment(id: String) {
        val removed = _state.value.draftAttachments.firstOrNull { it.id == id } ?: return
        _state.update { s -> s.copy(draftAttachments = s.draftAttachments.filterNot { it.id == id }) }
        removed.attachment?.let { attachments?.delete(listOf(it)) }
    }

    /** Content shared from another app: a new chat with it attached. */
    fun receiveShared(uris: List<Uri>, text: String?) {
        viewModelScope.launch {
            voice?.close()
            turnJob?.cancel()
            turnJob?.join()
            resetToNewChat()
            text?.takeIf { it.isNotBlank() }?.let { shared -> _state.update { it.copy(draft = shared) } }
            addAttachments(uris)
        }
    }

    /** Sends [text] directly, for suggestion chips on an empty chat. */
    fun sendSuggestion(text: String) {
        if (_state.value.busy) return
        onDraftChange(text)
        send()
    }

    fun retry() {
        if (_state.value.busy || !settingsReady()) return
        runTurn { onUpdate -> session.retry(onUpdate) }
    }

    fun regenerate() {
        if (_state.value.busy || !settingsReady()) return
        runTurn { onUpdate -> session.regenerate(onUpdate) }
    }

    /** Stops the reply in progress. Text received so far is kept. */
    fun stop() {
        turnJob?.cancel()
    }

    fun answerApproval(approved: Boolean) {
        approval?.complete(approved)
    }

    // ---- voice -----------------------------------------------------------------

    fun startVoice() {
        if (!settingsReady()) return
        voice?.start()
    }

    fun closeVoice() = voice?.close()

    /** Called when the app goes to the background, where Android blocks the mic. */
    fun pauseVoice() {
        if (voice?.active == true) voice.pause("Paused while the app was in the background. Tap to talk.")
    }

    fun interruptVoice() = voice?.interrupt()

    /** Opened as the phone's assistant (long-press power, headset button): a fresh chat in voice mode. */
    fun startAssistantSession() {
        viewModelScope.launch {
            voice?.close()
            turnJob?.cancel()
            turnJob?.join()
            if (_state.value.messages.isNotEmpty()) resetToNewChat()
            startVoice()
        }
    }

    private fun submitSpoken(text: String): Boolean {
        if (_state.value.busy || text.isBlank() || _state.value.settings.validate() != null) return false
        runTurn { onUpdate -> session.submitUserMessage(text, onUpdate = onUpdate) }
        return true
    }

    /**
     * Replaces the last user message with [text] and asks again, for a spoken
     * message that turned out to continue after a pause. Not when a tool already
     * ran for it, so actions never repeat.
     */
    private fun resubmitSpoken(text: String): Boolean {
        if (_state.value.busy || text.isBlank() || _state.value.settings.validate() != null) return false
        val messages = session.snapshot()
        val lastUser = messages.indexOfLast { it is Message.User }
        if (lastUser < 0 || messages.drop(lastUser + 1).any { it is Message.ToolCall }) return false
        runTurn { onUpdate -> session.editUserMessage(lastUser, text, onUpdate) }
        return true
    }

    override fun onCleared() {
        clearDraftAttachments()
        voice?.shutdown()
        super.onCleared()
    }

    fun startEditing(index: Int) {
        val message = _state.value.messages.getOrNull(index) as? Message.User ?: return
        if (_state.value.busy) return
        clearDraftAttachments()
        _state.update { it.copy(editingIndex = index, draft = message.content) }
    }

    fun cancelEditing() {
        _state.update { it.copy(editingIndex = null, draft = "") }
    }

    private fun runTurn(block: suspend (onUpdate: (List<Message>) -> Unit) -> TurnOutcome) {
        val isFirstExchange = session.snapshot().none { it is Message.Assistant }
        val now = ZonedDateTime.now()
        session.systemPrompt = AssistantPrompts.system(now)
        session.latestUserNote = AssistantPrompts.timeNote(now)
        _state.update { it.copy(busy = true, error = null) }
        turnJob = viewModelScope.launch {
            var outcome: TurnOutcome? = null
            try {
                outcome = block { messages ->
                    _state.update { it.copy(messages = messages) }
                }
            } catch (e: CancellationException) {
                // Stopped by the user; the session already kept the partial answer.
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Something went wrong.") }
            } finally {
                val messages = session.snapshot()
                _state.update {
                    it.copy(
                        messages = messages,
                        busy = false,
                        error = (outcome as? TurnOutcome.Failed)?.error ?: it.error,
                    )
                }
                turnJob = null
            }
            // Save even when the user pressed Stop; this job is cancelled at that point.
            withContext(NonCancellable) { persist() }
            if (isFirstExchange && outcome is TurnOutcome.Completed) generateTitle()
        }
    }

    private fun settingsReady(): Boolean {
        val error = _state.value.settings.validate() ?: return true
        _state.update { it.copy(error = error, showSettings = true, settingsError = error) }
        return false
    }

    // ---- conversations -------------------------------------------------------

    fun newChat() {
        switchTo(null)
    }

    fun openConversation(id: String) {
        if (id == _state.value.conversationId) return
        switchTo(id)
    }

    fun renameConversation(id: String, title: String) {
        val clean = title.trim().take(ConversationTitles.MAX_LENGTH * 2)
        if (clean.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { conversationStore.rename(id, clean) }
            if (_state.value.conversationId == id) _state.update { it.copy(title = clean) }
            refreshConversationList()
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            if (_state.value.conversationId == id) {
                turnJob?.cancel()
                turnJob?.join()
                resetToNewChat()
            }
            withContext(Dispatchers.IO) {
                val files = conversationStore.load(id)?.messages.orEmpty()
                    .filterIsInstance<Message.User>().flatMap { it.attachments }
                conversationStore.delete(id)
                attachments?.delete(files)
            }
            refreshConversationList()
        }
    }

    private fun switchTo(id: String?) {
        viewModelScope.launch {
            turnJob?.cancel()
            turnJob?.join()
            if (id == null) {
                resetToNewChat()
                return@launch
            }
            val stored = withContext(Dispatchers.IO) { conversationStore.load(id) }
            if (stored == null) {
                refreshConversationList()
                return@launch
            }
            clearDraftAttachments()
            session = newSession(stored.messages)
            createdAt = stored.summary.createdAt
            _state.update {
                it.copy(
                    conversationId = id,
                    title = stored.summary.title,
                    messages = stored.messages,
                    draft = "",
                    editingIndex = null,
                    error = null,
                )
            }
        }
    }

    private fun clearDraftAttachments() {
        val old = _state.value.draftAttachments.mapNotNull { it.attachment }
        _state.update { it.copy(draftAttachments = emptyList()) }
        attachments?.delete(old)
    }

    private fun resetToNewChat() {
        clearDraftAttachments()
        session = newSession(emptyList())
        _state.update {
            it.copy(
                conversationId = null,
                title = ConversationTitles.NEW_CHAT,
                messages = emptyList(),
                draft = "",
                editingIndex = null,
                error = null,
                draftAttachments = emptyList(),
            )
        }
    }

    private suspend fun persist() {
        val messages = session.snapshot()
        if (messages.isEmpty()) return
        val now = clock()
        val current = _state.value
        val id = current.conversationId ?: UUID.randomUUID().toString().also { createdAt = now }
        val title = if (current.conversationId == null) {
            ConversationTitles.fromFirstMessage(messages.first { it is Message.User } as Message.User)
        } else {
            current.title
        }
        val summary = ConversationSummary(id = id, title = title, createdAt = createdAt, updatedAt = now)
        withContext(Dispatchers.IO) { conversationStore.save(StoredConversation(summary, messages)) }
        _state.update { if (it.conversationId == null || it.conversationId == id) it.copy(conversationId = id, title = title) else it }
        refreshConversationList()
    }

    private suspend fun generateTitle() {
        val id = _state.value.conversationId ?: return
        val messages = session.snapshot()
        val firstUser = messages.firstOrNull { it is Message.User } as? Message.User ?: return
        val firstAnswer = messages.firstOrNull { it is Message.Assistant && it.content.isNotBlank() } as? Message.Assistant
        val title = ConversationTitles.generate(provider, firstUser.content, firstAnswer?.content.orEmpty()) ?: return
        withContext(Dispatchers.IO) { conversationStore.rename(id, title) }
        if (_state.value.conversationId == id) _state.update { it.copy(title = title) }
        refreshConversationList()
    }

    private fun refreshConversationList() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { conversationStore.list() }
            _state.update { it.copy(conversations = list) }
        }
    }

    private fun newSession(messages: List<Message>) = ConversationSession(
        modelProvider = provider,
        toolRegistry = toolRegistry,
        systemPrompt = AssistantPrompts.system(ZonedDateTime.now()),
        initialMessages = messages,
        confirmer = confirmer,
    )

    // ---- settings ------------------------------------------------------------

    fun openSettings() {
        _state.update { it.copy(showSettings = true, settings = settingsStore.load(), settingsError = null) }
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
            searchBaseUrl = settings.searchBaseUrl.trim(),
            searchApiKey = settings.searchApiKey.trim(),
        )
        settingsStore.save(normalized)
        provider = providers(normalized)
        session.modelProvider = provider
        onSettingsSaved(normalized)
        _state.update { it.copy(settings = normalized, showSettings = false, settingsError = null, error = null) }
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
        const val MAX_ATTACHMENTS = 6
    }
}

class ChatViewModelFactory(
    private val app: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val settingsStore = ServerSettingsStore(app)
        val volume = AndroidMediaVolume(app)
        val search = HttpSearchService(settingsStore.load().toSearchConfig())
        val registry = ToolRegistry().apply {
            register(SetMediaVolumeTool { level -> volume.setPercent(level) })
            register(WebSearchTool(search))
            phoneTools(AndroidPhoneActions(app)).forEach(::register)
        }
        return ChatViewModel(
            settingsStore = settingsStore,
            conversationStore = FileConversationStore(File(app.filesDir, "conversations")),
            toolRegistry = registry,
            providers = ChatViewModel.Companion::openAiProvider,
            onSettingsSaved = { saved -> search.config = saved.toSearchConfig() },
            voiceEngines = VoiceEngines(app),
            attachments = AttachmentImporter(app),
        ) as T
    }
}

internal fun ServerSettings.toSearchConfig(): SearchServiceConfig {
    return SearchServiceConfig(
        baseUrl = searchBaseUrl.trim(),
        apiKey = searchApiKey.trim().ifEmpty { null },
        connectTimeoutMillis = 10_000,
        readTimeoutMillis = 60_000,
    )
}
