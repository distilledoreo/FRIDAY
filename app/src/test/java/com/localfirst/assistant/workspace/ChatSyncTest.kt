package com.localfirst.assistant.workspace

import android.app.Application
import android.content.Context
import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.conversation.KnowledgeStore
import com.localfirst.assistant.settings.ServerSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ChatSyncTest {
    private lateinit var app: Application
    private lateinit var sync: ChatSync
    private val remote = linkedMapOf<String, JSONObject>()
    private var onWrite: (() -> Unit)? = null
    private val requests = mutableListOf<String>()
    private val delivered = mutableListOf<List<String>>()
    private var legacyGateway = false

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        val directory = File(app.cacheDir, "sync-test").apply { deleteRecursively(); mkdirs() }
        app.getSharedPreferences("friday_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        sync = ChatSync(app, WorkspaceClient(app) { ServerSettings(searchBaseUrl = "https://sync.test") },
            FileConversationStore(File(directory, "chats")), KnowledgeStore(File(directory, "knowledge.json"))) { path, method, body, _ ->
            if (method == "GET") {
                requests += path.substringBefore("?since")
                when {
                    path.startsWith("/workspace/changes") -> {
                        if (legacyGateway) throw java.io.IOException("Computer returned HTTP 404: Not Found")
                        val seen = java.net.URLDecoder.decode(path.substringAfter("since=").substringBefore('&'), "UTF-8").split(',').filter { ':' in it }
                            .associate { it.substringBefore(':') to it.substringAfter(':').toInt() }
                        val moved = remote.values.filter { seen[it.getString("id")] != it.getInt("revision") }
                        delivered += moved.map { it.getString("id") }
                        JSONObject().put("cursor", "at," + remote.values.joinToString(",") { it.getString("id") + ":" + it.getInt("revision") })
                            .put("reset", !path.contains("since=at")).put("items", JSONArray(moved)).put("jobs", JSONObject.NULL).put("memory", false)
                    }
                    path.endsWith("?revisions=1") -> JSONArray(remote.values.map { JSONObject().put("id", it.getString("id")).put("revision", it.getInt("revision")).put("deleted", it.optBoolean("deleted")) })
                    path.contains("?ids=") -> JSONArray(path.substringAfter("?ids=").split(',').mapNotNull { remote[it] })
                    else -> JSONArray(remote.values.toList())
                }.toString()
            }
            else {
                val id = path.substringAfterLast('/')
                val revision = remote[id]?.getInt("revision") ?: 0
                require(body!!.getInt("revision") == revision)
                val saved = JSONObject(body.toString()).put("id", id).put("revision", revision + 1)
                remote[id] = saved
                onWrite?.invoke()
                saved.toString()
            }
        }
    }
    private fun preference(value: String, revision: Int) = JSONObject().put("id", "preferences-theme")
        .put("revision", revision).put("payload", JSONObject().put("preferences", JSONObject().put("value", value).toString()))
    private fun theme() = app.getSharedPreferences("friday_appearance", Context.MODE_PRIVATE)

    private fun chat(id: String, title: String, revision: Int) = JSONObject().put("id", id).put("revision", revision).put("payload", JSONObject().put("chat",
        encodeConversation(StoredConversation(ConversationSummary(id, title, 1, 2), listOf(Message.User("Hello"))))))
    @Test fun syncDownloadsOnlyRecordsThatMoved() = runBlocking {
        val chats = FileConversationStore(File(File(app.cacheDir, "sync-test"), "chats"))
        remote["chat-a"] = chat("chat-a", "First", 1); remote["chat-b"] = chat("chat-b", "Second", 1)
        assertTrue(sync.sync().isEmpty())
        assertEquals(listOf("chat-a", "chat-b"), delivered.last())
        assertEquals(setOf("First", "Second"), chats.list().map { it.title }.toSet())
        requests.clear(); assertTrue(sync.sync().isEmpty())
        assertEquals(emptyList<String>(), delivered.last()); assertEquals(listOf("/workspace/changes"), requests)
        remote["chat-b"] = chat("chat-b", "Renamed on the computer", 2)
        sync.awaitRemoteChange(15)
        requests.clear(); assertTrue(sync.sync().isEmpty())
        // The answer that ended the wait is the one the sync uses.
        assertEquals(listOf("chat-b"), delivered.last()); assertEquals(emptyList<String>(), requests)
        assertEquals("Renamed on the computer", chats.load("chat-b")!!.summary.title)
    }
    @Test fun unresolvedConflictIsStillOfferedOnLaterSyncs() = runBlocking {
        val chats = FileConversationStore(File(File(app.cacheDir, "sync-test"), "chats"))
        remote["chat-a"] = chat("chat-a", "First", 1)
        assertTrue(sync.sync().isEmpty())
        chats.rename("chat-a", "Renamed on the phone")
        remote["chat-a"] = chat("chat-a", "Renamed on the computer", 2)
        assertEquals("chat-a", sync.sync().single().id)
        // The computer reports nothing new the second time; the phone still owes this record a decision.
        requests.clear()
        val conflict = sync.sync().single()
        assertEquals(emptyList<String>(), delivered.last()); assertEquals(listOf("/workspace/changes", "/workspace/items?ids=chat-a"), requests)
        sync.resolve(conflict, keepPhone = false)
        assertEquals("Renamed on the computer", chats.load("chat-a")!!.summary.title)
        assertTrue(sync.sync().isEmpty())
    }
    @Test fun gatewayWithoutChangeCursorsStillSyncs() = runBlocking {
        val chats = FileConversationStore(File(File(app.cacheDir, "sync-test"), "chats"))
        legacyGateway = true
        remote["chat-a"] = chat("chat-a", "First", 1); remote["chat-b"] = chat("chat-b", "Second", 1)
        assertTrue(sync.sync().isEmpty())
        assertEquals(setOf("First", "Second"), chats.list().map { it.title }.toSet())
        remote["chat-b"] = chat("chat-b", "Renamed on the computer", 2)
        requests.clear(); assertTrue(sync.sync().isEmpty())
        assertEquals(listOf("/workspace/items?revisions=1", "/workspace/items?ids=chat-b"), requests)
        assertEquals("Renamed on the computer", chats.load("chat-b")!!.summary.title)
    }
    @Test fun newClientAdoptsRemotePreferencesWithoutUploadingDefaultsOrCredentials() = runBlocking {
        remote["preferences-theme"] = preference("LIGHT", 1)
        assertTrue(sync.sync().isEmpty())
        assertEquals("LIGHT", theme().getString("theme", null))
        assertEquals(setOf("preferences-theme"), remote.keys)
        assertEquals(1, remote.getValue("preferences-theme").getInt("revision"))
    }
    @Test fun simultaneousPreferenceEditsRequireResolution() = runBlocking {
        remote["preferences-theme"] = preference("LIGHT", 1)
        sync.sync()
        theme().edit().putString("theme", "DARK").commit()
        remote["preferences-theme"] = preference("SYSTEM", 2)
        val conflict = sync.sync().single()
        assertEquals("DARK", theme().getString("theme", null))
        sync.resolve(conflict, false)
        assertEquals("SYSTEM", theme().getString("theme", null))
        assertTrue(sync.sync().isEmpty())
    }
    @Test fun editDuringUploadRemainsDirtyForNextSync() = runBlocking {
        theme().edit().putString("theme", "DARK").commit()
        onWrite = { theme().edit().putString("theme", "LIGHT").commit(); onWrite = null }
        sync.sync()
        sync.sync()
        val value = JSONObject(remote.getValue("preferences-theme").getJSONObject("payload").getString("preferences"))
        assertEquals("LIGHT", value.getString("value"))
        assertEquals(2, remote.getValue("preferences-theme").getInt("revision"))
    }
    @Test fun remoteTombstoneResetsPortablePreference() = runBlocking {
        remote["preferences-theme"] = preference("LIGHT", 1)
        sync.sync()
        remote["preferences-theme"] = JSONObject().put("id", "preferences-theme").put("revision", 2).put("deleted", true).put("payload", JSONObject())
        sync.sync()
        assertFalse(theme().contains("theme"))
        sync.sync()
        assertEquals(2, remote.getValue("preferences-theme").getInt("revision"))
    }
}
