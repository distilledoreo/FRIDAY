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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            try {
                val client = WorkspaceClient(applicationContext) { ServerSettingsStore(applicationContext).load() }
                val tasks = JSONArray(client.request("/workspace/jobs"))
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
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Offline: leave seen state untouched and retry at the next scheduled poll. */ }
            finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
