package com.localfirst.assistant.client

import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.voice.LiveVoiceConversation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LiveVoiceConversationTest {
    @Test fun enteringVoiceKeepsTextDraftAndReusesHistoryAcrossClients() = runTest {
        val pc = Pc().apply { chat() }
        val first = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 10 })
        first.connect("", "token")!!.join()
        first.select("a"); first.setDraft("Unsent text question")
        first.openLiveVoice()!!.join()
        assertEquals(LiveVoiceConversation.ID, first.state.value.activeId)
        assertEquals(LiveVoiceConversation.TITLE, first.state.value.active!!.conversation.summary.title)
        first.select("a")
        assertEquals("Unsent text question", first.state.value.draft)
        val history = listOf(Message.User("Earlier voice question"), Message.Assistant("Earlier answer"))
        pc.chat(LiveVoiceConversation.ID, LiveVoiceConversation.TITLE, 2, history)
        val second = WorkspaceController(WorkspaceRepository(pc), this, { "other" }, { 20 })
        second.connect("", "token")!!.join(); second.openLiveVoice()!!.join()
        assertEquals(history, second.state.value.active!!.conversation.messages)
        first.openLiveVoice()!!.join()
        assertEquals(history, first.state.value.active!!.conversation.messages)
        assertEquals(2, pc.items.size)
    }

    @Test fun privateVoiceNeverSelectsOrCreatesTheSavedVoiceConversation() = runTest {
        val pc = Pc()
        val vm = WorkspaceController(WorkspaceRepository(pc), this, { "private" }, { 10 })
        vm.connect("", "token")!!.join(); vm.newIncognito(); vm.setDraft("Private draft")
        assertNull(vm.openLiveVoice())
        assertTrue(vm.state.value.incognito)
        assertEquals("Private draft", vm.state.value.draft)
        assertTrue(pc.items.isEmpty())
    }

    @Test fun deletedVoiceConversationStartsFreshAtItsExistingRevision() = runTest {
        val pc = Pc().apply { items[LiveVoiceConversation.ID] = SyncItem(LiveVoiceConversation.ID, 4, deleted = true) }
        val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 10 })
        vm.connect("", "token")!!.join(); vm.openLiveVoice()!!.join()
        assertEquals(5, pc.items.getValue(LiveVoiceConversation.ID).revision)
        assertTrue(vm.state.value.active!!.conversation.messages.isEmpty())
    }

    @Test fun anotherClientCreatingVoiceFirstDoesNotLoseItsMessages() = runTest {
        val pc = Pc()
        val history = listOf(Message.User("Other device's voice question"))
        pc.routes["PUT /workspace/items/${LiveVoiceConversation.ID}"] = {
            pc.chat(LiveVoiceConversation.ID, LiveVoiceConversation.TITLE, 1, history)
            throw WorkspaceException(409, "Already created")
        }
        val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 10 })
        vm.connect("", "token")!!.join(); vm.openLiveVoice()!!.join()
        assertNull(vm.state.value.error)
        assertEquals(history, vm.state.value.active!!.conversation.messages)
    }
}
