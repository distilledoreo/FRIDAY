package com.localfirst.assistant.workspace

import android.app.Application
import android.content.Context
import com.localfirst.assistant.conversation.FileConversationStore
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

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        val directory = File(app.cacheDir, "sync-test").apply { deleteRecursively(); mkdirs() }
        app.getSharedPreferences("friday_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        sync = ChatSync(app, WorkspaceClient(app) { ServerSettings(searchBaseUrl = "https://sync.test") },
            FileConversationStore(File(directory, "chats")), KnowledgeStore(File(directory, "knowledge.json"))) { path, method, body, _ ->
            if (method == "GET") JSONArray(remote.values.toList()).toString()
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
