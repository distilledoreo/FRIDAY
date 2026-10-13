package com.localfirst.assistant.client

import com.localfirst.assistant.platform.PlatformNet
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/**
 * One answer from the computer about what changed. [items] are complete records. [all] lists every record's
 * revision when the answer is a complete listing (first contact, a restored computer, or a gateway without
 * cursors); it is null when [items] is only what moved after [cursor]'s predecessor.
 */
data class ItemChanges(val items: List<SyncItem>, val cursor: String, val all: Map<String, Int>?, val jobs: List<JsonObject>?, val memory: Boolean)

/** How every client learns what changed on the computer. What it then does with the answer is the client's own. */
class ChangeFeed(private val gateway: WorkspaceGateway, private val timerSeconds: Int = 5) {
    /** False once the gateway turns out to predate change cursors; the feed then lists revisions on a timer. */
    var cursors = true
        private set

    /**
     * With [waitSeconds], the gateway holds the request until something changes. [held] is the revision the
     * caller has of each record, used only to pick what to download from a gateway without cursors.
     */
    suspend fun changes(cursor: String, waitSeconds: Int, held: Map<String, Int>): ItemChanges {
        if (cursors) try {
            val reply = gateway.request("/workspace/changes?since=${PlatformNet.encodeUrl(cursor)}&wait=$waitSeconds").jsonObject
            val items = wireJson.decodeFromJsonElement<List<SyncItem>>(reply.getValue("items"))
            return ItemChanges(items, reply.getValue("cursor").jsonPrimitive.content,
                if (reply["reset"]?.jsonPrimitive?.booleanOrNull == true) items.associate { it.id to it.revision } else null,
                (reply["jobs"] as? JsonArray)?.map { it.jsonObject }, reply["memory"]?.jsonPrimitive?.booleanOrNull ?: true)
        } catch (e: WorkspaceException) {
            if (e.status != 404) throw e
            cursors = false
        }
        if (waitSeconds > 0) delay(timerSeconds * 1000L)
        if (held.isEmpty()) return complete(gateway.request("/workspace/items"))
        val listing = gateway.request("/workspace/items?revisions=1").jsonArray
        // A gateway from before revision listings answers with complete records.
        if (listing.any { "payload" in it.jsonObject }) return complete(listing)
        val all = listing.associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("revision").jsonPrimitive.int }
        val moved = all.filter { (id, revision) -> held[id] != revision }.keys
        if (moved.size > 120) return complete(gateway.request("/workspace/items"))
        return ItemChanges(items(moved), "", all, null, true)
    }

    /** Complete records by id. Ids the computer does not have are left out. */
    suspend fun items(ids: Collection<String>): List<SyncItem> = ids.chunked(40).flatMap {
        wireJson.decodeFromJsonElement<List<SyncItem>>(gateway.request("/workspace/items?ids=" + it.joinToString(",")))
    }

    private fun complete(records: JsonElement): ItemChanges {
        val items = wireJson.decodeFromJsonElement<List<SyncItem>>(records)
        return ItemChanges(items, "", items.associate { it.id to it.revision }, null, true)
    }
}
