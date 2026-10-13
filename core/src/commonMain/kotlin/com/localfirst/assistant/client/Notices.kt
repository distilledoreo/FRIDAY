package com.localfirst.assistant.client

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * What deserves a notification, by the rules of Android's task poller (TaskNotifications.kt): finished or failed
 * computer tasks, FRIDAY tasks that finished or need review (watch schedules only when their report changed), PC
 * permissions and questions (reminded every two hours until answered), PC sessions that stopped working, and
 * eligible check-in proposals. Chats waiting on an approval card are included too. At most five notices per poll.
 *
 * [seen] is this device's memory of what was already announced. The first poll on a device only records what exists.
 */
class NoticePlanner(
    private val request: suspend (path: String, method: String, body: JsonObject?) -> JsonElement,
    private val seen: MutableMap<String, String>,
    private val clock: () -> Long,
) {
    /** [open] names where the notice leads: "tasks", "activity", "code", "brief" or "chat:<id>". */
    data class Notice(val key: String, val title: String, val text: String, val open: String)

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private suspend fun optional(path: String, method: String = "GET", body: JsonObject? = null): JsonElement? =
        try { request(path, method, body) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    suspend fun poll(checkins: Boolean): List<Notice> {
        val first = INITIALIZED !in seen
        val out = mutableListOf<Notice>()
        fun announce(notice: Notice) { if (!first && out.size < 5) out += notice }

        for (task in optional("/workspace/jobs")?.rows().orEmpty()) {
            val id = task.text("id")
            val pending = task["pending"] as? JsonObject
            if (task.text("status") == "waiting" && pending != null && task.text("item_id").isNotBlank()) {
                val key = "approval-${pending.text("call_id")}"
                if (key !in seen) { announce(Notice(key, "FRIDAY needs your approval", pending.text("title").ifBlank { "A chat is waiting for you." }, "chat:${task.text("item_id")}")); seen[key] = "1" }
            }
            val signature = "${task.text("result")}\n${task.text("error")}".hashCode().toString()
            if ((task.text("result").isNotBlank() || task.text("status") == "failed") && seen[id] != signature) {
                announce(Notice(id, task.text("prompt").take(80), if (task.text("status") == "failed") task.text("error") else task.text("result").take(180), "tasks"))
                seen[id] = signature
            }
        }

        for (task in optional("/workspace/agent/tasks?limit=100")?.rows().orEmpty()) {
            val status = task.text("status")
            if (status !in listOf("done", "failed", "interrupted", "awaiting_setup")) continue
            val key = "agent-${task.text("id")}"
            val signature = "$status:${(task["updated"] as? JsonPrimitive)?.contentOrNull}"
            if (seen[key] == signature) continue
            val proposal = task["proposal"] as? JsonObject ?: JsonObject(emptyMap())
            val parent = (proposal["schedule_origin"] as? JsonObject)?.text("id")?.takeIf { Regex("[a-f0-9]{32}").matches(it) }
            if (status == "done" && parent != null) {
                // A watch schedule reports only when its report changes; quiet runs stay silent.
                val notify = ((optional("/workspace/agent/tasks/$parent") as? JsonObject)?.get("proposal") as? JsonObject)?.let { (it["schedule"] as? JsonObject)?.text("notify") }
                if (notify == "on_change") {
                    seen[key] = signature
                    val text = (((optional("/workspace/agent/tasks/${task.text("id")}/report") as? JsonObject)?.get("result")) as? JsonObject)?.text("text").orEmpty()
                    val watch = "watch-$parent"
                    if (text.isNotBlank() && seen[watch] != text.hashCode().toString()) {
                        seen[watch] = text.hashCode().toString()
                        announce(Notice(watch, "Watched change: ${proposal.text("prompt").take(80)}", text.take(180), "activity"))
                    }
                    continue
                }
            }
            announce(Notice(key, proposal.text("prompt").take(80), when (status) {
                "done" -> if ("schedule" in proposal) "Scheduled plan finished. Open FRIDAY for its run records." else "FRIDAY's report is ready. Open FRIDAY to read it."
                "awaiting_setup" -> "An action needs account setup. Review it in FRIDAY."
                "interrupted" -> "Task interrupted. Review before trying again."
                else -> "Task failed. Open FRIDAY for details."
            }, "activity"))
            seen[key] = signature
        }

        val pc = optional("/workspace/pc/status") as? JsonObject
        if ((pc?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true) {
            val approvals = optional("/workspace/pc/approvals") as? JsonObject
            val sessions = optional("/workspace/pc/sessions")?.rows().orEmpty()
            // Coding tasks are answered in Code, so their notices open it.
            val coding = sessions.filter { it.text("agent") == "friday-code" }.map { it.text("id") }.toSet()
            fun opener(session: String) = if (session in coding) "code" else "activity"
            val pending = mutableSetOf<String>()
            for ((kind, rows) in listOf("pcperm" to approvals?.get("permissions")?.rows().orEmpty(), "pcq" to approvals?.get("questions")?.rows().orEmpty())) {
                for (row in rows) {
                    val key = "$kind-${row.text("id")}"
                    pending += key
                    val firstSeen = seen[key]?.toLongOrNull() ?: 0L
                    if (firstSeen == 0L || clock() - firstSeen >= REMIND_MS) {
                        val owner = row.text("owner").ifBlank { row.text("session").ifBlank { row.text("sessionID") } }
                        announce(if (kind == "pcperm") Notice(key, "FRIDAY needs your permission", row.text("detail").ifBlank {
                            if (owner in coding) "A coding task is waiting for approval." else "A PC task is waiting for approval." }.take(180), opener(owner))
                            else Notice(key, "FRIDAY has a question", "A PC task needs your answer. Open FRIDAY to reply.", opener(owner)))
                        seen[key] = clock().toString()
                    }
                }
            }
            // Answered ones are forgotten.
            seen.keys.filter { (it.startsWith("pcperm-") || it.startsWith("pcq-")) && it !in pending }.forEach { seen.remove(it) }
            for (session in sessions) {
                val id = session.text("id")
                if (!Regex("[A-Za-z0-9_-]{1,100}").matches(id)) continue
                val key = "pc-$id"
                val state = session.text("state")
                val previous = seen[key]
                if (previous == null && state != "busy") { seen[key] = state; continue }
                if (previous == "busy" && state != "busy" && "$key-notified" !in seen) {
                    val kind = if (id in coding) "coding task" else "PC task"
                    announce(Notice(key, session.text("title").ifBlank { "PC task" }.take(80), when (state) {
                        "idle" -> if (id in coding) "Your coding task finished. Open Code to review the changes." else "Your PC task finished. Open FRIDAY to see the result."
                        "waiting" -> "Your $kind needs your reply. Open FRIDAY to review it."
                        else -> "Your $kind stopped ($state). Open FRIDAY for details."
                    }, opener(id)))
                    seen["$key-notified"] = state
                }
                if (previous != state) seen[key] = state
            }
        }

        if (checkins) {
            // Proposals contain no account snapshot and cannot approve or start a task.
            val proposals = optional("/workspace/agent/briefing/proposals/refresh", "POST", buildJsonObject { put("scope", "") }) as? JsonObject
            if ((proposals?.get("notify") as? JsonPrimitive)?.booleanOrNull == true) for (item in proposals["items"]?.rows().orEmpty()) {
                val key = "brief-${item.text("id")}"
                if (key in seen) continue
                announce(Notice(key, "FRIDAY", if (item.text("kind") == "morning") "Open FRIDAY for your morning brief." else "A check-in proposal is available. Open FRIDAY to review it.", "brief"))
                seen[key] = clock().toString()
            }
            seen.entries.removeAll { (key, value) -> key.startsWith("brief-") && (value.toLongOrNull() ?: 0L) < clock() - 30L * 86_400_000L }
        }
        seen[INITIALIZED] = "1"
        return out
    }

    companion object {
        const val INITIALIZED = "__initialized"
        const val REMIND_MS = 2 * 60 * 60 * 1000L
    }
}
