package com.localfirst.assistant.web.state

import com.localfirst.assistant.web.data.ChatClient
import com.localfirst.assistant.web.data.ChatMessage
import com.localfirst.assistant.web.data.Conversation
import com.localfirst.assistant.web.data.ConversationStore
import com.localfirst.assistant.web.data.ServerSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Drives the chat screen: conversations, the active thread, sending, and streaming state. */
class ChatViewModel(
    private val store: ConversationStore,
    private val settingsStore: ServerSettingsStore,
    private val scope: CoroutineScope,
) {
    private val _conversations = MutableStateFlow(store.load())
    val conversations = _conversations.asStateFlow()

    private val _activeId = MutableStateFlow(_conversations.value.firstOrNull()?.id)
    val activeId = _activeId.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft = _draft.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val active: Conversation? get() = _conversations.value.firstOrNull { it.id == _activeId.value }

    fun setDraft(text: String) { _draft.value = text }

    fun newChat() {
        val id = "c" + (js("Date.now()").unsafeCast<Double>().toLong()).toString(36)
        _conversations.value = listOf(Conversation(id, "New chat", emptyList())) + _conversations.value
        _activeId.value = id
        _error.value = null
    }

    fun select(id: String) { _activeId.value = id; _error.value = null }

    fun delete(id: String) {
        _conversations.value = _conversations.value.filterNot { it.id == id }
        if (_activeId.value == id) _activeId.value = _conversations.value.firstOrNull()?.id
        persist()
    }

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty() || _busy.value) return
        val settings = settingsStore.state.value
        if (!settings.configured) { _error.value = "Add a model server in Settings first."; return }
        var id = _activeId.value
        if (id == null) { newChat(); id = _activeId.value!! }
        val current = _conversations.value.first { it.id == id }
        val userMsg = ChatMessage("user", text)
        val updated = current.messages + userMsg
        val title = if (current.messages.isEmpty()) text.take(40) else current.title
        _conversations.value = _conversations.value.map { if (it.id == id) Conversation(id, title, updated) else it }
        _draft.value = ""
        persist()
        _busy.value = true
        _error.value = null
        scope.launch {
            try {
                val reply = ChatClient(settings).complete(updated.map { it.toMessage() })
                val withReply = updated + ChatMessage("assistant", reply)
                _conversations.value = _conversations.value.map { if (it.id == id) Conversation(id, title, withReply) else it }
                persist()
            } catch (e: Throwable) {
                _error.value = e.message ?: "The model request failed."
            } finally {
                _busy.value = false
            }
        }
    }

    private fun persist() { store.save(_conversations.value) }
}
