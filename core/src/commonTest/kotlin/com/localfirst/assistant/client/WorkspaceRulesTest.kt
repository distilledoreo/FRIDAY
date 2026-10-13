package com.localfirst.assistant.client
import com.localfirst.assistant.conversation.*
import com.localfirst.assistant.settings.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
class WorkspaceRulesTest {
    @Test fun portablePreferenceChangesPreserveOtherFieldsAndRejectDeviceSettings() {
        val old = AppearanceSettings(displayName = "Me", reducedMotion = true)
        assertEquals(old.copy(accentHex = "00AABB"), old.withPreference("accent", JsonPrimitive(" #00aabb ")))
        assertFailsWith<IllegalArgumentException> { old.withPreference("api_key", JsonPrimitive("secret")) }
        assertFailsWith<IllegalArgumentException> { old.withPreference("accent", JsonPrimitive("invalid")) }
    }
    @Test fun editingAProjectPreservesMemoriesAndOtherProjects() {
        val old = Knowledge(listOf(SavedMemory("m", "Remember", 1)), listOf(AssistantProject("a", "Old"), AssistantProject("b", "Other")))
        val next = old.withProject("a", " Updated ", "Instructions", "Reference")
        assertEquals(old.memories, next.memories)
        assertEquals(AssistantProject("b", "Other"), next.projects.first())
        assertEquals(AssistantProject("a", "Updated", "Instructions", "Reference"), next.projects.last())
        assertFailsWith<IllegalArgumentException> { old.withProject("a", " ", "", "") }
        assertFailsWith<IllegalArgumentException> { old.withProject("a", "Name", "x".repeat(8001), "") }
    }
    @Test fun memoryWritesUseTheSameLimitsAndPreserveScopeAndPinning() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject?>>()
        val gateway = object : WorkspaceGateway {
            override fun connect(baseUrl: String, token: String) {}
            override fun lock() {}
            override suspend fun request(path: String, method: String, body: JsonObject?): JsonElement {
                calls += path to body; return JsonObject(emptyMap())
            }
        }
        val repository = WorkspaceRepository(gateway)
        repository.saveMemory(" Fact ", "id", "preference", "project", false)
        assertEquals("/workspace/memory/memories/id", calls.single().first)
        assertEquals("Fact", calls.single().second?.get("text")?.jsonPrimitive?.content)
        assertEquals("project", calls.single().second?.get("scope")?.jsonPrimitive?.content)
        assertEquals(false, calls.single().second?.get("pinned")?.jsonPrimitive?.boolean)
        assertFailsWith<IllegalArgumentException> { repository.saveMemory(" ", null, "other", "", true) }
        assertFailsWith<IllegalArgumentException> { repository.memorySetting("unknown", true) }
        assertEquals(1, calls.size)
    }
}
