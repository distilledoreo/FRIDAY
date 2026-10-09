package com.localfirst.assistant.workspace

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.localfirst.assistant.MainActivity
import com.localfirst.assistant.settings.ServerSettingsStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

data class WorkspaceFile(val id: String, val name: String, val size: Long, val mime: String = "application/octet-stream") {
    companion object { fun from(j: JSONObject) = WorkspaceFile(j.getString("id"), j.getString("name"), j.getLong("size"), j.optString("mime", "application/octet-stream")) }
}

data class BackgroundTask(val id: String, val prompt: String, val status: String, val result: String, val error: String, val runAt: Long = 0, val intervalSeconds: Int = 0) {
    companion object { fun from(j: JSONObject) = BackgroundTask(j.getString("id"), j.getString("prompt"),
        j.getString("status"), j.optString("result"), j.optString("error"), (j.optDouble("run_at", 0.0) * 1000).toLong(), j.optInt("interval_seconds")) }
}

object TaskNotifications {
    const val CHANNEL = "background-tasks"
    fun enable(context: Context) {
        val info = JobInfo.Builder(831, ComponentName(context, TaskPollService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60 * 1000L).setPersisted(true).build()
        context.getSystemService(JobScheduler::class.java).schedule(info)
    }
}

/** Android chooses polling time; results are durable on the PC while the phone is offline. */
class TaskPollService : JobService() {
    companion object {
        /** Unanswered PC approvals re-alert this often until answered or cleared. */
        const val PC_REMIND_MS = 2 * 60 * 60 * 1000L

        fun sha256Hex(text: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            try {
                val client = WorkspaceClient(applicationContext) { ServerSettingsStore(applicationContext).load() }
                val tasks = runCatching { JSONArray(client.request("/workspace/jobs")) }.getOrElse { if (it is CancellationException) throw it; JSONArray() }
                val prefs = getSharedPreferences("task-notifications", Context.MODE_PRIVATE)
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(TaskNotifications.CHANNEL, "Assistant task results", NotificationManager.IMPORTANCE_DEFAULT))
                var sent = 0
                for (i in 0 until tasks.length()) {
                    val task = BackgroundTask.from(tasks.getJSONObject(i))
                    val signature = "${task.result}\n${task.error}".hashCode().toString()
                    if ((task.result.isNotBlank() || task.status == "failed") && prefs.getString(task.id, null) != signature) {
                        if (sent++ < 5) {
                            val open = PendingIntent.getActivity(this@TaskPollService, 0,
                                Intent(this@TaskPollService, MainActivity::class.java).setAction("com.localfirst.assistant.TASKS"),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            val notification = NotificationCompat.Builder(this@TaskPollService, TaskNotifications.CHANNEL)
                                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(task.prompt.take(80))
                                .setContentText(if (task.status == "failed") task.error else task.result.take(180))
                                .setContentIntent(open).setAutoCancel(true).build()
                            runCatching { manager.notify(task.id.hashCode(), notification) }
                        }
                        prefs.edit().putString(task.id, signature).apply()
                    }
                }
                if (manager.areNotificationsEnabled()) {
                    val agentTasks = JSONArray(client.request("/workspace/agent/tasks?limit=100"))
                    for (i in 0 until agentTasks.length()) {
                        val task = agentTasks.getJSONObject(i)
                        val status = task.optString("status")
                        if (status !in listOf("done", "failed", "interrupted", "awaiting_setup")) continue
                        val key = "agent-${task.getString("id") }"
                        val signature = "$status:${task.optDouble("updated") }"
                        if (prefs.getString(key, null) == signature || sent >= 5) continue
                        val origin = task.getJSONObject("proposal").optJSONObject("schedule_origin")
                        val parentId = origin?.optString("id")?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                        if (status == "done" && parentId != null) {
                            // A watch schedule reports only when its report changes; quiet runs stay silent.
                            val notify = runCatching { JSONObject(client.request("/workspace/agent/tasks/$parentId"))
                                .getJSONObject("proposal").optJSONObject("schedule")?.optString("notify") }.getOrNull()
                            if (notify == "on_change") {
                                val report = runCatching { JSONObject(client.request("/workspace/agent/tasks/${task.getString("id")}/report")) }.getOrNull()
                                val text = report?.optJSONObject("result")?.optString("text").orEmpty()
                                val watchKey = "watch-$parentId"
                                prefs.edit().putString(key, signature).apply()
                                if (text.isNotBlank() && prefs.getString(watchKey, null) != sha256Hex(text)) {
                                    prefs.edit().putString(watchKey, sha256Hex(text)).apply()
                                    val open = PendingIntent.getActivity(this@TaskPollService, watchKey.hashCode(),
                                        Intent(this@TaskPollService, MainActivity::class.java).setAction("com.localfirst.assistant.ACTIVITY"),
                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                    val notification = NotificationCompat.Builder(this@TaskPollService, TaskNotifications.CHANNEL)
                                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                                        .setContentTitle("Watched change: ${task.getJSONObject("proposal").optString("prompt").take(80)}")
                                        .setContentText(text.take(180)).setContentIntent(open).setAutoCancel(true).build()
                                    manager.notify(watchKey.hashCode(), notification)
                                    sent++
                                }
                                continue
                            }
                        }
                        val open = PendingIntent.getActivity(this@TaskPollService, key.hashCode(),
                            Intent(this@TaskPollService, MainActivity::class.java).setAction("com.localfirst.assistant.ACTIVITY"),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val text = when (status) {
                            "done" -> if (task.getJSONObject("proposal").has("schedule")) "Scheduled plan finished. Open Activity for its run records." else "FRIDAY's report is ready. Open Activity to read it."
                            "awaiting_setup" -> "An action needs account setup. Review it in Activity."
                            "interrupted" -> "Task interrupted. Review before trying again."
                            else -> "Task failed. Open Activity for details."
                        }
                        val notification = NotificationCompat.Builder(this@TaskPollService, TaskNotifications.CHANNEL)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(task.getJSONObject("proposal").optString("prompt").take(80))
                            .setContentText(text).setContentIntent(open).setAutoCancel(true).build()
                        manager.notify(key.hashCode(), notification)
                        prefs.edit().putString(key, signature).apply()
                        sent++
                    }
                }
                val privacy = getSharedPreferences("brief-privacy", Context.MODE_PRIVATE)
                if (manager.areNotificationsEnabled() && !privacy.getBoolean("incognito", false)) {
                    try {
                        val pcStatus = JSONObject(client.request("/workspace/pc/status"))
                        if (pcStatus.optBoolean("enabled")) {
                            val approvals = runCatching { JSONObject(client.request("/workspace/pc/approvals")) }.getOrNull()
                            // Unanswered approvals re-alert until answered or cleared; answered ones are forgotten.
                            val pendingKeys = mutableSetOf<String>()
                            approvals?.optJSONArray("permissions")?.let { permissions ->
                                for (i in 0 until permissions.length()) {
                                    val request = permissions.optJSONObject(i) ?: continue
                                    val key = "pcperm-${request.optString("id")}"
                                    pendingKeys += key
                                    val firstSeen = prefs.getLong(key, 0L)
                                    if ((firstSeen == 0L || System.currentTimeMillis() - firstSeen >= PC_REMIND_MS) && sent < 5) {
                                        val open = PendingIntent.getActivity(this@TaskPollService, key.hashCode(),
                                            Intent(this@TaskPollService, MainActivity::class.java).setAction("com.localfirst.assistant.ACTIVITY"),
                                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                        val notification = NotificationCompat.Builder(this@TaskPollService, TaskNotifications.CHANNEL)
                                            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("FRIDAY needs your permission")
                                            .setContentText(request.optString("detail").ifBlank { "A PC task is waiting for approval." }.take(180))
                                            .setContentIntent(open).setAutoCancel(true).build()
                                        manager.notify(key.hashCode(), notification)
                                        prefs.edit().putLong(key, System.currentTimeMillis()).apply(); sent++
                                    }
                                }
                            }
                            approvals?.optJSONArray("questions")?.let { questions ->
                                for (i in 0 until questions.length()) {
                                    val request = questions.optJSONObject(i) ?: continue
                                    val key = "pcq-${request.optString("id")}"
                                    pendingKeys += key
                                    val firstSeen = prefs.getLong(key, 0L)
                                    if ((firstSeen == 0L || System.currentTimeMillis() - firstSeen >= PC_REMIND_MS) && sent < 5) {
                                        val open = PendingIntent.getActivity(this@TaskPollService, key.hashCode(),
                                            Intent(this@TaskPollService, MainActivity::class.java).setAction("com.localfirst.assistant.ACTIVITY"),
                                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                        val notification = NotificationCompat.Builder(this@TaskPollService, TaskNotifications.CHANNEL)
                                            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("FRIDAY has a question")
                                            .setContentText("A PC task needs your answer. Open FRIDAY to reply.".take(180))
                                            .setContentIntent(open).setAutoCancel(true).build()
                                        manager.notify(key.hashCode(), notification)
                                        prefs.edit().putLong(key, System.currentTimeMillis()).apply(); sent++
                                    }
                                }
                            }
                            prefs.all.keys.filter { (it.startsWith("pcperm-") || it.startsWith("pcq-")) && it !in pendingKeys }.forEach {
                                prefs.edit().remove(it).apply()
                            }
                            val sessions = runCatching { JSONArray(client.request("/workspace/pc/sessions")) }.getOrNull() ?: JSONArray()
                            for (i in 0 until sessions.length()) {
                                val session = sessions.optJSONObject(i) ?: continue
                                val id = session.optString("id")
                                if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) continue
                                val key = "pc-$id"
                                val state = session.optString("state")
                                val previous = prefs.getString(key, null)
                                if (previous == null && state != "busy") { prefs.edit().putString(key, state).apply(); continue }
                                if (previous == "busy" && state != "busy" && prefs.getString("$key-notified", null) == null && sent < 5) {
                                    val open = PendingIntent.getActivity(this@TaskPollService, key.hashCode(),
                                        Intent(this@TaskPollService, MainActivity::class.java).setAction("com.localfirst.assistant.ACTIVITY"),
                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                    val text = when (state) {
                                        "idle" -> "Your PC task finished. Open FRIDAY to see the result."
                                        "waiting" -> "Your PC task needs your reply. Open FRIDAY to review it."
                                        else -> "Your PC task stopped ($state). Open FRIDAY for details."
                                    }
                                    val notification = NotificationCompat.Builder(this@TaskPollService, TaskNotifications.CHANNEL)
                                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                                        .setContentTitle(session.optString("title").ifBlank { "PC task" }.take(80))
                                        .setContentText(text).setContentIntent(open).setAutoCancel(true).build()
                                    manager.notify(key.hashCode(), notification)
                                    prefs.edit().putString("$key-notified", state).apply(); sent++
                                }
                                if (previous != state) prefs.edit().putString(key, state).apply()
                            }
                        }
                    } catch (_: Exception) { /* PC offline: leave seen state untouched. */ }
                }
                if (manager.areNotificationsEnabled() && !privacy.getBoolean("incognito", false)) {
                    // Proposals contain no account snapshot and cannot approve/start a task.
                    val proposals = JSONObject(client.request("/workspace/agent/briefing/proposals/refresh", "POST", JSONObject().put("scope", "")))
                    val items = proposals.getJSONArray("items")
                    if (proposals.optBoolean("notify") && !privacy.getBoolean("incognito", false)) for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val key = "brief-${item.getString("id") }"
                        if (prefs.contains(key) || sent >= 5 || privacy.getBoolean("incognito", false)) continue
                        val open = PendingIntent.getActivity(this@TaskPollService, key.hashCode(),
                            Intent(this@TaskPollService, MainActivity::class.java).setAction("com.localfirst.assistant.BRIEF"),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val notification = NotificationCompat.Builder(this@TaskPollService, TaskNotifications.CHANNEL)
                            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("FRIDAY")
                            .setContentText(if (item.optString("kind") == "morning") "Open FRIDAY for your morning brief." else "A check-in proposal is available. Open FRIDAY to review it.")
                            .setContentIntent(open).setAutoCancel(true).build()
                        manager.notify(key.hashCode(), notification)
                        prefs.edit().putLong(key, System.currentTimeMillis()).apply(); sent++
                    }
                    prefs.all.filter { (key, value) -> key.startsWith("brief-") && value is Long && value < System.currentTimeMillis() - 30L * 86400000L }.keys.forEach { prefs.edit().remove(it).apply() }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Offline: leave seen state untouched and retry at the next scheduled poll. */ }
            finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
