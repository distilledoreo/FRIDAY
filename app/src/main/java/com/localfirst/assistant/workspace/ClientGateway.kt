package com.localfirst.assistant.workspace
import com.localfirst.assistant.client.WorkspaceGateway
import com.localfirst.assistant.client.wireJson
import kotlinx.serialization.json.*
import org.json.JSONObject
/** The native workspace owns connections and permissions; the shared repository owns API rules. */
class ClientGateway(private val client: WorkspaceClient) : WorkspaceGateway {
    override fun connect(baseUrl: String, token: String) { error("Use native connection settings") }
    override fun lock() {} // No separate token/session is owned by this adapter.
    override suspend fun artifact(id: String) { client.openArtifact("assistant://artifact/$id") }
    override suspend fun request(path: String, method: String, body: JsonObject?): JsonElement =
        wireJson.parseToJsonElement(client.request(path, method, body?.let { JSONObject(it.toString()) }))
}
