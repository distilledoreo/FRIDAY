@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.localfirst.assistant.ui.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.client.*
import com.localfirst.assistant.ui.*
import com.localfirst.assistant.ui.wire.JSONObject
import com.localfirst.assistant.ui.wire.JSONArray
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

val LocalOAuthLauncher = staticCompositionLocalOf<((String) -> Unit)?> { null }
val LocalFeatureImages = staticCompositionLocalOf<suspend (String) -> ImageBitmap?> { { null } }

@Composable
private fun ServiceScreen(title: String, vm: FeatureController, initialTask: String? = null, initialSession: String? = null, content: LazyListScope.(FeatureState) -> Unit) {
    val state by vm.state.collectAsState()
    LaunchedEffect(title, initialTask, initialSession) { vm.open(when(title) { "Library" -> "files"; "Images" -> "images"; "Accounts" -> "accounts"; else -> "activity" }, initialTask, initialSession) }
    FridayVisibleEffect(title) { while (true) { delay(3000); vm.refresh() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium); TextButton({ vm.refresh() }, enabled = !state.busy) { Text("Refresh") } } }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        content(state)
    }
}

@Composable
fun LibraryScreen(vm: FeatureController, open: (String) -> Unit, attach: (() -> Unit)? = null) {
    var deleting by remember { mutableStateOf<JsonObject?>(null) }
    ServiceScreen("Library", vm) { state ->
        if (attach != null) item { Button(attach, enabled = !state.busy) { Text("Upload files") } }
        if (state.files.isEmpty() && !state.busy) item { Text("Uploaded files and files created by FRIDAY appear here.") }
        items(state.files, key = { it.string("id") }) { file -> FridayCard(Modifier.fillMaxWidth()) {
            ListItem(headlineContent = { Text(file.string("name")) }, supportingContent = { Text("${file.string("size")} bytes · ${file.string("mime")}") },
                modifier = Modifier.clickable { open(file.string("id")) }, trailingContent = { MoreActions(listOf("Delete" to { deleting = file })) })
        } }
    }
    deleting?.let { file -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${file.string("name") }?") }, text = { Text("Chats or tasks using this file may no longer open it.") },
        confirmButton = { TextButton({ vm.change("/workspace/files/${file.string("id")}", "DELETE"); deleting = null }) { Text("Delete") } }, dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
}

@Composable
fun ServiceImage(path: String, label: String, modifier: Modifier = Modifier) {
    val loader = LocalFeatureImages.current
    val bitmap by produceState<ImageBitmap?>(null, path, loader) { value = runCatching { loader(path) }.getOrNull() }
    bitmap?.let { Image(it, label, modifier.heightIn(max = 480.dp), contentScale = ContentScale.Fit) } ?: Text("Preview loading or unavailable", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun ImagesScreen(vm: FeatureController, open: (String) -> Unit) {
    var prompt by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("768") }; var height by remember { mutableStateOf("768") }
    var steps by remember { mutableStateOf("8") }; var seed by remember { mutableStateOf("") }
    var transparent by remember { mutableStateOf(false) }; var references by remember { mutableStateOf(setOf<String>()) }
    val valid = width.toIntOrNull()?.let { it in 256..2000 && it % 16 == 0 } == true && height.toIntOrNull()?.let { it in 256..2000 && it % 16 == 0 } == true && steps.toIntOrNull() in 4..12 && (seed.isBlank() || seed.toIntOrNull()?.let { it >= 0 } == true) && (width.toLongOrNull() ?: 0) * (height.toLongOrNull() ?: 0) * (steps.toLongOrNull() ?: 0) <= 2000L * 2000 * 8
    ServiceScreen("Images", vm) { state ->
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(prompt, { prompt = it.take(4000) }, Modifier.fillMaxWidth(), label = { Text("Describe an image") })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Square" to (768 to 768), "Landscape" to (1024 to 576), "Portrait" to (576 to 1024)).forEach { (label, size) -> TextButton({ width = size.first.toString(); height = size.second.toString() }) { Text(label) } } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(width, { width = it.take(4) }, Modifier.weight(1f), label = { Text("Width") })
                OutlinedTextField(height, { height = it.take(4) }, Modifier.weight(1f), label = { Text("Height") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(steps, { steps = it.take(2) }, Modifier.weight(1f), label = { Text("Steps (4–12)") })
                OutlinedTextField(seed, { seed = it.take(10) }, Modifier.weight(1f), label = { Text("Seed (optional)") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Transparent background", Modifier.weight(1f)); Switch(transparent, { transparent = it }) }
            Text("Reference images · ${references.size}/2", style = MaterialTheme.typography.labelLarge)
            state.files.filter { it.string("mime").startsWith("image/") }.forEach { file -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(file.string("id") in references, { checked -> references = if (checked) references + file.string("id") else references - file.string("id") }, enabled = file.string("id") in references || references.size < 2); Text(file.string("name"))
            } }
            Button({ vm.change("/workspace/images", body = buildJsonObject { put("prompt", prompt); put("width", width.toInt()); put("height", height.toInt()); put("steps", steps.toInt()); put("seed", seed.toIntOrNull() ?: kotlin.random.Random.nextInt(Int.MAX_VALUE)); put("transparent", transparent); put("reference_file_ids", JsonArray(references.map(::JsonPrimitive))) }) }, enabled = valid && prompt.isNotBlank() && !state.busy) { Text("Generate image") }
            if (!valid) Text("Use dimensions 256–2000 in multiples of 16, 4–12 steps, and a non-negative seed. Large images allow fewer steps.")
        } }
        if (state.images.isEmpty()) item { Text("No generated images yet.") }
        items(state.images, key = { it.string("id") }) { job -> FridayCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(job.string("prompt"), style = MaterialTheme.typography.titleSmall); Text(job.string("phase").ifBlank { job.string("status") }.replace('_', ' '))
            (job["artifact"] as? JsonObject)?.let { artifact -> ServiceImage("/workspace/files/${artifact.string("id")}", "Generated image", Modifier.fillMaxWidth()); TextButton({ open(artifact.string("id")) }) { Text("Open or save image") } }
            job.string("error").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (job.string("status") !in listOf("completed", "failed", "cancelled")) TextButton({ vm.change("/workspace/images/${job.string("id")}/cancel") }, enabled = !state.busy) { Text("Cancel") }
            else TextButton({ vm.change("/workspace/images/${job.string("id")}", "DELETE") }, enabled = !state.busy) { Text("Remove from image history") }
        } } }
    }
}

@Composable
fun ActivityScreen(vm: FeatureController, initialTask: String? = null, initialSession: String? = null, discuss: (String) -> Unit = {}) {
    var prompt by remember { mutableStateOf("") }; var plan by remember { mutableStateOf("Read public sources\nPrepare a cited report") }
    var approval by remember { mutableStateOf<JsonObject?>(null) }; var outgoing by remember { mutableStateOf<JsonObject?>(null) }
    var followup by remember { mutableStateOf("") }; var runAt by remember { mutableStateOf("") }; var interval by remember { mutableStateOf("0") }; var count by remember { mutableStateOf("1") }
    var mailScopes by remember { mutableStateOf(setOf<String>()) }; var calendarScopes by remember { mutableStateOf(setOf<String>()) }
    var previewPc by remember { mutableStateOf(false) }
    val scheduleTime = runCatching {
        if (runAt.isBlank()) null else {
            val parts = runAt.trim().split(' '); require(parts.size == 2)
            require(com.localfirst.assistant.ui.genui.isoDateMillis(parts[0]) != null)
            val time = parts[1].split(':'); require(time.size == 2 && time[0].toInt() in 0..23 && time[1].toInt() in 0..59)
            resolveTaskStart(parts[0], time[0].toInt(), time[1].toInt(), com.localfirst.assistant.platform.PlatformSystem.currentTimeMillis()) / 1000.0
        }
    }.getOrNull()
    var after by remember { mutableStateOf(0L) }
    ServiceScreen("FRIDAY", vm, initialTask, initialSession) { state ->
        if (state.session != null) {
            val session = state.session!!; val id = session.string("id")
            item { TextButton({ vm.session(null) }) { Text("All activity") }; Text(session.string("title")); Text(session.string("state")); }
            items(session["items"]?.rows().orEmpty(), key = { it.string("id") }) { item -> Column { NativeResponseContent(item.string("text")); item.string("output").takeIf { it.isNotBlank() }?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } } } }
            items(session["permissions"]?.rows().orEmpty()) { permission -> PcPermissionCard(JSONObject(permission.toString()), !state.busy) { pid, reply, remember -> vm.change("/workspace/pc/permissions/$pid", body = buildJsonObject { put("reply", reply); put("remember", remember) }) } }
            items(session["questions"]?.rows().orEmpty()) { question -> PcQuestionCard(JSONObject(question.toString()), !state.busy) { qid, answers -> vm.change("/workspace/pc/questions/$qid" + if (answers == null) "/reject" else "", body = answers?.let { buildJsonObject { put("answers", it.json()) } }) } }
            item { OutlinedTextField(followup, { followup = it.take(20000) }, Modifier.fillMaxWidth(), label = { Text("Follow-up") }); Row {
                Button({ vm.change("/workspace/pc/sessions/$id/messages", body = buildJsonObject { put("prompt", followup) }); followup = "" }, enabled = !state.busy && followup.isNotBlank() && session.string("state") != "busy") { Text("Send") }
                TextButton({ vm.change("/workspace/pc/sessions/$id/abort") }, enabled = !state.busy) { Text("Stop") }
            } }
        } else if (state.task != null) {
            val task = state.task!!; val proposal = task["proposal"] as? JsonObject ?: JsonObject(emptyMap()); val id = task.string("id")
            item { TextButton({ after = 0; vm.task(null) }) { Text("All activity") }; Text(proposal.string("prompt"), style = MaterialTheme.typography.titleLarge); Text(task.string("status")); Text(describePlan(proposal)); Row {
                if (task.string("status") == "proposed") Button({ approval = task }, enabled = !state.busy && state.health.flag("ready")) { Text("Review and start") }
                if (task.string("status") in setOf("proposed", "approved", "running", "scheduled", "awaiting_setup")) TextButton({ vm.change("/workspace/agent/tasks/$id/cancel") }, enabled = !state.busy) { Text("Cancel task and schedule") }
            } }
            (task["result"] as? JsonObject)?.string("text")?.takeIf { it.isNotBlank() }?.let { report -> item { NativeResponseContent(report); TextButton({ discuss(report) }) { Text("Discuss in chat") } } }
            items(task["runs"]?.rows().orEmpty()) { run -> TextButton({ vm.task(run.string("id")) }) { Text("Run ${run.string("created")} · ${run.string("status")}") } }
            items(task["actions"]?.rows().orEmpty()) { action -> TextButton({ outgoing = action }, enabled = action.string("status") == "proposed" && !state.busy) { Text("${(action["payload"] as? JsonObject)?.string("kind")} · ${action.string("status")} · Review exact action") } }
            items(state.events, key = { it.string("seq") }) { event -> Column {
                Text(event.string("kind").replace('_', ' '), style = MaterialTheme.typography.labelLarge); SelectionContainer { Text(event["data"].toString(), style = MaterialTheme.typography.bodySmall) }
                if (event.string("kind") == "screenshot") (event["data"] as? JsonObject)?.let { ServiceImage("/workspace/agent/tasks/$id/screenshots/${it.string("id")}", "Page preview", Modifier.fillMaxWidth()) }
            } }
            item { Row { if (after > 0) TextButton({ after = 0; vm.refresh() }) { Text("First activity") }; if (state.events.size == 200) TextButton({ after = state.events.last().string("seq").toLong(); vm.refresh(after) }) { Text("More activity") } } }
        } else {
            item { Text("Your computer", style = MaterialTheme.typography.titleLarge);
            if (state.pc.flag("computer_use")) { TextButton({ previewPc = !previewPc }) { Text(if (previewPc) "Hide screen" else "View PC screen") }; if (previewPc) ServiceImage("/workspace/pc/screenshot", "PC screen", Modifier.fillMaxWidth()) }; Text(state.pc.string("detail").ifBlank { if (state.pc.flag("ready")) "Ready" else "Unavailable · check computer access settings" }); OutlinedTextField(prompt, { prompt = it.take(8000) }, Modifier.fillMaxWidth(), label = { Text("Give FRIDAY a task") }); Button({ vm.startPc(prompt); prompt = "" }, enabled = prompt.isNotBlank() && !state.busy && state.pc.flag("enabled")) { Text("Start on your computer") } }
            items(state.sessions, key = { "pc-" + it.string("id") }) { session -> TextButton({ vm.session(session.string("id")) }) { Text("${session.string("title")} · ${session.string("state")}") } }
            item { Text("Isolated research", style = MaterialTheme.typography.titleLarge); Text(state.health.string("detail")); Text("Only account reads selected below go to the cloud model. Public web tools are disabled for account-data tasks.", style = MaterialTheme.typography.bodySmall)
            state.accounts.forEach { account ->
                Text(account.string("label"))
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(account.string("id") in mailScopes, { checked -> mailScopes = if (checked) mailScopes + account.string("id") else mailScopes - account.string("id") }); Text("Share up to 10 inbox headers") }
                if (account.string("provider") != "imap") Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(account.string("id") in calendarScopes, { checked -> calendarScopes = if (checked) calendarScopes + account.string("id") else calendarScopes - account.string("id") }); Text("Share up to 30 events in next 7 days") }
            }
            OutlinedTextField(plan, { plan = it.take(12000) }, Modifier.fillMaxWidth(), label = { Text("Plan · one step per line") }); OutlinedTextField(runAt, { runAt = it }, Modifier.fillMaxWidth(), label = { Text("Start: YYYY-MM-DD HH:mm (optional)") }); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { listOf("Once" to "0", "Hourly" to "3600", "Daily" to "86400", "Weekly" to "604800").forEach { (label, value) -> FilterChip(interval == value, { interval = value }, label = { Text(label) }) } }; OutlinedTextField(count, { count = it }, Modifier.weight(1f), label = { Text("Run count") })
            }; Button({ vm.change("/workspace/agent/tasks", body = buildJsonObject { put("prompt", prompt); put("plan", JsonArray(plan.lines().map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive)));
                    put("data_scopes", buildJsonArray {
                        val now = com.localfirst.assistant.platform.PlatformSystem.currentTimeMillis().toDouble()
                        mailScopes.forEach { id -> add(buildJsonObject { put("account_id", id); put("kind", "inbox"); put("limit", 10) }) }
                        calendarScopes.forEach { id -> add(buildJsonObject { put("account_id", id); put("kind", "calendar"); put("limit", 30); put("start", com.localfirst.assistant.platform.PlatformTime.isoString(now)); put("end", com.localfirst.assistant.platform.PlatformTime.isoString(now + 7 * 86400000.0)) }) }
                    }); if (runAt.isNotBlank()) put("schedule", buildJsonObject { put("run_at", scheduleTime!!); put("interval_seconds", interval.toInt()); put("max_runs", count.toInt()) }) }) }, enabled = !state.busy && prompt.isNotBlank() && plan.isNotBlank() && mailScopes.size + calendarScopes.size <= 5 && (runAt.isBlank() || scheduleTime != null && interval.toIntOrNull() != null && count.toIntOrNull() != null)) { Text("Save plan for review") } }
            items(state.tasks, key = { "agent-" + it.string("id") }) { task -> TextButton({ vm.task(task.string("id")) }) { Text("${(task["proposal"] as? JsonObject)?.string("prompt")} · ${task.string("status")}") } }
            items(state.schedules, key = { "schedule-" + it.string("id") }) { schedule -> Row { Text(schedule.string("prompt"), Modifier.weight(1f)); TextButton({ vm.change("/workspace/pc/schedules/${schedule.string("id")}", "DELETE") }) { Text("Cancel schedule") } } }
            item { Text("Computer access", style = MaterialTheme.typography.titleMedium); Text("Changing access stops active computer sessions.");
            FlowRow { listOf("ask" to "Ask for every action", "ask_changes" to "Ask before changes", "full" to "Full access").forEach { (mode, label) -> FilterChip(state.pc.string("mode") == mode, { vm.change("/workspace/pc/settings", "PUT", JsonObject(state.pc.filterKeys { it in setOf("enabled", "computer_use", "mode", "allow", "deny") } + ("mode" to JsonPrimitive(mode)))) }, label = { Text(label) }, enabled = !state.busy) } }; listOf("enabled" to "PC access", "computer_use" to "Desktop control").forEach { (key, label) -> Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(state.pc.flag(key), { enabled -> vm.change("/workspace/pc/settings", "PUT", JsonObject(state.pc.filterKeys { it in setOf("enabled", "computer_use", "mode", "allow", "deny") } + (key to JsonPrimitive(enabled)))) }, enabled = !state.busy && state.pc.isNotEmpty()) } } }
            // Tool servers the PC's mcp.json connects: a new or changed one waits here until confirmed, as on the phone.
            state.mcp?.get("servers")?.rows()?.let { servers -> item {
                Text("Connected services", style = MaterialTheme.typography.titleSmall)
                Text("Operator-configured tool servers from the PC's mcp.json. A new or changed server waits here until you confirm it; its tool calls still ask approval one by one afterwards.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                if (servers.isEmpty()) Text("No service connections configured.")
                servers.forEach { server ->
                    val name = server.string("name")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(name); Text(server.string("type"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                        if (server.flag("confirmed")) Text("Confirmed", style = MaterialTheme.typography.labelMedium)
                        else TextButton({ vm.change("/workspace/pc/mcp/${com.localfirst.assistant.platform.PlatformNet.encodeUrl(name)}/confirm") }, enabled = !state.busy) { Text("Confirm") }
                    }
                }
            } }
        }
    }
    approval?.let { exact -> AlertDialog(onDismissRequest = { approval = null }, title = { Text("Start this exact plan?") }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(describePlan(exact["proposal"] as? JsonObject ?: JsonObject(emptyMap()))); Text("FRIDAY sends this request and the pages she reads to a cloud model. Account reads require the exact scopes in this plan.") } }, confirmButton = { TextButton({ vm.change("/workspace/agent/tasks/${exact.string("id")}/approve", body = buildJsonObject { put("fingerprint", exact.string("fingerprint")) }); approval = null }) { Text("Start") } }, dismissButton = { TextButton({ approval = null }) { Text("Back") } }) }
    outgoing?.let { exact -> val review = exact["review"] as? JsonObject
        AlertDialog(onDismissRequest = { outgoing = null }, title = { Text("Approve this exact action?") }, text = { SelectionContainer { Text("${exact["payload"]}\n\n$review\n\nSubmit once. An uncertain receipt requires checking provider records before another proposal.", Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) } }, confirmButton = { if (review?.flag("executable") == true) TextButton({ vm.change("/workspace/agent/actions/${exact.string("id")}/approve", body = buildJsonObject { put("fingerprint", exact.string("fingerprint")); put("review_fingerprint", review.string("review_fingerprint")) }); outgoing = null }) { Text("Approve once") } }, dismissButton = { TextButton({ outgoing = null }) { Text("Back") } })
    }
}

@Composable
fun AccountsScreen(vm: FeatureController) {
    val uri = LocalUriHandler.current
    val oauthLaunch = LocalOAuthLauncher.current
    var setup by remember { mutableStateOf<String?>(null) }; var clientId by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<JsonObject?>(null) }
    var email by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var host by remember { mutableStateOf("") }
    var smtpHost by remember { mutableStateOf("") }; var smtpPort by remember { mutableStateOf("587") }; var smtpUser by remember { mutableStateOf("") }; var smtpPassword by remember { mutableStateOf("") }
    var mail by remember { mutableStateOf(true) }; var calendar by remember { mutableStateOf(true) }; var send by remember { mutableStateOf(false) }; var write by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }; var returnedState by remember { mutableStateOf("") }
    ServiceScreen("Accounts", vm) { state ->
        item { Text("Connect email and calendar accounts to FRIDAY. Credentials stay in the computer’s vault. Sending and calendar changes require separate, exact approvals.")
            listOf("Read mail" to (mail to { v: Boolean -> mail = v }), "Read calendar" to (calendar to { v: Boolean -> calendar = v }), "Send mail" to (send to { v: Boolean -> send = v }), "Write calendar" to (write to { v: Boolean -> write = v })).forEach { (label, choice) -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(choice.first, choice.second); Text(label) } }
        }
        items(state.providers, key = { it.string("provider") }) { provider -> Column {
            Text(provider.string("provider").replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleMedium)
            Row { TextButton({ setup = provider.string("provider") }) { Text("Configure OAuth application") }
                Button({ vm.beginOAuth(provider.string("provider"), listOfNotNull("mail_read".takeIf { mail }, "calendar_read".takeIf { calendar }, "mail_send".takeIf { send }, "calendar_write".takeIf { write })) }, enabled = !state.busy && provider.flag("configured") && (mail || calendar || send || write) && state.oauth == null) { Text("Sign in") }
            }
        } }
        state.oauth?.let { flow -> item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            flow.string("authorization_url").takeIf { it.startsWith("https://") }?.let { url -> Button({ if (oauthLaunch != null) oauthLaunch(url) else uri.openUri(url) }) { Text("Open provider sign-in") } }
                ?: Text("Complete sign-in in the Android authorization dialog.")
            OutlinedTextField(code, { code = it.take(8192) }, Modifier.fillMaxWidth(), label = { Text("Authorization code") }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(returnedState, { returnedState = it.take(128) }, Modifier.fillMaxWidth(), label = { Text("State from provider redirect") })
            Row { Button({ vm.finishOAuth(code, returnedState)?.invokeOnCompletion { code = ""; returnedState = "" } }, enabled = code.isNotBlank() && returnedState.isNotBlank() && !state.busy) { Text("Complete sign-in") }; TextButton({ vm.cancelOAuth(); code = ""; returnedState = "" }) { Text("Cancel") } }
        } } }
        item { Text("Other email provider", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(email, { email = it.take(320) }, Modifier.fillMaxWidth(), label = { Text("Email") })
            OutlinedTextField(username, { username = it.take(320) }, Modifier.fillMaxWidth(), label = { Text("IMAP username") })
            OutlinedTextField(host, { host = it.take(253) }, Modifier.fillMaxWidth(), label = { Text("Public IMAP server (TLS)") })
            OutlinedTextField(password, { password = it.take(1024) }, Modifier.fillMaxWidth(), label = { Text("App password") }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(smtpHost, { smtpHost = it.take(253) }, Modifier.fillMaxWidth(), label = { Text("SMTP server (optional)") })
            if (smtpHost.isNotBlank()) { OutlinedTextField(smtpPort, { smtpPort = it.take(5) }, label = { Text("SMTP port") }); OutlinedTextField(smtpUser, { smtpUser = it.take(320) }, label = { Text("SMTP username") }); OutlinedTextField(smtpPassword, { smtpPassword = it.take(1024) }, label = { Text("SMTP password") }, visualTransformation = PasswordVisualTransformation()) }
            Button({ vm.change("/workspace/agent/accounts/imap", body = buildJsonObject { put("email", email); put("username", username); put("password", password); put("imap_host", host); if (smtpHost.isNotBlank()) { put("smtp_host", smtpHost); put("smtp_port", smtpPort.toInt()); put("smtp_username", smtpUser.ifBlank { username }); put("smtp_password", smtpPassword.ifBlank { password }) } })?.invokeOnCompletion { password = ""; smtpPassword = "" } }, enabled = !state.busy && email.isNotBlank() && username.isNotBlank() && host.isNotBlank() && password.isNotBlank() && (smtpHost.isBlank() || smtpPort.toIntOrNull() in 1..65535)) { Text("Connect mail account") }
        }
        if (state.accounts.isEmpty()) item { Text("No connected accounts.") }
        items(state.accounts, key = { it.string("id") }) { account -> FridayCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(account.string("label")); Text(account.string("provider"), style = MaterialTheme.typography.labelMedium) }; TextButton({ vm.readAccount("/workspace/agent/accounts/${account.string("id")}/mail") }) { Text("Inbox") }
                if (account.string("provider") != "imap") TextButton({
                    val now = com.localfirst.assistant.platform.PlatformSystem.currentTimeMillis().toDouble()
                    fun encoded(value: Double) = com.localfirst.assistant.platform.PlatformNet.encodeUrl(com.localfirst.assistant.platform.PlatformTime.isoString(value))
                    vm.readAccount("/workspace/agent/accounts/${account.string("id")}/calendar?start=${encoded(now)}&end=${encoded(now + 7 * 86400000.0)}")
                }) { Text("Calendar") }
                TextButton({ removing = account }) { Text("Remove") } } } }
        state.accountContent?.let { content ->
            items(content["messages"]?.rows().orEmpty(), key = { "mail-" + it.string("id") }) { message -> TextButton({ vm.readAccount("/workspace/agent/accounts/${content.string("account_id")}/mail/" + com.localfirst.assistant.platform.PlatformNet.encodeUrl(message.string("id"))) }) { Text("${message.string("subject")} · ${message.string("from")}") } }
            (content["message"] as? JsonObject)?.let { message -> item { Text(message.string("subject"), style = MaterialTheme.typography.titleLarge); SelectionContainer { Text(message.string("body")) } } }
            items(content["events"]?.rows().orEmpty(), key = { "event-" + it.string("id") }) { event -> Text("${event.string("title")} · ${event["start"]} · ${event.string("location")}") }
        }
    }
    setup?.let { provider -> AlertDialog(onDismissRequest = { setup = null; secret = "" }, title = { Text("Configure $provider") }, text = { Column { OutlinedTextField(clientId, { clientId = it.take(300) }, label = { Text("Client ID") }); if (provider == "google") OutlinedTextField(secret, { secret = it.take(500) }, label = { Text("Client secret") }, visualTransformation = PasswordVisualTransformation()) } }, confirmButton = { TextButton({ vm.change("/workspace/agent/accounts/config/$provider", body = buildJsonObject { put("client_id", clientId); if (provider == "google") put("client_secret", secret) }); secret = ""; setup = null }) { Text("Save") } }, dismissButton = { TextButton({ secret = ""; setup = null }) { Text("Cancel") } }) }
    removing?.let { account -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${account.string("label")}?") }, text = { Text("Provider grants can also be revoked in the provider’s account settings.") }, confirmButton = { TextButton({ vm.change("/workspace/agent/accounts/${account.string("id")}", "DELETE"); removing = null }) { Text("Remove") } }, dismissButton = { TextButton({ removing = null }) { Text("Cancel") } }) }
}

private fun describePlan(proposal: JsonObject): String = buildString {
    append(proposal.string("prompt"))
    (proposal["plan"] as? JsonArray)?.forEachIndexed { index, step -> append("\n${index + 1}. ${step.jsonPrimitive.content}") }
    (proposal["schedule"] as? JsonObject)?.let { schedule ->
        val seconds = schedule.string("run_at").toDoubleOrNull()
        append("\nStarts: " + seconds?.let { com.localfirst.assistant.platform.PlatformTime.displayString(it * 1000) })
        val interval = schedule.string("interval_seconds").toIntOrNull() ?: 0
        if (interval > 0) append("\nEvery ${interval / 3600} hours · ${schedule.string("max_runs")} runs")
    }
    proposal["data_scopes"]?.rows()?.forEach { read -> append("\nAccount ${read.string("account_id")}: ${read.string("kind")} · up to ${read.string("limit")} records") }
}
