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
}
