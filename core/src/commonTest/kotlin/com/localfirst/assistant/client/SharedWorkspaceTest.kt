package com.localfirst.assistant.client

import com.localfirst.assistant.conversation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

private class Pc : WorkspaceGateway {
    val items = linkedMapOf<String, SyncItem>()
    val calls = mutableListOf<Pair<String, JsonObject?>>()
    val facts = mutableListOf<JsonObject>()
    var wait: CompletableDeferred<Unit>? = null
    var jobError = ""
    var jobStatus = "completed"
    var legacy = false
    /** Record ids returned by each change-cursor reply. */
    val sent = mutableListOf<List<String>>()
    private fun jobs() = buildJsonArray { add(buildJsonObject { put("id", "job"); put("status", jobStatus); put("result", "Done"); put("error", jobError) }) }
    override fun connect(baseUrl: String, token: String) {}
    override fun lock() {}
    override suspend fun request(path: String, method: String, body: JsonObject?): JsonElement {
        calls += path to body
        if (path.startsWith("/workspace/changes?")) {
            if (legacy) throw WorkspaceException(404, "Not found")
            wait?.await()
            val since = com.localfirst.assistant.platform.PlatformNet.decodeUrl(path.substringAfter("since=").substringBefore('&')).split('|')
            val seen = since[0].split(',').filter { it.isNotEmpty() }.associate { it.substringBefore(':') to it.substringAfter(':').toInt() }
            val jobMark = "$jobStatus/$jobError"; val memoryMark = facts.hashCode().toString()
            val moved = items.values.filter { seen[it.id] != it.revision }
            sent += moved.map { it.id }
            return buildJsonObject {
                put("cursor", items.values.joinToString(",") { "${it.id}:${it.revision}" } + "|$jobMark|$memoryMark")
                put("reset", since.size < 3); put("items", wireJson.encodeToJsonElement(moved))
                put("jobs", if (since.getOrNull(1) != jobMark) jobs() else JsonNull); put("memory", since.getOrNull(2) != memoryMark)
            }
        }
        if (path == "/workspace/items") { wait?.await(); return wireJson.encodeToJsonElement(items.values.toList()) }
        if (path == "/workspace/items?revisions=1") return buildJsonArray { items.values.forEach { add(buildJsonObject { put("id", it.id); put("revision", it.revision); put("deleted", it.deleted) }) } }
        if (path.startsWith("/workspace/items?ids=")) return wireJson.encodeToJsonElement(path.substringAfter('=').split(',').mapNotNull { items[it] })
        if (path == "/workspace/memory") return buildJsonObject { put("memories", facts.size); put("settings", buildJsonObject { put("use_memories", true); put("use_history", true) }) }
        if (path.startsWith("/workspace/memory/memories?")) {
            val args = path.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val query = com.localfirst.assistant.platform.PlatformNet.decodeUrl(args["q"].orEmpty())
            return JsonArray(facts.filter { query.isBlank() || it["text"]?.jsonPrimitive?.content.orEmpty().contains(query, true) }.drop(args["offset"]?.toInt() ?: 0).take(args["limit"]?.toInt() ?: 100))
        }
        if (path.startsWith("/workspace/memory/sources?")) return JsonArray(emptyList())
        if (path == "/workspace/memory/memories" && method == "POST") { facts += body!!; return body }
        if (path == "/workspace/memory/settings") return body!!
        if (path == "/workspace/jobs") return jobs()
        if (path.startsWith("/workspace/jobs/") && method == "POST") {
            jobStatus = when(path.substringAfterLast('/')) { "resume" -> "scheduled"; "pause" -> "paused"; else -> "cancelled" }
            return buildJsonObject { put("status", jobStatus) }
        }
        if (path.startsWith("/workspace/items/") && method in listOf("PUT", "POST")) {
            val id = path.removePrefix("/workspace/items/").substringBefore('/')
            val revision = items[id]?.revision ?: 0
            if (body!!.getValue("revision").jsonPrimitive.int != revision) throw WorkspaceException(409, "Changed on another client")
            val item = SyncItem(id, revision + 1, body.getValue("payload").jsonObject, body["deleted"]?.jsonPrimitive?.booleanOrNull ?: false)
            items[id] = item
            return if (path.endsWith("/continue")) buildJsonObject { put("id", "job"); put("revision", item.revision) } else wireJson.encodeToJsonElement(item)
        }
        throw IllegalStateException("Unexpected route $path")
    }
    fun chat(id: String = "a", title: String = "Original", revision: Int = 1, messages: List<Message> = listOf(Message.User("Hello"))) {
        val c = StoredConversation(ConversationSummary(id, title, 1, 2), messages)
        items[id] = SyncItem(id, revision, buildJsonObject { put("chat", encodeConversation(c)) })
    }
}
@OptIn(ExperimentalCoroutinesApi::class)
class SharedWorkspaceTest {
    @Test fun canonicalWirePreservesToolsAttachmentsAndUnicode() {
        val c = StoredConversation(ConversationSummary("id", "Quote \" and é", 1, 2, "project"), listOf(
            Message.User("line 1\nline 2", listOf(Attachment("asset", AttachmentKind.IMAGE, "image.jpg", "image/jpeg", path = "remote:abc"))),
            Message.ToolCall("call", "web_search", "{}"), Message.ToolResult("call", "web_search", "result", true), Message.Assistant("Answer")))
        assertEquals(c, decodeConversation(encodeConversation(c)))
    }
    @Test fun refreshDownloadsOnlyChangedRecords() = runTest {
        val pc = Pc().apply { chat("a"); chat("b", "Second") }; val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 })
        vm.connect("", "token")!!.join()
        assertEquals(listOf("a", "b"), pc.sent.last())
        pc.calls.clear(); vm.refresh()!!.join()
        // Nothing moved: one small request, and no task or memory downloads.
        assertEquals(emptyList(), pc.sent.last()); assertTrue(pc.calls.single().first.startsWith("/workspace/changes?"))
        pc.chat("b", "Renamed elsewhere", 2); pc.chat("c", "Third"); pc.items["a"] = SyncItem("a", 2, deleted = true)
        vm.refresh()!!.join()
        assertEquals(listOf("a", "b", "c"), pc.sent.last())
        assertEquals(setOf("Renamed elsewhere", "Third"), vm.state.value.chats.map { it.conversation.summary.title }.toSet())
        pc.facts += buildJsonObject { put("id", "m"); put("text", "Saved elsewhere") }
        pc.calls.clear(); vm.refresh()!!.join()
        assertEquals(emptyList(), pc.sent.last()); assertTrue(pc.calls.any { it.first.startsWith("/workspace/memory/memories?") })
        assertEquals("Saved elsewhere", vm.state.value.memories.single()["text"]!!.jsonPrimitive.content)
    }
    @Test fun gatewayWithoutChangeCursorsStillRefreshesIncrementally() = runTest {
        val pc = Pc().apply { legacy = true; chat("a"); chat("b", "Second") }; val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 })
        vm.connect("", "token")!!.join()
        fun itemCalls() = pc.calls.map { it.first }.filter { it.startsWith("/workspace/items") }
        assertEquals(listOf("/workspace/items"), itemCalls())
        pc.chat("b", "Renamed elsewhere", 2); pc.calls.clear(); vm.refresh()!!.join()
        assertEquals(listOf("/workspace/items?revisions=1", "/workspace/items?ids=b"), itemCalls())
        assertTrue(vm.state.value.chats.any { it.conversation.summary.title == "Renamed elsewhere" })
    }
    @Test fun heldRefreshNeitherBlocksNorDropsActions() = runTest {
        val pc = Pc().apply { chat() }; val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 })
        vm.connect("", "token")!!.join(); vm.select("a"); vm.setDraft("Sent while refresh is held")
        pc.wait = CompletableDeferred(); vm.startPolling { true }; advanceTimeBy(2000); runCurrent()
        assertFalse(vm.state.value.busy); assertTrue(vm.state.value.canSend)
        pc.wait = null
        assertNotNull(vm.send()).join()
        assertTrue(pc.calls.any { it.first == "/workspace/items/a/continue" }); assertEquals("", vm.state.value.draft)
        pc.chat(title = "Changed on the phone", revision = 3); advanceTimeBy(3000); runCurrent()
        assertEquals("Changed on the phone", vm.state.value.chats.single().conversation.summary.title)
        vm.close()
    }
    @Test fun sidebarDeletesTheChosenChatNotTheOpenOne() = runTest {
        val pc = Pc().apply { chat("a"); chat("b", "Second") }; val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 })
        vm.connect("", "token")!!.join(); vm.select("a")
        vm.delete("b")!!.join()
        assertEquals(listOf("a"), vm.state.value.chats.map { it.item.id }); assertEquals("a", vm.state.value.activeId)
        assertTrue(pc.items.getValue("b").deleted)
    }
    @Test fun refreshAndRemoteDeletionPreserveUnsentDraft() = runTest {
        val pc = Pc().apply { chat() }; val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 })
        vm.connect("", "token")!!.join(); vm.select("a"); vm.setDraft("Keep this")
        pc.chat(title = "Renamed on Android", revision = 2); vm.refresh()!!.join()
        assertEquals("Renamed on Android", vm.state.value.active!!.conversation.summary.title); assertEquals("Keep this", vm.state.value.draft)
        pc.items["a"] = SyncItem("a", 3, deleted = true); vm.refresh()!!.join()
        assertNull(vm.state.value.active); assertEquals("Keep this", vm.state.value.draft)
    }
    @Test fun staleSendRetainsDraftAndRemoteHistory() = runTest {
        val pc = Pc().apply { chat() }; val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 })
        vm.connect("", "token")!!.join(); vm.select("a"); vm.setDraft("My pending message")
        pc.chat(title = "Remote edit", revision = 2); vm.send()!!.join()
        assertEquals("My pending message", vm.state.value.draft); assertNull(vm.state.value.activeJob)
        assertTrue(vm.state.value.error!!.contains("another client")); assertEquals(2, pc.items.getValue("a").revision)
    }
    @Test fun clientsSharePreferencesWithoutOverwritingOtherFieldsOrSendingToken() = runTest {
        val pc = Pc(); val first = WorkspaceController(WorkspaceRepository(pc), this, { "first" }, { 5 }); val second = WorkspaceController(WorkspaceRepository(pc), this, { "second" }, { 5 })
        first.connect("", "private-token")!!.join(); second.connect("", "private-token")!!.join()
        first.preference("theme", JsonPrimitive("LIGHT"))!!.join(); second.preference("display_name", JsonPrimitive("Alex"))!!.join(); first.refresh()!!.join()
        assertEquals("LIGHT", first.state.value.preferences.theme.name); assertEquals("Alex", first.state.value.preferences.displayName)
        assertEquals(setOf("preferences-theme", "preferences-display_name"), pc.items.keys)
        assertFalse(pc.items.values.toString().contains("private-token"))
    }
    @Test fun editingAgainstCapturedPreferenceRevisionStillConflictsAfterRefresh() = runTest {
        val pc = Pc(); val vm = WorkspaceController(WorkspaceRepository(pc), this, { "id" }, { 5 }); vm.connect("", "token")!!.join()
        vm.preference("display_name", JsonPrimitive("Remote name"))!!.join()
        vm.preference("display_name", JsonPrimitive("Stale local name"), expectedRevision = 0)!!.join()
        assertNotNull(vm.state.value.error); assertEquals("Remote name", vm.state.value.preferences.displayName)
    }
    @Test fun renamePreservesFullAndroidTranscript() = runTest {
        val messages = listOf(Message.User("Hello"), Message.ToolCall("c", "search", "{}"), Message.ToolResult("c", "search", "source", true))
        val pc = Pc().apply { chat(messages = messages) }; val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 })
        vm.connect("", "token")!!.join(); vm.select("a"); vm.rename("Renamed")!!.join()
        assertEquals(messages, decodeConversation(pc.items.getValue("a").payload.getValue("chat").jsonPrimitive.content).messages)
    }
    @Test fun lockPreventsLateSnapshotFromRestoringPersonalData() = runTest {
        val pc = Pc().apply { chat(); wait = CompletableDeferred() }; val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 })
        vm.connect("", "token"); runCurrent(); vm.lock(); pc.wait!!.complete(Unit); runCurrent()
        assertEquals(WorkspaceState(), vm.state.value)
    }
    @Test fun memoryPagingSurvivesRefreshAndUsesCanonicalEndpoints() = runTest {
        val pc = Pc(); repeat(350) { pc.facts += buildJsonObject { put("id", "$it"); put("text", "Fact $it") } }
        val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 }); vm.connect("", "token")!!.join()
        vm.moreMemories()!!.join(); vm.moreMemories()!!.join(); assertEquals(300, vm.state.value.memories.size)
        vm.refresh()!!.join(); assertEquals(300, vm.state.value.memories.size)
        vm.memorySetting("use_history", false)!!.join(); assertTrue(pc.calls.any { it.first == "/workspace/memory/settings" && it.second?.get("use_history")?.jsonPrimitive?.booleanOrNull == false })
    }
    @Test fun legacyImportIsExplicitIdempotentAndPreservesEscapes() = runTest {
        val pc = Pc(); val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 }); vm.connect("", "token")!!.join()
        val raw = """[{"id":"old","title":"Quote \" é","messages":[{"role":"user","content":"first\nsecond"}]}]"""
        val old = decodeLegacyChats(raw); assertTrue(pc.items.isEmpty())
        vm.importLegacy(old) { "stable" }!!.join(); vm.importLegacy(old) { "stable" }!!.join()
        assertEquals(1, pc.items.size)
        val c = decodeConversation(pc.items.getValue("stable").payload.getValue("chat").jsonPrimitive.content)
        assertEquals("Quote \" é", c.summary.title); assertEquals(Message.User("first\nsecond"), c.messages.single())
    }
    @Test fun projectEditsUseCapturedKnowledgeRevision() = runTest {
        val pc = Pc(); val vm = WorkspaceController(WorkspaceRepository(pc), this, { "project" }, { 5 }); vm.connect("", "token")!!.join(); val baseline = vm.state.value
        vm.saveProject(null, "Remote project", "Remote instructions", "")!!.join()
        vm.saveProject(null, "Stale project", "Old instructions", "", baseline)!!.join()
        assertNotNull(vm.state.value.error); assertEquals("Remote project", vm.state.value.knowledge.projects.single().name)
    }    @Test fun reconnectResumesAutomaticRefresh() = runTest {
        val pc = Pc().apply { chat() }; val vm = WorkspaceController(WorkspaceRepository(pc), this, { "id" }, { 5 })
        vm.startPolling { true }; vm.connect("", "token")!!.join(); vm.lock()
        vm.connect("", "token")!!.join(); pc.chat(title = "Changed after reconnect", revision = 2)
        advanceTimeBy(5000); runCurrent()
        assertEquals("Changed after reconnect", vm.state.value.chats.single().conversation.summary.title)
        vm.close()
    }
    @Test fun preservedTaskReplyDoesNotReplaceConcurrentHistoryEdits() = runTest {
        val pc = Pc().apply { chat() }; val vm = WorkspaceController(WorkspaceRepository(pc), this, { "id" }, { 5 })
        vm.connect("", "token")!!.join(); vm.select("a"); vm.setDraft("My message"); vm.send()!!.join()
        pc.chat(title = "Remote edited chat", revision = 3); pc.jobError = "Chat changed; result saved as a task"
        vm.refresh()!!.join()
        assertEquals("Remote edited chat", vm.state.value.active!!.conversation.summary.title)
        assertEquals("Done", vm.state.value.detachedReply); assertNull(vm.state.value.activeJob)
        assertEquals("Done", vm.state.value.tasks.single()["result"]?.jsonPrimitive?.content)
    }
    @Test fun taskResumeKeepsTheExistingConversationAndCheckpoint() = runTest {
        val pc = Pc().apply { chat(); jobStatus = "failed" }; val vm = WorkspaceController(WorkspaceRepository(pc), this, { "id" }, { 5 })
        vm.connect("", "token")!!.join(); vm.taskAction("job", "resume")!!.join()
        assertEquals(1, pc.items.getValue("a").revision)
        assertTrue(pc.calls.any { it.first == "/workspace/jobs/job/resume" })
        assertEquals("scheduled", vm.state.value.tasks.single()["status"]?.jsonPrimitive?.content)
    }
    @Test fun memorySearchRetainsItsQueryAcrossPagingAndRefresh() = runTest {
        val pc = Pc().apply { repeat(250) { facts += buildJsonObject { put("id", "$it"); put("text", if (it < 120) "Needle é" else "Other") } } }
        val vm = WorkspaceController(WorkspaceRepository(pc), this, { "new" }, { 5 })
        vm.connect("", "token")!!.join(); vm.searchMemory("Needle é")!!.join()
        assertEquals(100, vm.state.value.memories.size); assertTrue(vm.state.value.memoryHasMore)
        vm.moreMemories()!!.join(); assertEquals(120, vm.state.value.memories.size); assertFalse(vm.state.value.memoryHasMore)
        vm.refresh()!!.join(); assertEquals(120, vm.state.value.memories.size)
        assertTrue(vm.state.value.memories.all { it["text"]?.jsonPrimitive?.content == "Needle é" })
    }

}
