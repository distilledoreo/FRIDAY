package com.localfirst.assistant.client

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SavedSignInTest {
    private class Memory(var saved: Pair<String, String>? = null) : SavedSignIn {
        override fun load() = saved
        override fun save(baseUrl: String, token: String) { saved = baseUrl to token }
        override fun clear() { saved = null }
    }

    @Test fun rememberedSignInReconnectsUntilLock() = runTest {
        val store = Memory()
        val vm = WorkspaceController(WorkspaceRepository(Pc()), this, { "new" }, { 5 }, savedSignIn = store)
        vm.connect("", "token", remember = true)!!.join()
        assertEquals("" to "token", store.saved)
        vm.lock()  // Leaving the page: the token leaves memory, the saved sign-in stays.
        assertFalse(vm.state.value.connected)
        vm.resumeSavedSignIn()!!.join()
        assertTrue(vm.state.value.connected)
        vm.signOut()
        assertNull(store.saved)
        assertNull(vm.resumeSavedSignIn())
    }

    @Test fun connectingWithoutRememberForgetsAnOlderSignIn() = runTest {
        val store = Memory("" to "old")
        val vm = WorkspaceController(WorkspaceRepository(Pc()), this, { "new" }, { 5 }, savedSignIn = store)
        vm.connect("", "token")!!.join()
        assertNull(store.saved)
    }

    @Test fun aRefusedSavedTokenIsForgotten() = runTest {
        val store = Memory("" to "revoked")
        val pc = Pc().apply { routes["GET /workspace/changes"] = { error("The computer did not accept this request. Check your token and access settings.") } }
        val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 }, savedSignIn = store)
        vm.resumeSavedSignIn()!!.join()
        assertFalse(vm.state.value.connected)
        assertNull(store.saved)
    }

    private class Drafts : DraftRecovery {
        val saved = mutableMapOf<String, String>()
        override fun load(key: String) = saved[key]
        override fun save(key: String, text: String?) { if (text == null) saved.remove(key) else saved[key] = text }
        override fun clear() { saved.clear() }
    }

    @Test fun anUnsentDraftSurvivesAReloadWhileTheSignInIsRemembered() = runTest {
        val store = Memory(); val drafts = Drafts()
        val vm = WorkspaceController(WorkspaceRepository(Pc()), this, { "new" }, { 5 }, savedSignIn = store, draftRecovery = drafts)
        vm.connect("", "token", remember = true)!!.join()
        vm.setDraft("QA UNSENT DRAFT 731")
        assertEquals(mapOf("new:" to "QA UNSENT DRAFT 731"), drafts.saved)
        // A reload: a new page (a new controller) reconnects with the remembered sign-in.
        val reloaded = WorkspaceController(WorkspaceRepository(Pc()), this, { "new" }, { 5 }, savedSignIn = store, draftRecovery = drafts)
        reloaded.resumeSavedSignIn()!!.join()
        assertEquals("QA UNSENT DRAFT 731", reloaded.state.value.draft)
        reloaded.signOut()
        assertTrue(drafts.saved.isEmpty())
    }

    @Test fun withoutARememberedSignInTheHostStillKeepsTheDraftForTheTab() = runTest {
        // The browser keeps it in session storage then: through a reload, gone when the tab closes.
        val drafts = Drafts()
        val vm = WorkspaceController(WorkspaceRepository(Pc()), this, { "new" }, { 5 }, savedSignIn = Memory(), draftRecovery = drafts)
        vm.connect("", "token")!!.join()
        vm.setDraft("Shared computer")
        assertEquals(mapOf("new:" to "Shared computer"), drafts.saved)
        vm.signOut()
        assertTrue(drafts.saved.isEmpty())
    }
}
