package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.clip
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
    val snapshot:JSONObject?=null,val followups:List<JSONObject> = emptyList(),val proposals:List<JSONObject> = emptyList(),val tasks:List<BackgroundTask> = emptyList(),val agentTasks:List<JSONObject> = emptyList(),
    /** Today's events from the phone's own calendar, used when no PC calendar is connected. Null when not read. */
    val phoneEvents:List<com.localfirst.assistant.tools.phone.CalendarEntry>?=null,
    /** The phone calendar needs permission before it can be shown. */
    val phoneCalendarNeedsPermission:Boolean=false)

/** Eligibility comes from the host's opt-ins/evidence/quiet controls; this only changes selection. */
fun dashboardProposalKinds(level:ProactivityLevel,kind:String)=kind in setOf("followup","situation")&&(level!=ProactivityLevel.CONSERVATIVE||kind=="followup")

@Composable
internal fun PersonalDashboard(state:ChatUiState,vm:ChatViewModel,onClose:()->Unit,expanded:Boolean=false,onToggle:(()->Unit)?=null) {
    val data=state.dashboard
    val level=LocalAppearance.current.proactivity
    val uri=LocalUriHandler.current
    var allEvents by remember { mutableStateOf(false) }
    fun go(destination:WorkspaceDestination) { onClose();vm.openWorkspace(destination) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(min=240.dp,max=720.dp),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment=Alignment.CenterVertically) {
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),Modifier.weight(1f).padding(start=4.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick=vm::refreshDashboard,enabled=!data.loading) { Icon(Icons.Filled.Refresh,"Refresh dashboard",Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick=onClose) { Icon(Icons.Filled.Close,"Close dashboard",Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant) }
        } }
        if(state.privacy.incognito) {
            item { DashCard { DashMessage("Your dashboard is unavailable in incognito.") } }
        } else if(data.scope!=state.projectId.orEmpty()||data.loading) {
            item { DashCard { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp));DashMessage("Checking your sources…") } }
        } else if(data.error!=null) {
            item { DashCard {
                DashMessage(data.error)
                DashRow(onClick=vm::refreshDashboard) { Text("Try again",Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge) }
                DashRow(onClick={go(WorkspaceDestination.SETTINGS)}) { Text("Settings",Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge) }
            } }
        } else if(data.loaded) {
            val snapshot=data.snapshot
            val zone=snapshot?.optString("timezone")?.takeIf(String::isNotBlank)
            val sections=rows(snapshot?.optJSONArray("sections"))
            val calendar=sections.firstOrNull { it.optString("kind")=="calendar" }
            val weather=sections.firstOrNull { it.optString("kind")=="weather" }?.takeIf { it.optString("status")=="available" }
            val events=rows(calendar?.optJSONArray("events"))
            item { DashCard {
                Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable { go(WorkspaceDestination.BRIEF) }.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Filled.DateRange,null,Modifier.size(22.dp),tint=com.localfirst.assistant.ui.theme.LocalFridayPalette.current.accent)
                        Spacer(Modifier.width(12.dp));Text("Today",style=MaterialTheme.typography.titleMedium);Chevron()
                    }
                    weather?.let { source ->
                        val high=source.optDouble("temperature_2m_max")
                        Row(Modifier.clip(MaterialTheme.shapes.small).clickable { source.optString("source").takeIf { it.startsWith("https://") }?.let(uri::openUri) }.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                            WeatherGlyph(source.optString("conditions"))
                            Spacer(Modifier.width(8.dp))
                            Text(if(high.isFinite())"${high.toInt()}°" else source.optString("conditions"),style=MaterialTheme.typography.titleMedium)
                            Chevron()
                        }
                    }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                val phone=data.phoneEvents
                when {
                    calendar?.optString("status")!="available"&&phone!=null -> {
                        if(phone.isEmpty())DashMessage("Nothing on your calendar today.")
                        phone.take(if(allEvents)30 else 4).forEachIndexed { index,event ->
                            DashRow(onClick={vm.openPhoneCalendar()}) {
                                Text(if(event.allDay)"All day" else event.start.format(DateTimeFormatter.ofPattern("h:mm a")),Modifier.width(76.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Box(Modifier.padding(end=14.dp).size(8.dp).clip(CircleShape).background(eventColor(index,com.localfirst.assistant.ui.theme.LocalFridayPalette.current.accent)))
                                DashText(event.title,phoneEventDetail(event))
                            }
                        }
                        if(phone.size>4)DashRow(onClick={allEvents=!allEvents}) { Text(if(allEvents)"Show fewer"else"Show all ${phone.size} events",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    calendar?.optString("status")!="available"&&data.phoneCalendarNeedsPermission -> DashRow(onClick=vm::allowPhoneCalendar) { DashText("Show your phone’s calendar","Allow calendar access") }
                    calendar?.optString("status")=="unavailable" -> DashRow(onClick={go(WorkspaceDestination.ACCOUNTS)}) { DashText("Your calendar couldn’t be checked","Open accounts") }
                    calendar?.optString("status")!="available" -> DashRow(onClick={go(WorkspaceDestination.BRIEF)}) { DashText("Connect a calendar to see your day","Choose sources") }
                    events.isEmpty() -> DashMessage("Nothing on your calendar today.")
                    else -> {
                        events.take(if(allEvents)30 else 4).forEachIndexed { index,event ->
                            DashRow(onClick={go(WorkspaceDestination.ACCOUNTS)}) {
                                Text(eventTime(event,zone),Modifier.width(76.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Box(Modifier.padding(end=14.dp).size(8.dp).clip(CircleShape).background(eventColor(index,com.localfirst.assistant.ui.theme.LocalFridayPalette.current.accent)))
                                DashText(event.optString("title").ifBlank { event.optString("summary").ifBlank { "Untitled event" } },eventDetail(event))
                            }
                        }
                        if(events.size>4)DashRow(onClick={allEvents=!allEvents}) { Text(if(allEvents)"Show fewer"else"Show all ${events.size} events",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            } }
            val proposals=data.proposals.filter { dashboardProposalKinds(level,it.optString("kind")) }.take(level.suggestionLimit)
            val attention=data.agentTasks.filter { it.optString("status") in FRIDAY_NEEDS_YOU }
            val reviewCount=data.followups.size+attention.size+proposals.size
            item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.height(IntrinsicSize.Min)) {
                DashCard(Modifier.weight(.42f).fillMaxHeight(),onClick={ if(attention.isNotEmpty()) { onClose();vm.openFriday() } else go(WorkspaceDestination.BRIEF) }) {
                    Row(Modifier.fillMaxSize().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.List,null,Modifier.size(22.dp),tint=com.localfirst.assistant.ui.theme.LocalFridayPalette.current.accent)
                        Spacer(Modifier.width(12.dp))
                        Text(if(reviewCount==0)"All caught up" else "$reviewCount ${if(reviewCount==1)"thing" else "things"} to review",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                        if(reviewCount>0)Chevron()
                    }
                }
                val suggestion=proposals.firstOrNull()
                val working=data.agentTasks.firstOrNull { it.optString("status") in FRIDAY_WORKING }
                DashCard(Modifier.weight(.58f).fillMaxHeight(),onClick={ onClose();if(suggestion!=null)vm.openWorkspace(WorkspaceDestination.BRIEF) else vm.openFriday() }) {
                    Row(Modifier.fillMaxSize().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                        SparkleGlyph()
                        Spacer(Modifier.width(12.dp))
                        val source=suggestion?.let { it.optJSONObject("source")?:it }
                        DashText(
                            source?.let { it.optString("title").ifBlank { it.optString("summary").ifBlank { "Worth a look" } } }
                                ?: working?.let { "FRIDAY is working" } ?: "Ask FRIDAY to take something on",
                            source?.optString("detail")?.takeIf(String::isNotBlank)
                                ?: working?.optJSONObject("proposal")?.optString("prompt")
                                ?: "Research, compare or keep watch for you",
                            Modifier.weight(1f),
                            compact=true,
                        )
                        Chevron()
                    }
                }
            } }
            if(data.followups.isNotEmpty())item { DashCard {
                DashHeader("Follow-ups")
                data.followups.take(6).forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(start=16.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically) {
                        DashText(item.optString("title"),followupTime(item,zone),Modifier.weight(1f).padding(vertical=10.dp))
                        IconButton(onClick={vm.completeDashboardFollowup(item.getString("id"))},enabled=!state.workspaceBusy&&!state.busy) { Icon(Icons.Filled.Check,"Mark ${item.optString("title")} done",tint=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            } }
            if(attention.isNotEmpty())item { DashCard {
                DashHeader("FRIDAY needs your OK")
                attention.take(3).forEach { task ->
                    DashRow(onClick={onClose();vm.openFriday(task.optString("id"))}) { DashText(task.optJSONObject("proposal")?.optString("prompt").orEmpty(),agentStatus(task.optString("status"))) }
                }
                Spacer(Modifier.height(6.dp))
            } }
            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
                TextButton(shape=MaterialTheme.shapes.small,onClick={go(WorkspaceDestination.BRIEF)},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurfaceVariant)) { Text("Edit sources") }
            } }
        }
    }
}

@Composable
private fun DashCard(modifier:Modifier=Modifier,onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
    val dark=com.localfirst.assistant.ui.theme.LocalFridayPalette.current.dark
    val color=if(dark)androidx.compose.ui.graphics.Color(0xFF232326) else androidx.compose.ui.graphics.Color.White
    if(onClick!=null)Surface(onClick=onClick,modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=color) { Column(content=content) }
    else Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=color) { Column(content=content) }
}

@Composable
private fun DashRow(onClick:()->Unit,content:@Composable RowScope.()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).heightIn(min=56.dp).padding(start=16.dp,end=10.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
        content()
        Chevron()
    }
}

@Composable
private fun RowScope.DashText(title:String,detail:String?,modifier:Modifier=Modifier.weight(1f),compact:Boolean=false) {
    Column(modifier) {
        Text(title,style=if(compact)MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        detail?.takeIf(String::isNotBlank)?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
    }
}

@Composable private fun DashMessage(text:String) = Text(text,Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
@Composable private fun DashHeader(text:String) = Text(text,Modifier.padding(start=16.dp,top=14.dp,bottom=2.dp),style=MaterialTheme.typography.titleSmall)
@Composable private fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.7f))

/** Distinct, calm dot colors for consecutive events. */
private fun eventColor(index:Int,accent:androidx.compose.ui.graphics.Color)=listOf(accent,androidx.compose.ui.graphics.Color(0xFF6F9BDE),androidx.compose.ui.graphics.Color(0xFF6DBE8B),androidx.compose.ui.graphics.Color(0xFFD9B26A),androidx.compose.ui.graphics.Color(0xFFA48BDB))[index%5]

/** The same "1 hr · Place" line for an event from the phone's calendar. */
internal fun phoneEventDetail(event:com.localfirst.assistant.tools.phone.CalendarEntry):String? {
    val minutes=event.end?.let { Duration.between(event.start,it).toMinutes() }?.takeIf { !event.allDay&&it>0 }
    val length=minutes?.let { when { it<60 -> "$it min"; it%60==0L -> "${it/60} hr"; else -> "${it/60} hr ${it%60} min" } }
    return listOfNotNull(length,event.location?.takeIf(String::isNotBlank)).joinToString(" · ").ifBlank { null }
}

/** "30 min · Google Meet": length and place, when the event has them. */
internal fun eventDetail(event:JSONObject):String? {
    val length=runCatching {
        val start=OffsetDateTime.parse(event.optJSONObject("start")?.optString("dateTime")).toInstant()
        val end=OffsetDateTime.parse(event.optJSONObject("end")?.optString("dateTime")).toInstant()
        val minutes=Duration.between(start,end).toMinutes()
        when { minutes<=0 -> null; minutes<60 -> "$minutes min"; minutes%60==0L -> "${minutes/60} hr"; else -> "${minutes/60} hr ${minutes%60} min" }
    }.getOrNull()
    val place=event.optString("location").ifBlank { event.optString("conference").ifBlank { event.optString("hangoutLink").takeIf(String::isNotBlank)?.let { "Video call" }.orEmpty() } }
    return listOfNotNull(length,place.takeIf(String::isNotBlank)).joinToString(" · ").ifBlank { null }
}

@Composable
private fun WeatherGlyph(conditions:String) {
    val accent=MaterialTheme.colorScheme.primary
    val sunny=conditions.contains("clear",true)||conditions.contains("sun",true)
    val cloud=MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(22.dp)) {
        val c=center;val r=size.minDimension*.2f
        if(sunny) {
            drawCircle(androidx.compose.ui.graphics.Color(0xFFF2B35B),r,c)
            for(i in 0 until 8) { val a=i*Math.PI/4;val d1=r*1.5f;val d2=r*2.2f
                drawLine(androidx.compose.ui.graphics.Color(0xFFF2B35B),androidx.compose.ui.geometry.Offset(c.x+(d1*Math.cos(a)).toFloat(),c.y+(d1*Math.sin(a)).toFloat()),androidx.compose.ui.geometry.Offset(c.x+(d2*Math.cos(a)).toFloat(),c.y+(d2*Math.sin(a)).toFloat()),strokeWidth=1.6.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round) }
        } else {
            drawCircle(cloud,r*1.05f,c+androidx.compose.ui.geometry.Offset(-r*.6f,r*.2f))
            drawCircle(cloud,r*1.35f,c+androidx.compose.ui.geometry.Offset(r*.5f,-r*.1f))
            drawRect(cloud,c+androidx.compose.ui.geometry.Offset(-r*1.6f,r*.2f),androidx.compose.ui.geometry.Size(r*3.4f,r*1.05f))
        }
    }
}

@Composable
private fun SparkleGlyph() {
    val accent=com.localfirst.assistant.ui.theme.LocalFridayPalette.current.accent
    Canvas(Modifier.size(22.dp)) {
        fun star(cx:Float,cy:Float,r:Float) = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx,cy-r);quadraticTo(cx,cy,cx+r,cy);quadraticTo(cx,cy,cx,cy+r);quadraticTo(cx,cy,cx-r,cy);quadraticTo(cx,cy,cx,cy-r);close()
        }
        drawPath(star(size.width*.42f,size.height*.55f,size.width*.36f),accent,style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(),join=androidx.compose.ui.graphics.StrokeJoin.Round))
        drawPath(star(size.width*.8f,size.height*.2f,size.width*.15f),accent)
    }
}

private fun rows(array:org.json.JSONArray?) = (0 until (array?.length()?:0)).mapNotNull { array?.optJSONObject(it) }
internal fun eventTime(event:JSONObject,timezone:String?):String {
    val start=event.optJSONObject("start")
    if(event.optBoolean("all_day")||!start?.optString("date").isNullOrBlank())return "All day"
    val raw=start?.optString("dateTime")?.takeIf(String::isNotBlank)?:event.optString("start")
    return runCatching {
        val zone=timezone?.takeIf(String::isNotBlank)?.let(ZoneId::of)?:ZoneId.systemDefault()
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
    return runCatching { Instant.ofEpochMilli((due*1000).toLong()).atZone(timezone?.takeIf(String::isNotBlank)?.let(ZoneId::of)?:ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")) }.getOrNull()
}
