package com.localfirst.assistant.client

import com.localfirst.assistant.backup.*
import com.localfirst.assistant.conversation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatActionsTest {
    private val conversation = listOf(
        Message.User("First", listOf(Attachment("a", AttachmentKind.IMAGE, "photo.jpg", "image/jpeg", path = "remote:0123456789abcdef0123456789abcdef"))),
        Message.Assistant("One"), Message.User("Second"), Message.ToolCall("c", "web_search", "{}"), Message.ToolResult("c", "web_search", "{}", true), Message.Assistant("Two"),
    )
    private fun chatOf(pc: Pc) = decodeConversation(pc.items.getValue("a").payload.getValue("chat").jsonPrimitive.content)
    private fun TestScope.connected(pc: Pc, ids: () -> String = { "new" }, saved: MutableList<Triple<String, String, ByteArray>> = mutableListOf()) =
        WorkspaceController(WorkspaceRepository(pc), this, ids, { 5 }, save = { n, m, b -> saved += Triple(n, m, b) })

    @Test fun switchingChatsRestoresEachDraftAndItsAttachments() = runTest {
        val pc = Pc().apply { chat("a"); chat("b", "Other") }; val vm = connected(pc)
        vm.connect("", "token")!!.join(); vm.select("a")
        vm.setDraft("Draft A"); vm.upload("a.txt", "text/plain", "A".encodeToByteArray())!!.join()
        val attachments = vm.state.value.attachments
        vm.select("b"); assertEquals("", vm.state.value.draft); assertTrue(vm.state.value.attachments.isEmpty())
        vm.setDraft("Draft B"); vm.selectProject("changed-project"); vm.select("a")
        assertEquals("Draft A", vm.state.value.draft); assertEquals(attachments, vm.state.value.attachments)
        vm.select("b"); assertEquals("Draft B", vm.state.value.draft)
        vm.newChat(); vm.setDraft("Unsent new chat"); vm.select("a"); vm.newChat()
        assertEquals("Unsent new chat", vm.state.value.draft)
    }

    @Test fun cancellingOrSendingAnEditRestoresTheOriginalComposer() = runTest {
        val pc = Pc().apply { chat(messages = conversation) }; val vm = connected(pc)
        vm.connect("", "token")!!.join(); vm.select("a")
        vm.setDraft("Next question"); vm.upload("next.txt", "text/plain", "Next".encodeToByteArray())!!.join()
        val attachments = vm.state.value.attachments
        vm.startEditing(0); vm.setDraft("Changed first question"); vm.cancelEditing()
        assertEquals("Next question", vm.state.value.draft); assertEquals(attachments, vm.state.value.attachments)
        vm.startEditing(2); vm.setDraft("Changed second question"); vm.send()!!.join()
        assertEquals("Next question", vm.state.value.draft); assertEquals(attachments, vm.state.value.attachments)
        assertEquals("Changed second question", (chatOf(pc).messages.last() as Message.User).content)
        assertNull(vm.state.value.editingIndex)
    }

    @Test fun unfinishedEditsStayWithTheirChatAndPrivateDraftsDoNot() = runTest {
        val pc = Pc().apply { chat("a", messages = conversation); chat("b") }; val vm = connected(pc)
        vm.connect("", "token")!!.join(); vm.select("a"); vm.setDraft("Original draft")
        vm.startEditing(2); vm.setDraft("Unsent edit"); vm.select("b"); vm.select("a")
        assertEquals("Unsent edit", vm.state.value.draft); assertEquals(2, vm.state.value.editingIndex)
        vm.cancelEditing(); assertEquals("Original draft", vm.state.value.draft)
        vm.newIncognito(); assertEquals("", vm.state.value.draft)
        vm.setDraft("Private draft"); vm.select("a"); assertEquals("Original draft", vm.state.value.draft)
        vm.newIncognito(); assertEquals("", vm.state.value.draft)
        vm.newChat(); vm.setDraft("Ordinary draft"); vm.lock(); vm.connect("", "token")!!.join(); vm.newChat()
        assertEquals("", vm.state.value.draft)
    }

    @Test fun failedEditsKeepTheirTextAndCanRecoverTheOriginalDraft() = runTest {
        val pc = Pc().apply { chat(messages = conversation) }; val vm = connected(pc)
        vm.connect("", "token")!!.join(); vm.select("a"); vm.setDraft("Original unsent question")
        vm.startEditing(2); vm.setDraft("Attempted edit")
        pc.routes["POST /workspace/items/a/continue"] = { error("Revision conflict") }
        vm.send()!!.join()
        assertEquals("Attempted edit", vm.state.value.draft); assertEquals(2, vm.state.value.editingIndex)
        assertEquals("Revision conflict", vm.state.value.error)
        vm.cancelEditing(); assertEquals("Original unsent question", vm.state.value.draft)
    }

    @Test fun newChatDraftsStayScopedToTheirProject() = runTest {
        val pc = Pc(); val vm = connected(pc)
        vm.connect("", "token")!!.join()
        vm.newChat("p1"); vm.setDraft("Project one")
        vm.newChat("p2"); assertEquals("", vm.state.value.draft); vm.setDraft("Project two")
        vm.newChat("p1"); assertEquals("Project one", vm.state.value.draft)
        vm.newChat("p2"); assertEquals("Project two", vm.state.value.draft)
    }

    @Test fun acceptedSendDoesNotLeaveAStaleDraftInTheCache() = runTest {
        val pc = Pc().apply { chat("a"); chat("b") }; val vm = connected(pc)
        vm.connect("", "token")!!.join(); vm.select("a"); vm.setDraft("Send this")
        vm.select("b"); vm.select("a"); vm.send()!!.join(); vm.select("b"); vm.select("a")
        assertEquals("", vm.state.value.draft); assertTrue(vm.state.value.attachments.isEmpty())
    }

    @Test fun editsKeepAttachmentsRegenerateDropsAnswersAndBranchesEndAtAUserMessage() {
        val edited = ChatEdits.edit(conversation, 0, "  Changed  ")!!
        assertEquals(listOf(Message.User("Changed", (conversation[0] as Message.User).attachments)), edited)
        assertNull(ChatEdits.edit(conversation, 1, "Not a user message"))
        assertNull(ChatEdits.edit(listOf(Message.User("x")), 0, " "))
        assertEquals(conversation.take(3), ChatEdits.regenerate(conversation))
        assertEquals(conversation.take(3), ChatEdits.branch(conversation, 2))
        assertNull(ChatEdits.branch(conversation, 1))
        assertEquals("Second", ChatEdits.questionBefore(conversation, 5))
        assertEquals("Notes (branch)", ChatEdits.branchTitle("Notes"))
        assertEquals("Plan Q3.md", CanvasDoc("Plan: Q3!", "x").fileName)
        assertTrue(AnswerFeedback.memory("Q", "**Bold** answer", true).startsWith("User liked this answer"))
    }

    @Test fun editingSendsTheTruncatedChatAndRegenerateAsksAgain() = runTest {
        val pc = Pc().apply { chat(messages = conversation) }; val vm = connected(pc)
        vm.connect("", "token")!!.join(); vm.select("a")
        vm.startEditing(2)
        assertEquals("Second", vm.state.value.draft); assertEquals(2, vm.state.value.editingIndex)
        vm.setDraft("Second, edited"); vm.send()!!.join()
        assertEquals(listOf(conversation[0], conversation[1], Message.User("Second, edited")), chatOf(pc).messages)
        assertNull(vm.state.value.editingIndex); assertEquals("", vm.state.value.draft)
        pc.chat(messages = conversation, revision = pc.items.getValue("a").revision); vm.refresh()!!.join()
        vm.regenerate()!!.join()
        assertEquals(conversation.take(3), chatOf(pc).messages)
        assertTrue(pc.calls.count { it.first == "/workspace/items/a/continue" } == 2)
    }

    @Test fun branchCreatesAnIndependentChatAndOpensIt() = runTest {
        val pc = Pc().apply { chat(messages = conversation) }; val vm = connected(pc, { "branch" })
        vm.connect("", "token")!!.join(); vm.select("a"); vm.branch(2)!!.join()
        val branch = decodeConversation(pc.items.getValue("branch").payload.getValue("chat").jsonPrimitive.content)
        assertEquals(conversation.take(3), branch.messages); assertEquals("Original (branch)", branch.summary.title)
        assertEquals("branch", vm.state.value.activeId)
        assertEquals(conversation, chatOf(pc).messages)
        assertFalse(pc.calls.any { it.first.endsWith("/continue") })
    }

    @Test fun runningJobsShowStepsAndWaitForTheApprovalAnswer() = runTest {
        val pc = Pc().apply { chat(); jobStatus = "running"; jobFields = buildJsonObject { put("item_id", "a") } }
        val resume = CompletableDeferred<Unit>()
        pc.jobEvents = { _, event ->
            event(buildJsonObject {
                put("text", ""); put("done", false); put("status", "waiting")
                put("entries", buildJsonArray { add(buildJsonObject { put("role", "tool_call"); put("id", "c1"); put("name", "remember"); put("arguments", "{}") }) })
                put("approval", buildJsonObject { put("call_id", "c1"); put("tool", "remember"); put("title", "Remember this?"); put("prompt", "Save tea?") })
            })
            resume.await()
            event(buildJsonObject { put("text", "Saved."); put("done", true) })
        }
        pc.routes["POST /workspace/jobs/job/approval"] = { body -> buildJsonObject { put("approved", body!!.getValue("approve")) } }
        val vm = connected(pc)
        vm.connect("", "token")!!.join(); vm.select("a"); runCurrent()
        // A job already running for this chat (after a reload or from another client) is followed.
        assertEquals("job", vm.state.value.activeJob)
        assertEquals("Save tea?", vm.state.value.approval!!["prompt"]!!.jsonPrimitive.content)
        assertEquals(listOf(Message.User("Hello"), Message.ToolCall("c1", "remember", "{}")), vm.state.value.transcript)
        assertFalse(vm.state.value.canSend)
        vm.answerApproval(true)!!.join()
        assertEquals(true, pc.calls.last { it.first == "/workspace/jobs/job/approval" }.second!!["approve"]!!.jsonPrimitive.boolean)
        assertEquals("c1", pc.calls.last { it.first == "/workspace/jobs/job/approval" }.second!!["call_id"]!!.jsonPrimitive.content)
        assertNull(vm.state.value.approval)
        pc.jobStatus = "completed"; resume.complete(Unit); runCurrent()
        assertNull(vm.state.value.activeJob); assertTrue(vm.state.value.liveEntries.isEmpty())
    }

    @Test fun leavingAChatWithARunningJobStopsFollowingWithoutCancellingIt() = runTest {
        val pc = Pc().apply { chat(); chat("b", "Other"); jobStatus = "waiting"; jobFields = buildJsonObject { put("item_id", "a") } }
        pc.jobEvents = { _, _ -> awaitCancellation() }
        val vm = connected(pc)
        vm.connect("", "token")!!.join(); vm.select("a"); runCurrent()
        assertEquals("job", vm.state.value.activeJob)
        vm.select("b")
        assertEquals("b", vm.state.value.activeId); assertNull(vm.state.value.activeJob)
        assertFalse(pc.calls.any { it.first.endsWith("/cancel") })
        vm.close()
    }

    @Test fun canvasSavesToLibraryAndFeedbackBecomesOnePreference() = runTest {
        val pc = Pc().apply { chat(messages = conversation) }
        pc.routes["POST /workspace/create-file"] = { body -> buildJsonObject { put("id", "f"); put("name", body!!.getValue("name")) } }
        val vm = connected(pc)
        vm.connect("", "token")!!.join(); vm.select("a")
        vm.openCanvas("## Draft\n**Bold** text")
        assertEquals("Original", vm.state.value.canvas!!.title)
        vm.updateCanvas("Trip plan", "Body"); vm.saveCanvas()!!.join()
        assertEquals("Trip plan.md", pc.calls.last { it.first == "/workspace/create-file" }.second!!["name"]!!.jsonPrimitive.content)
        assertNull(vm.state.value.canvas); assertNotNull(vm.state.value.notice)
        vm.feedback(5, "Two", liked = false)!!.join(); vm.feedback(5, "Two", liked = false)!!.join()
        assertEquals(1, pc.facts.size)
        assertEquals("preference", pc.facts.single()["category"]!!.jsonPrimitive.content)
        assertTrue(pc.facts.single()["text"]!!.jsonPrimitive.content.contains("Q: Second"))
        assertEquals("Already noted — no duplicate saved.", vm.state.value.notice)
    }

    @Test fun exportsDownloadTheOpenChat() = runTest {
        val pc = Pc().apply { chat(messages = conversation) }; val saved = mutableListOf<Triple<String, String, ByteArray>>()
        val vm = connected(pc, saved = saved)
        vm.connect("", "token")!!.join(); vm.select("a")
        vm.exportChat("md")!!.join(); vm.exportChat("json")!!.join()
        assertEquals(listOf("Original.md", "Original.json"), saved.map { it.first })
        assertTrue(saved[0].third.decodeToString().contains("## FRIDAY\nTwo"))
        assertEquals(conversation, decodeConversation(saved[1].third.decodeToString()).messages)
    }

    @Test fun backupRoundTripRestoresNewCopiesWithAttachmentsAndProjects() = runTest {
        val image = "0123456789abcdef0123456789abcdef"
        val pc = Pc().apply { chat(messages = conversation); files[image] = byteArrayOf(1, 2, 3) }
        val saved = mutableListOf<Triple<String, String, ByteArray>>()
        var next = 0
        val vm = connected(pc, { "restored${next++}" }, saved)
        vm.connect("", "token")!!.join()
        vm.exportBackup()!!.join()
        val backup = saved.single { it.first == "friday-backup.zip" }.third
        val contents = ChatBackup.read(backup) { _, _ -> error("stored entries need no inflater") }
        assertEquals(listOf(byteArrayOf(1, 2, 3).toList()), contents.assets.values.map { it.toList() })
        assertEquals("assets/$image", (contents.chats.single().messages[0] as Message.User).attachments.single().path)
        vm.restoreBackup(backup)!!.join()
        assertNull(vm.state.value.error)
        val restored = decodeConversation(pc.items.getValue("restored0").payload.getValue("chat").jsonPrimitive.content)
        val attachment = (restored.messages[0] as Message.User).attachments.single()
        assertTrue(attachment.path!!.startsWith("remote:")); assertNotEquals("remote:$image", attachment.path)
        assertEquals(conversation.drop(1), restored.messages.drop(1))
        assertEquals(conversation, chatOf(pc).messages)
    }

    @Test fun zipReaderRejectsUnknownAndUnsafeEntries() = runTest {
        for (name in listOf("../evil.json", "chats/../../x.json", "settings.json")) {
            val zip = Zip.write(listOf(ZipEntry(name, "{}".encodeToByteArray())))
            assertFailsWith<IllegalArgumentException> { ChatBackup.read(zip) { _, _ -> ByteArray(0) } }
        }
        val damaged = Zip.write(listOf(ZipEntry("knowledge.json", "{}".encodeToByteArray()))).also { it[30 + "knowledge.json".length] = 'x'.code.toByte() }
        assertFails { ChatBackup.read(damaged) { _, _ -> ByteArray(0) } }
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
    }

    @Test fun restoreMergesProjectsWithoutOverwriting() {
        val current = Knowledge(projects = listOf(AssistantProject("p", "Mine")))
        val incoming = Knowledge(projects = listOf(AssistantProject("p", "Theirs"), AssistantProject("q", "New")))
        val (merged, renamed) = ChatBackup.merge(current, incoming) { "fresh" }
        assertEquals(listOf("Mine", "Theirs", "New"), merged.projects.map { it.name })
        assertEquals(mapOf("p" to "fresh"), renamed)
        val chats = ChatBackup.restored(listOf(StoredConversation(ConversationSummary("old", "t", 1, 1, "p"), emptyList())), renamed) { "copy" }
        assertEquals(ConversationSummary("copy", "t", 1, 1, "fresh"), chats.single().summary)
    }

    @Test fun memoryReviewAndArchiveInvestigationUseTheSharedEndpoints() = runTest {
        val pc = Pc()
        pc.routes["GET /workspace/memory/suggestions"] = { buildJsonArray { add(buildJsonObject { put("id", "s"); put("text", "Likes tea"); put("quote", "I like tea") }) } }
        pc.routes["GET /workspace/memory/situations"] = { buildJsonArray { add(buildJsonObject { put("id", "t"); put("topic", "Move"); put("summary", "Moving soon"); put("status", "open") }) } }
        pc.routes["POST /workspace/memory/suggestions/s"] = { JsonObject(emptyMap()) }
        pc.routes["GET /workspace/memory/sources/x"] = { buildJsonObject { put("id", "x"); put("title", "Old chat"); put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "Hi") }); add(buildJsonObject { put("role", "assistant"); put("content", "Hello") }) }) } }
        pc.routes["DELETE /workspace/memory/sources/x"] = { JsonObject(emptyMap()) }
        pc.routes["POST /workspace/memory/imports/i"] = { buildJsonObject { put("imported", 3); put("duplicates", 1) } }
        val vm = connected(pc, { "continued" })
        vm.connect("", "token")!!.join()
        assertEquals("Likes tea", vm.state.value.suggestions.single()["text"]!!.jsonPrimitive.content)
        assertEquals("Move", vm.state.value.situations.single()["topic"]!!.jsonPrimitive.content)
        vm.reviewSuggestion("s", true, "Likes green tea")!!.join()
        assertEquals("Likes green tea", pc.calls.last { it.first == "/workspace/memory/suggestions/s" }.second!!["text"]!!.jsonPrimitive.content)
        vm.searchArchive("trip é")!!.join()
        assertTrue(pc.calls.last { it.first.startsWith("/workspace/memory/sources?") }.first.contains("q=trip"))
        vm.source("x")!!.join(); vm.continueSource()!!.join()
        assertEquals(listOf(Message.User("Hi"), Message.Assistant("Hello")), decodeConversation(pc.items.getValue("continued").payload.getValue("chat").jsonPrimitive.content).messages)
        assertEquals("continued", vm.state.value.activeId)
        vm.stageImport { buildJsonObject { put("id", "i"); put("conversations", 4) } }!!.join()
        assertEquals("4", vm.state.value.importPreview!!["conversations"]!!.jsonPrimitive.content)
        vm.commitImport(true)!!.join()
        assertNull(vm.state.value.importPreview); assertTrue(vm.state.value.notice!!.startsWith("Imported 3 chats"))
        vm.source("x")!!.join(); vm.deleteSource("x")!!.join()
        assertNull(vm.state.value.sourcePreview)
    }

    @Test fun currentQuestionsGetEvidenceFirstAndNoSearchRequestsTurnWebOff() = runTest {
        val pc = Pc().apply { chat() }
        pc.routes["POST /grounding"] = { buildJsonObject { putJsonArray("evidence") { add(buildJsonObject { put("title", "Rates"); put("url", "https://example.org/rates"); put("status", "retrieved"); putJsonArray("passages") { add("5%") } }) } } }
        pc.routes["GET /v1/models"] = { buildJsonObject { putJsonArray("data") { add(buildJsonObject { put("id", "big") }); add(buildJsonObject { put("id", "small") }) } } }
        val vm = connected(pc)
        vm.connect("", "token")!!.join(); vm.select("a")
        assertEquals(listOf("big", "small"), vm.state.value.models)
        vm.setModel("small")
        vm.setDraft("What is the current interest rate?"); vm.send()!!.join()
        val body = pc.calls.last { it.first == "/workspace/items/a/continue" }.second!!
        assertTrue(body["turn_note"]!!.jsonPrimitive.content.contains("Public evidence retrieval was attempted"))
        assertEquals("small", body["model"]!!.jsonPrimitive.content)
        assertNull(body["block_web"])
        assertEquals("https://example.org/rates", vm.state.value.groundingSources.single().url)
        pc.chat(messages = listOf(Message.User("What is the current interest rate?"), Message.Assistant("It is 5% [1](https://example.org/rates).")), revision = pc.items.getValue("a").revision)
        vm.refresh()!!.join()
        vm.setDraft("Don't search, just guess"); vm.send()!!.join()
        val offline = pc.calls.last { it.first == "/workspace/items/a/continue" }.second!!
        assertEquals(true, offline["block_web"]!!.jsonPrimitive.boolean)
        assertTrue(offline["turn_note"]!!.jsonPrimitive.content.contains("disabled for this turn"))
        assertEquals(1, pc.calls.count { it.first == "/grounding" })
    }

    @Test fun privateApprovalRemainsUsableWhileStreamingAndCanRetryAFailedAnswer() = runTest {
        val pc = Pc(); val vm = connected(pc, { "private" })
        val answered = CompletableDeferred<Unit>()
        pc.privateEvents = { event ->
            event(buildJsonObject { put("session", "session") })
            event(buildJsonObject { put("approval", buildJsonObject { put("call_id", "call"); put("title", "Send private details?") }) })
            answered.await()
            event(buildJsonObject { put("text", "Kept private."); put("done", true) })
        }
        var fail = true
        pc.routes["POST /workspace/chat/ephemeral/session/approval"] = { body ->
            assertEquals("call", body!!["call_id"]!!.jsonPrimitive.content)
            assertFalse(body["approve"]!!.jsonPrimitive.boolean)
            if (fail) { fail = false; error("Disconnected") }
            answered.complete(Unit)
            buildJsonObject { put("approved", false) }
        }
        vm.connect("", "token")!!.join(); vm.newIncognito()
        vm.setDraft("Look her up")
        val turn = vm.send()!!
        runCurrent()
        assertTrue(vm.state.value.busy)
        assertTrue(vm.state.value.canAnswerApproval)
        vm.answerApproval(false)!!.join()
        assertEquals("Disconnected", vm.state.value.error)
        assertTrue(vm.state.value.canAnswerApproval)
        vm.answerApproval(false)!!.join()
        turn.join()
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.approval)
        assertEquals("Kept private.", (vm.state.value.active!!.conversation.messages.last() as Message.Assistant).content)
        assertTrue(pc.items.isEmpty())
    }

    @Test fun privateChatsUseMemoriesUnlessFreshSlateAndAnswerTheirStreamsApprovals() = runTest {
        val pc = Pc(); val vm = connected(pc, { "private" })
        vm.connect("", "token")!!.join(); vm.newIncognito()
        assertFalse(vm.state.value.freshSlate)
        vm.setDraft("Question"); vm.send()!!.join()
        assertEquals(true, pc.calls.last { it.first == "/workspace/chat/ephemeral" }.second!!["use_memory"]!!.jsonPrimitive.boolean)
        vm.newIncognito(freshSlate = true); vm.setDraft("Question"); vm.send()!!.join()
        assertEquals(false, pc.calls.last { it.first == "/workspace/chat/ephemeral" }.second!!["use_memory"]!!.jsonPrimitive.boolean)
    }
}
