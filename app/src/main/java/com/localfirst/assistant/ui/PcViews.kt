@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.localfirst.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private fun pcRows(value: JSONObject?, key: String): List<JSONObject> = value?.optJSONArray(key)?.let { array ->
    (0 until array.length()).mapNotNull(array::optJSONObject)
}.orEmpty()
private fun pcStrings(value: JSONObject, key: String): List<String> = value.optJSONArray(key)?.let { array ->
    (0 until array.length()).map { array.optString(it) }
}.orEmpty()
private fun pcState(value: String) = when(value) {
    "busy" -> "Working"; "waiting" -> "Needs your reply"; "idle" -> "Finished"; "cancelled" -> "Stopped"
    "interrupted" -> "Interrupted"; "error" -> "Could not finish"; else -> value.replaceFirstChar(Char::uppercase)
}

@Composable
internal fun PcHomePanel(state: ChatUiState, vm: ChatViewModel) {
    LaunchedEffect(Unit) { vm.refreshPc(null) }
    val active=state.pcSessions.any { it.optString("state")=="busy" }
    FridayVisibleEffect(active) { if(active)while(true) { delay(2500);vm.refreshPc(null) } }
    FridayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Your computer",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={vm.openWorkspace(WorkspaceDestination.COMPUTER_ACCESS)}) { Text("Access") }
            }
            val status=state.pcStatus
            Text(when {
                status==null -> "Checking native PC access…"
                !status.optBoolean("enabled") -> "PC access is off"
                status.optBoolean("ready") -> "Native OpenCode · shell and desktop"
                else -> "Native OpenCode is unavailable"
            },style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            state.pcError?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
            if(status?.optBoolean("enabled")==false)TextButton(onClick={vm.openWorkspace(WorkspaceDestination.COMPUTER_ACCESS)}) { Text("Configure access") }
            else TextButton(onClick={vm.refreshPc(null)}) { Text("Refresh PC tasks") }
            if(status?.optBoolean("enabled")==true&&status.optBoolean("computer_use"))TextButton(onClick=vm::capturePcScreen,enabled=!state.pcBusy) { Text("View PC screen") }
            state.pcSessions.take(8).forEach { task ->
                Row(Modifier.fillMaxWidth().clickable { vm.openPcTask(task.getString("id")) }.heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(task.optString("title").ifBlank { "PC task" },style=MaterialTheme.typography.bodyLarge)
                        Text(pcState(task.optString("state")),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›",style=MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
    PcScreenPreview(state,vm)
}

@Composable
internal fun PcSettingsPage(state: ChatUiState, vm: ChatViewModel) {
    LaunchedEffect(Unit) { vm.refreshPc(null) }
    val status=state.pcStatus
    val fingerprint=status?.let { value -> listOf("enabled","mode","computer_use","allow","deny").joinToString { value.opt(it).toString() } }
    var enabled by remember(fingerprint) { mutableStateOf(status?.optBoolean("enabled") ?: true) }
    var computer by remember(fingerprint) { mutableStateOf(status?.optBoolean("computer_use") ?: true) }
    var mode by remember(fingerprint) { mutableStateOf(status?.optString("mode") ?: "ask_changes") }
    var allow by remember(fingerprint) { mutableStateOf(status?.let { pcStrings(it,"allow").joinToString("\n") }.orEmpty()) }
    var deny by remember(fingerprint) { mutableStateOf(status?.let { pcStrings(it,"deny").joinToString("\n") }.orEmpty()) }
    val editable=status!=null&&!state.pcBusy
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Native access to your PC",style=MaterialTheme.typography.titleMedium)
        Text("FRIDAY uses OpenCode on the computer itself. Free cloud models can see relevant command output, files and screenshots; local Qwen is the text-only fallback.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        state.pcError?.let { Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick={vm.refreshPc(null)}) { Text("Try again") } }
        PcSwitch("PC access",enabled,editable) { enabled=it }
        PcSwitch("Desktop screenshots and input",computer,editable&&enabled) { computer=it }
        Text("Permissions",style=MaterialTheme.typography.titleSmall)
        listOf("ask" to "Ask every time", "ask_changes" to "Ask before changes", "full" to "Full access").forEach { (value,label) ->
            Row(Modifier.fillMaxWidth().heightIn(min=52.dp).clickable(enabled=editable) { mode=value },verticalAlignment=Alignment.CenterVertically) {
                RadioButton(mode==value,onClick={mode=value},enabled=editable)
                Text(label,style=MaterialTheme.typography.bodyLarge)
            }
        }
        Text("Ask before changes allows ordinary reads; commands that change things and desktop input ask first. Full access runs without those prompts; very destructive commands still ask. Deny rules take precedence.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(allow,{allow=it},label={Text("Allow rules")},supportingText={Text("One shell pattern per line, such as gh pr list *. ‘computer’ allows desktop input.")},modifier=Modifier.fillMaxWidth(),enabled=editable,minLines=2,maxLines=6)
        OutlinedTextField(deny,{deny=it},label={Text("Deny rules")},supportingText={Text("One shell pattern per line, such as git push *.")},modifier=Modifier.fillMaxWidth(),enabled=editable,minLines=2,maxLines=6)
        status?.let { Text(if(it.optBoolean("sudo_ready"))"Sudo is available through the PC’s existing setup." else "Sudo currently needs the PC’s own authorization/setup. FRIDAY will not request or store your password.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("Saving changed access settings stops active PC tasks. Existing task history is retained.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick={
            vm.savePcSettings(JSONObject().put("enabled",enabled).put("computer_use",computer).put("mode",mode)
                .put("allow",JSONArray(allow.lines().map(String::trim).filter(String::isNotBlank)))
                .put("deny",JSONArray(deny.lines().map(String::trim).filter(String::isNotBlank))))
        },enabled=editable,shape=MaterialTheme.shapes.small) { Text("Save access settings") }
    }
}

@Composable
private fun PcSwitch(label:String,value:Boolean,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
        Switch(value,onCheckedChange=onChange,enabled=enabled,modifier=Modifier.semantics { contentDescription=label })
    }
}

@Composable
internal fun PcTaskPage(state: ChatUiState, vm: ChatViewModel) {
    val id=state.pcSessionId ?: return
    BackHandler(onBack=vm::closePcTask)
    LaunchedEffect(id) { vm.refreshPc(id) }
    val active=state.pcSession==null||state.pcSession?.optBoolean("busy")==true||state.pcSession?.optString("state")=="waiting"
    FridayVisibleEffect(id,active) { if(active)while(true) { delay(2000);vm.refreshPc(id) } }
    PcTaskContent(state.pcSession,state.pcBusy,state.pcError,vm::closePcTask,{vm.refreshPc(id)},vm::cancelPcTask,
        vm::replyPcPermission,vm::answerPcQuestion,vm::sendPcMessage,
        (vm::capturePcScreen).takeIf { state.pcStatus?.optBoolean("enabled")==true&&state.pcStatus.optBoolean("computer_use") })
    PcScreenPreview(state,vm)
}

/** Native task results and human controls; every action delegates to an actual server route. */
@Composable
internal fun PcTaskContent(task:JSONObject?,busy:Boolean,error:String?,onBack:()->Unit,onRefresh:()->Unit,onCancel:()->Unit,
    onPermission:(String,String,Boolean)->Unit,onQuestion:(String,JSONArray?)->Unit,onSend:(String)->Unit,onViewScreen:(()->Unit)?=null) {
    var message by rememberSaveable(task?.optString("id")) { mutableStateOf("") }
    val permissions=pcRows(task,"permissions")
    val questions=pcRows(task,"questions")
    val working=task?.optBoolean("busy")==true
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)) {
        item {
            TextButton(onClick=onBack) { Text("Back to FRIDAY") }
            Text(task?.optString("title")?.ifBlank { "PC task" } ?: "Loading PC task…",style=MaterialTheme.typography.titleLarge)
            task?.let { Text("${pcState(it.optString("state"))} · ${it.optString("model")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            FlowRow { onViewScreen?.let { TextButton(onClick=it,enabled=!busy) { Text("View PC screen") } };TextButton(onClick=onRefresh,enabled=!busy) { Text("Refresh") };if(working)TextButton(onClick=onCancel,enabled=!busy) { Text("Stop task") } }
        }
        items(permissions,key={"permission-${it.optString("id")}"}) { request -> PcPermissionCard(request,!busy,onPermission) }
        items(questions,key={"question-${it.optString("id")}"}) { question -> PcQuestionCard(question,!busy,onQuestion) }
        items(pcRows(task,"items"),key={it.getString("id")}) { item ->
            FridayCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(item.optString("kind")=="tool") {
                        Text(item.optString("title"),style=MaterialTheme.typography.titleSmall)
                        Text(item.optString("status"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        item.optString("output").takeIf(String::isNotBlank)?.let { SelectionContainer { Text(it,style=MaterialTheme.typography.bodySmall) } }
                    } else SelectionContainer { Text(item.optString("text"),style=MaterialTheme.typography.bodyMedium,color=if(item.optString("kind")=="error")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
        if(task!=null)item {
            OutlinedTextField(message,{message=it.take(20000)},label={Text("Continue this PC task")},modifier=Modifier.fillMaxWidth(),enabled=!busy&&!working&&permissions.isEmpty()&&questions.isEmpty(),minLines=1,maxLines=5)
            Button(onClick={val text=message.trim();if(text.isNotEmpty()){onSend(text);message=""}},enabled=!busy&&!working&&message.isNotBlank()&&permissions.isEmpty()&&questions.isEmpty(),shape=MaterialTheme.shapes.small) { Text("Send to FRIDAY") }
        }
    }
    }
}

@Composable
internal fun PcPermissionCard(request:JSONObject,enabled:Boolean,onReply:(String,String,Boolean)->Unit) {
    var rememberRule by remember(request.optString("id")) { mutableStateOf(false) }
    val patterns=pcStrings(request,"always")
    val id=request.getString("id")
    FridayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(if(request.optBoolean("sudo"))"Approve sudo command" else "FRIDAY needs your permission",style=MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(request.optString("detail"),style=MaterialTheme.typography.bodyMedium) }
            request.optString("diff").takeIf { it.isNotBlank()&&it!="null" }?.let { SelectionContainer { Text(it,style=MaterialTheme.typography.bodySmall) } }
            if(patterns.isNotEmpty()) {
                Text("Always allows: ${patterns.joinToString(", ")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(request.optString("kind") in listOf("bash","friday-computer_action"))Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(rememberRule,{rememberRule=it},enabled=enabled,modifier=Modifier.semantics { contentDescription="Remember for future tasks" })
                    Text("Remember for future tasks",style=MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={onReply(id,"once",false)},enabled=enabled,shape=MaterialTheme.shapes.small) { Text("Allow once") }
                if(patterns.isNotEmpty())TextButton(onClick={onReply(id,"always",rememberRule)},enabled=enabled) { Text("Always for task") }
                TextButton(onClick={onReply(id,"reject",false)},enabled=enabled) { Text("Deny") }
            }
        }
    }
}

@Composable
internal fun PcQuestionCard(request:JSONObject,enabled:Boolean,onAnswer:(String,JSONArray?)->Unit) {
    val questions=pcRows(request,"questions")
    var answers by remember(request.optString("id")) { mutableStateOf(List(questions.size) { emptyList<String>() }) }
    var custom by remember(request.optString("id")) { mutableStateOf(List(questions.size) { "" }) }
    FridayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            questions.forEachIndexed { index,question ->
                Text(question.optString("question"),style=MaterialTheme.typography.titleSmall)
                pcRows(question,"options").forEach { option ->
                    val label=option.optString("label")
                    val checked=label in answers[index]
                    Row(Modifier.fillMaxWidth().clickable(enabled=enabled) {
                        answers=answers.toMutableList().apply { this[index]=if(question.optBoolean("multiple"))if(checked)this[index]-label else this[index]+label else listOf(label) }
                    }.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked,onCheckedChange=null)
                        Column(Modifier.weight(1f)) { Text(label);option.optString("description").takeIf(String::isNotBlank)?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                }
                if(question.optBoolean("custom",true))OutlinedTextField(custom[index],{text->custom=custom.toMutableList().apply { this[index]=text.take(2000) }},label={Text("Your answer")},modifier=Modifier.fillMaxWidth(),enabled=enabled)
            }
            Row {
                Button(onClick={onAnswer(request.getString("id"),JSONArray(answers.indices.map { i->JSONArray(if(custom[i].isNotBlank())listOf(custom[i].trim()) else answers[i]) }))},enabled=enabled&&answers.indices.all { answers[it].isNotEmpty()||custom[it].isNotBlank() },shape=MaterialTheme.shapes.small) { Text("Answer") }
                TextButton(onClick={onAnswer(request.getString("id"),null)},enabled=enabled) { Text("Dismiss") }
            }
        }
    }
}

@Composable
internal fun PcScreenPreview(state:ChatUiState,vm:ChatViewModel) {
    val path=state.pcScreen ?: return
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path,state.pcScreenAt) {
        bitmap=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest=vm::closePcScreen,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        FridayDialogWindow()
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("PC screen",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    TextButton(onClick=vm::capturePcScreen,enabled=!state.pcBusy) { Text("Refresh") }
                    TextButton(onClick=vm::closePcScreen) { Text("Close") }
                }
                Text("Captured ${java.time.Instant.ofEpochMilli(state.pcScreenAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("h:mm:ss a"))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                bitmap?.let { androidx.compose.foundation.Image(it.asImageBitmap(),"Captured PC screen",Modifier.fillMaxWidth().weight(1f),contentScale=androidx.compose.ui.layout.ContentScale.Fit) }
                    ?:Text("The screenshot could not be displayed.")
            }
        }
    }
}

/** A native PC session linked from chat: live status, inline approvals, and follow-up actions. */
@Composable
internal fun PcChatCard(json:String,state:ChatUiState,vm:ChatViewModel) {
    val stored=remember(json) { runCatching { JSONObject(json) }.getOrNull() } ?: return
    val id=stored.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) } ?: return
    val listed=state.pcSessions.firstOrNull { it.optString("id")==id }
    val full=state.pcChat[id] ?: if (stored.has("state")||stored.has("busy")) stored else null
    val title=(full?.optString("title") ?: listed?.optString("title") ?: stored.optString("title")).ifBlank { "PC task" }
    val status=full?.optString("state") ?: listed?.optString("state") ?: stored.optString("state").ifBlank { "busy" }
    val busy=full?.optBoolean("busy") ?: (status=="busy")
    val model=full?.optString("model") ?: listed?.optString("model").orEmpty()
    val permissions=full?.let { pcRows(it,"permissions") }.orEmpty()
    val questions=full?.let { pcRows(it,"questions") }.orEmpty()
    val waiting=status=="waiting"||permissions.isNotEmpty()||questions.isNotEmpty()
    FridayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("PC task",style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
                Text(if (waiting) "Needs your reply" else pcState(status),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(title,style=MaterialTheme.typography.bodyMedium)
            if (model.isNotBlank()) Text(model,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy&&!waiting) Text("Working on your computer…",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            permissions.forEach { request ->
                val requestId=request.optString("id").takeIf(String::isNotBlank) ?: return@forEach
                key(requestId) { PcPermissionCard(request,!state.pcBusy,vm::replyPcPermission) }
            }
            questions.forEach { question ->
                val questionId=question.optString("id").takeIf(String::isNotBlank) ?: return@forEach
                key(questionId) { PcQuestionCard(question,!state.pcBusy,vm::answerPcQuestion) }
            }
            if (full==null&&(state.pcStatus?.optInt("pending_count") ?: 0)>0) {
                Text("Approvals may be waiting. Open the task to review them.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(shape=MaterialTheme.shapes.small,onClick={ vm.openPcTask(id) }) { Text(if (waiting) "Review" else "Open") }
                TextButton(shape=MaterialTheme.shapes.small,onClick={ vm.discussPcTask(id) },enabled=!state.busy) { Text("Read into chat") }
                TextButton(shape=MaterialTheme.shapes.small,onClick={ vm.refreshChatPc() },enabled=!state.pcBusy) { Text("Refresh") }
            }
        }
    }
}
