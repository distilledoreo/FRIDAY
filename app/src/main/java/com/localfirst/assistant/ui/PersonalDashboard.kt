package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.settings.ProactivityLevel
import com.localfirst.assistant.ui.theme.LocalAppearance
import com.localfirst.assistant.workspace.BackgroundTask
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

data class DashboardState(val scope:String="",val loading:Boolean=false,val loaded:Boolean=false,val error:String?=null,
    val snapshot:JSONObject?=null,val followups:List<JSONObject> = emptyList(),val proposals:List<JSONObject> = emptyList(),val tasks:List<BackgroundTask> = emptyList(),val agentTasks:List<JSONObject> = emptyList())

/** Eligibility comes from the host's opt-ins/evidence/quiet controls; this only changes selection. */
fun dashboardProposalKinds(level:ProactivityLevel,kind:String)=kind in setOf("followup","situation")&&(level!=ProactivityLevel.CONSERVATIVE||kind=="followup")

@Composable
internal fun PersonalDashboard(state:ChatUiState,vm:ChatViewModel,onClose:()->Unit,expanded:Boolean=false,onToggle:(()->Unit)?=null) {
    val data=state.dashboard
    val level=LocalAppearance.current.proactivity
    val uri=LocalUriHandler.current
    var allEvents by remember { mutableStateOf(false) }
    fun go(destination:WorkspaceDestination) { onClose();vm.openWorkspace(destination) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(min=240.dp,max=720.dp),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Your dashboard",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            if(onToggle!=null)TextButton(shape = MaterialTheme.shapes.small, onClick=onToggle) { Text(if(expanded)"Collapse"else"Expand") }
            TextButton(shape = MaterialTheme.shapes.small, onClick=onClose) { Text("Close") }
        } }
        if(state.privacy.incognito) {
            item { DashboardCard("Private session") { Text("Your personal dashboard is unavailable in incognito.",style=MaterialTheme.typography.bodyMedium) } }
        } else if(data.scope!=state.projectId.orEmpty()||data.loading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth());Text("Checking your selected sources…",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodyMedium) }
        } else if(data.error!=null) {
            item { DashboardCard("Dashboard unavailable") { Text(data.error,style=MaterialTheme.typography.bodyMedium);Row { TextButton(shape = MaterialTheme.shapes.small, onClick=vm::refreshDashboard) { Text("Try again") };TextButton(shape = MaterialTheme.shapes.small, onClick={go(WorkspaceDestination.SETTINGS)}) { Text("Settings") } } } }
        } else if(data.loaded) {
            val snapshot=data.snapshot
            val sections=rows(snapshot?.optJSONArray("sections"))
            val calendar=sections.firstOrNull { it.optString("kind")=="calendar" }
            val weather=sections.firstOrNull { it.optString("kind")=="weather" }
            val events=rows(calendar?.optJSONArray("events"))
            item { DashboardCard("Today") {
                weather?.takeIf { it.optString("status")=="available" }?.let { source ->
                    val high=source.optDouble("temperature_2m_max")
                    Text("${source.optString("location")} · ${source.optString("conditions")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(high.isFinite())TextButton(shape = MaterialTheme.shapes.small, onClick={source.optString("source").takeIf { it.startsWith("https://") }?.let(uri::openUri)}) { Text("${high.toInt()}${source.optString("temperature_unit")} · Forecast") }
                }
                if(weather?.optString("status")=="unavailable")Text("Your selected forecast could not be checked.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(calendar?.optString("status")!="available") {
                    Text(if(calendar?.optString("status")=="unavailable")"Your selected calendar could not be checked."else"Choose a calendar to see today's events.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(shape = MaterialTheme.shapes.small, onClick={go(WorkspaceDestination.BRIEF)}) { Text("Choose sources") }
                } else if(events.isEmpty())Text("No events returned for today.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    events.take(if(allEvents)30 else 4).forEach { event ->
                        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(vertical=8.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(eventTime(event,snapshot?.optString("timezone")),Modifier.widthIn(min=64.dp,max=90.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) { Text(event.optString("title").ifBlank { event.optString("summary").ifBlank { "Untitled event" } },style=MaterialTheme.typography.bodyMedium);event.optString("location").takeIf(String::isNotBlank)?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
                        }
                    }
                    if(events.size>4)TextButton(shape = MaterialTheme.shapes.small, onClick={allEvents=!allEvents}) { Text(if(allEvents)"Show fewer events"else"Show all ${events.size} events") }
                    if(calendar.optBoolean("possibly_truncated"))Text("This preview may omit additional events.",style=MaterialTheme.typography.bodySmall)
                    TextButton(shape = MaterialTheme.shapes.small, onClick={go(WorkspaceDestination.ACCOUNTS)}) { Text("Open calendar accounts") }
                }
                snapshot?.optString("date")?.takeIf(String::isNotBlank)?.let { Text("$it · ${snapshot.optString("timezone")}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            } }
            item { DashboardCard("Follow-ups") {
                if(data.followups.isEmpty())Text("No saved follow-ups.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                data.followups.take(6).forEach { item -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical=8.dp)) { Text(item.optString("title"),style=MaterialTheme.typography.bodyMedium);followupTime(item,snapshot?.optString("timezone"))?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
                    TextButton(shape = MaterialTheme.shapes.small, onClick={vm.completeDashboardFollowup(item.getString("id"))},enabled=!state.workspaceBusy&&!state.busy) { Text("Done") }
                } }
                TextButton(shape = MaterialTheme.shapes.small, onClick={go(WorkspaceDestination.BRIEF)}) { Text("Manage follow-ups") }
            } }
            val tasks=data.tasks.filter { it.status !in listOf("done","completed","cancelled") }
            item { DashboardCard("Tasks and attention") {
                val attention=data.agentTasks.filter { it.optString("status") in FRIDAY_NEEDS_YOU||it.optString("status") in FRIDAY_WORKING }
                if(tasks.isEmpty()&&attention.isEmpty())Text("No unfinished tasks returned.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                tasks.take(4).forEach { task -> Text(task.prompt,style=MaterialTheme.typography.bodyMedium);Text(task.status,style=MaterialTheme.typography.labelSmall,color=if(task.status in listOf("failed","error"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                attention.take(3).forEach { task ->
                    Text(task.optJSONObject("proposal")?.optString("prompt").orEmpty(),style=MaterialTheme.typography.bodyMedium)
                    Text(agentStatus(task.optString("status")),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(shape = MaterialTheme.shapes.small, onClick={onClose();vm.openFriday(task.optString("id"))}) { Text("Review task") }
                }
                Row { TextButton(shape = MaterialTheme.shapes.small, onClick={go(WorkspaceDestination.TASKS)}) { Text("View tasks") };TextButton(shape = MaterialTheme.shapes.small, onClick={go(WorkspaceDestination.ACTIVITY)}) { Text("Assistant activity") } }
            } }
            val proposals=data.proposals.filter { dashboardProposalKinds(level,it.optString("kind")) }.take(level.suggestionLimit)
            item { DashboardCard("Worth a look") {
                if(proposals.isEmpty())Text("Nothing relevant to suggest right now.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                proposals.forEach { proposal ->
                    val source=proposal.optJSONObject("source")?:proposal
                    Text(source.optString("title").ifBlank { source.optString("summary").ifBlank { source.optString("detail").ifBlank { "Saved follow-up" } } },style=MaterialTheme.typography.bodyMedium)
                    if(proposal.optString("kind")=="situation")Text("Tentative · review the source evidence",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    source.optString("detail").takeIf(String::isNotBlank)?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row { TextButton(shape = MaterialTheme.shapes.small, onClick={go(WorkspaceDestination.BRIEF)}) { Text("Review") };TextButton(shape = MaterialTheme.shapes.small, onClick={vm.dismissDashboardProposal(proposal.getString("id"))},enabled=!state.workspaceBusy&&!state.busy) { Text("Dismiss") } }
                }
            } }
            item { Row { TextButton(shape = MaterialTheme.shapes.small, onClick=vm::refreshDashboard,enabled=!data.loading) { Text("Refresh") };TextButton(shape = MaterialTheme.shapes.small, onClick={go(WorkspaceDestination.BRIEF)}) { Text("Sources and brief") } } }
        }
    }
}

@Composable
private fun DashboardCard(title:String,content:@Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface,shadowElevation=1.dp) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(title,style=MaterialTheme.typography.titleSmall);content() }
    }
}
private fun rows(array:org.json.JSONArray?) = (0 until (array?.length()?:0)).mapNotNull { array?.optJSONObject(it) }
internal fun eventTime(event:JSONObject,timezone:String?):String {
    val start=event.optJSONObject("start")
    if(event.optBoolean("all_day")||!start?.optString("date").isNullOrBlank())return "All day"
    val raw=start?.optString("dateTime")?.takeIf(String::isNotBlank)?:event.optString("start")
    return runCatching {
        val zone=ZoneId.of(timezone?.takeIf(String::isNotBlank)?:"UTC")
        val instant=runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrElse {
            LocalDateTime.parse(raw).atZone(ZoneId.of(start?.optString("timeZone")?.takeIf(String::isNotBlank)?:zone.id)).toInstant()
        }
        instant.atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a"))
    }.getOrDefault("Time unavailable")
}
internal fun followupTime(item:JSONObject,timezone:String?):String? {
    if(item.isNull("due"))return null
    val due=item.optDouble("due")
    if(!due.isFinite())return null
    return runCatching { Instant.ofEpochMilli((due*1000).toLong()).atZone(ZoneId.of(timezone?.takeIf(String::isNotBlank)?:"UTC")).format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")) }.getOrNull()
}
