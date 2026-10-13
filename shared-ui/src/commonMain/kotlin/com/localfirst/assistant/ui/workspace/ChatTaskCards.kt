package com.localfirst.assistant.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.presentation.ToolStep
import com.localfirst.assistant.ui.FridayCard
import com.localfirst.assistant.ui.LineIcons
import com.localfirst.assistant.ui.NativeResponseContent
import com.localfirst.assistant.ui.code.CodeChatCard
import com.localfirst.assistant.ui.code.CodeWorkspace
import com.localfirst.assistant.client.FeatureController
import com.localfirst.assistant.client.FRIDAY_WORKING
import kotlinx.serialization.json.*

private fun parse(json: String) = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * What a tool step started from chat shows beneath it: a generated image, FRIDAY proposals and PC tasks that open
 * in FRIDAY, coding tasks that open in Code, otherwise the result itself.
 */
@Composable
fun ChatToolResult(step: ToolStep, openArtifact: (String) -> Unit, openFriday: (task: String?, session: String?) -> Unit,
                   code: CodeWorkspace?, openCode: (String) -> Unit, features: FeatureController? = null, discuss: ((String) -> Unit)? = null) {
    when {
        step.imageResult != null -> GeneratedImageCard(step.imageResult!!, openArtifact)
        step.agentTask != null && features != null -> FridayTaskCard(step.agentTask!!, features, openFriday, discuss)
        step.pcTask != null && features != null -> PcChatCard(step.pcTask!!, features, openFriday, discuss)
        step.codeTask != null && code != null -> CodeChatCard(step.codeTask!!, code, openCode)
        else -> step.resultContent?.let { NativeResponseContent(it.take(2000)) }
    }
}

/** Ids of the PC sessions and FRIDAY tasks a transcript started, newest last, for refreshing their cards. */
fun chatWorkIds(steps: List<ToolStep>): Pair<List<String>, List<String>> =
    steps.mapNotNull { step -> step.pcTask?.let(::parse)?.let { ((it["session"] as? JsonObject) ?: it).text("id").takeIf(String::isNotBlank) } } to
        steps.mapNotNull { step -> step.agentTask?.let(::parse)?.text("id")?.takeIf(String::isNotBlank) }

private fun pcState(value: String) = when (value) {
    "busy" -> "Working"; "waiting" -> "Needs your reply"; "idle" -> "Finished"; "cancelled" -> "Stopped"
    "interrupted" -> "Interrupted"; "error" -> "Could not finish"; else -> value.replaceFirstChar(Char::uppercase)
}

/** A PC session started from chat: live status, its permission and question cards answered in place, and a way into FRIDAY. */
@Composable
fun PcChatCard(json: String, features: FeatureController, openFriday: (String?, String?) -> Unit, discuss: ((String) -> Unit)?) {
    val stored = remember(json) { parse(json) } ?: return
    val id = ((stored["session"] as? JsonObject) ?: stored).text("id").takeIf { Regex("[A-Za-z0-9_-]{1,100}").matches(it) } ?: return
    val state by features.state.collectAsState()
    val full = state.chatPc[id]
    val status = full?.text("state") ?: stored.text("state").ifBlank { "busy" }
    val permissions = (full?.get("permissions") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val questions = (full?.get("questions") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    val waiting = status == "waiting" || permissions.isNotEmpty() || questions.isNotEmpty()
    val busy = (full?.get("busy") as? JsonPrimitive)?.booleanOrNull ?: (status == "busy")
    // Live while it works or waits on you; quiet once it has finished.
    LaunchedEffect(id, busy || waiting) {
        do { features.refreshChatWork(listOf(id), emptyList())?.join(); kotlinx.coroutines.delay(3000) } while (busy || waiting)
    }
    com.localfirst.assistant.ui.FridayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LineIcons.Screen, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
                Text("PC task", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(if (waiting) "Needs your reply" else pcState(status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text((full?.text("title") ?: stored.text("title")).ifBlank { "PC task" }, style = MaterialTheme.typography.bodyMedium)
            full?.text("model")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (busy && !waiting) Text("Working on your computer…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            permissions.forEach { request -> key(request.text("id")) {
                com.localfirst.assistant.ui.PcPermissionCard(com.localfirst.assistant.ui.wire.JSONObject(request.toString()), !state.busy) { pid, reply, remember ->
                    features.chatAction("/workspace/pc/permissions/$pid", buildJsonObject { put("reply", reply); put("remember", remember) }, pcIds = listOf(id))
                }
            } }
            questions.forEach { question -> key(question.text("id")) {
                com.localfirst.assistant.ui.PcQuestionCard(com.localfirst.assistant.ui.wire.JSONObject(question.toString()), !state.busy) { qid, answers ->
                    features.chatAction("/workspace/pc/questions/$qid" + if (answers == null) "/reject" else "", answers?.let { buildJsonObject { put("answers", it.json()) } }, pcIds = listOf(id))
                }
            } }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ openFriday(null, id) }) { Text(if (waiting) "Review" else "Open") }
                discuss?.let { TextButton({ it("Read PC task $id using get_pc_task, then help me with its results.") }) { Text("Read into chat") } }
                TextButton({ features.refreshChatWork(listOf(id), emptyList()) }, enabled = !state.chatWorkBusy) { Text("Refresh") }
            }
        }
    }
}

/** FRIDAY's plan proposed from chat: start it, look closer, dismiss it, or read its report. */
@Composable
fun FridayTaskCard(json: String, features: FeatureController, openFriday: (String?, String?) -> Unit, discuss: ((String) -> Unit)?) {
    val proposed = remember(json) { parse(json) } ?: return
    val id = proposed.text("id").takeIf { Regex("[a-f0-9]{32}").matches(it) } ?: return
    val state by features.state.collectAsState()
    val task = state.chatTasks[id] ?: proposed
    val proposal = task["proposal"] as? JsonObject ?: return
    val status = task.text("status")
    val sharesAccountData = ((proposal["data_scopes"] as? JsonArray)?.size ?: 0) > 0
    LaunchedEffect(id, status in FRIDAY_WORKING) {
        do { features.refreshChatWork(emptyList(), listOf(id))?.join(); kotlinx.coroutines.delay(5000) } while (status in FRIDAY_WORKING)
    }
    com.localfirst.assistant.ui.FridayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LineIcons.Friday, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
                Text("FRIDAY’s plan", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(com.localfirst.assistant.ui.dashboard.agentStatus(status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(proposal.text("prompt"), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            (proposal["plan"] as? JsonArray)?.take(6)?.forEachIndexed { i, step -> Text("${i + 1}. ${(step as? JsonPrimitive)?.contentOrNull.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
            if (proposal["schedule"] is JsonObject) Text("Scheduled · review timing in FRIDAY", style = MaterialTheme.typography.bodySmall)
            if (sharesAccountData) Text("Shares selected account data with the cloud model.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            if (status in FRIDAY_WORKING) Text(if (status == "approved") "Starting on her computer…" else "Working on her computer…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                when (status) {
                    "proposed" -> {
                        // Account data scopes need the exact review in FRIDAY before anything starts.
                        Button({ if (sharesAccountData) openFriday(id, null) else features.chatAction("/workspace/agent/tasks/$id/approve", buildJsonObject { put("fingerprint", task.text("fingerprint")) }, taskIds = listOf(id)) },
                            enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(if (sharesAccountData) "Review" else if (proposal["schedule"] is JsonObject) "Schedule it" else "Start") }
                        TextButton({ openFriday(id, null) }) { Text("Details") }
                        TextButton({ features.chatAction("/workspace/agent/tasks/$id/cancel", taskIds = listOf(id)) }, enabled = !state.busy) { Text("Dismiss") }
                    }
                    "approved", "running" -> TextButton({ openFriday(id, null) }) { Text("Watch") }
                    "done" -> {
                        TextButton({ openFriday(id, null) }) { Text("See report") }
                        discuss?.let { TextButton({ it("Read the report for FRIDAY task $id using get_agent_report, then help me discuss its findings.") }) { Text("Discuss") } }
                    }
                    else -> TextButton({ openFriday(id, null) }) { Text("Open in FRIDAY") }
                }
            }
        }
    }
}

@Composable
private fun GeneratedImageCard(result: String, openArtifact: (String) -> Unit) {
    val id = remember(result) { (parse(result)?.get("artifact") as? JsonObject)?.text("id") }?.takeIf { Regex("[a-f0-9]{32}").matches(it) } ?: return
    val loader = LocalFeatureImages.current
    var image by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) { runCatching { loader("/workspace/files/$id") }.onSuccess { image = it; failed = it == null }.onFailure { failed = true } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        image?.let { Image(it, "Generated image", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).clickable { openArtifact(id) }) }
            ?: if (!failed) LinearProgressIndicator(Modifier.fillMaxWidth()) else Text("Preview unavailable.", style = MaterialTheme.typography.bodySmall)
        TextButton({ openArtifact(id) }) { Text("Open or save image") }
    }
}
