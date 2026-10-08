package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
    val accent=com.localfirst.assistant.ui.theme.LocalFridayPalette.current.accent
    val muted=MaterialTheme.colorScheme.onSurfaceVariant
    var allEvents by remember { mutableStateOf(false) }
    var openEvent by remember { mutableStateOf<String?>(null) }
    var weatherOpen by remember { mutableStateOf(false) }
    val enabled=!state.workspaceBusy&&!state.busy
    fun go(destination:WorkspaceDestination) { onClose();vm.openWorkspace(destination) }
    fun talk(text:String) { onClose();vm.chatAbout(text) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(min=240.dp,max=720.dp),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment=Alignment.CenterVertically) {
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),Modifier.weight(1f).padding(start=4.dp),style=MaterialTheme.typography.labelLarge,color=muted)
            IconButton(onClick={go(WorkspaceDestination.BRIEF)}) { Icon(LineIcons.Settings,"Dashboard settings",Modifier.size(20.dp),tint=muted) }
            IconButton(onClick=vm::refreshDashboard,enabled=!data.loading) { Icon(LineIcons.Refresh,"Refresh dashboard",Modifier.size(20.dp),tint=muted) }
            IconButton(onClick=onClose) { Icon(LineIcons.Close,"Close dashboard",Modifier.size(20.dp),tint=muted) }
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
                    Icon(LineIcons.Calendar,null,Modifier.size(22.dp),tint=accent)
                    Spacer(Modifier.width(12.dp));Text("Today",Modifier.weight(1f).padding(vertical=10.dp),style=MaterialTheme.typography.titleMedium)
                    weather?.let { source ->
                        val high=source.optDouble("temperature_2m_max")
                        Row(Modifier.clip(MaterialTheme.shapes.small).clickable { weatherOpen=!weatherOpen }.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                            WeatherGlyph(source.optString("conditions"))
                            Spacer(Modifier.width(8.dp))
                            Text(if(high.isFinite())"${high.toInt()}°" else source.optString("conditions"),style=MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if(weatherOpen)weather?.let { source -> WeatherDetail(source) { runCatching { uri.openUri(forecastPage(source)) } } }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                val phone=data.phoneEvents
                when {
                    calendar?.optString("status")!="available"&&phone!=null -> {
                        if(phone.isEmpty())DashMessage("Nothing on your calendar today.")
                        phone.take(if(allEvents)30 else 4).forEachIndexed { index,event ->
                            val key="phone-$index"
                            EventRow(if(event.allDay)"All day" else event.start.format(DateTimeFormatter.ofPattern("h:mm a")),event.title,phoneEventDetail(event),eventColor(index,accent),openEvent==key,{openEvent=if(openEvent==key)null else key}) {
                                EventActions(event.location,onOpen={vm.openPhoneCalendar(event.start)},onPrep={talk("Help me prepare for “${event.title}” today${event.location?.takeIf(String::isNotBlank)?.let { " at $it" }.orEmpty()}.")})
                            }
                        }
                        if(phone.size>4)ShowAll(allEvents,phone.size) { allEvents=!allEvents }
                    }
                    calendar?.optString("status")!="available"&&data.phoneCalendarNeedsPermission -> DashRow(onClick=vm::allowPhoneCalendar) { DashText("Show your phone’s calendar","Allow calendar access") }
                    calendar?.optString("status")=="unavailable" -> DashRow(onClick={go(WorkspaceDestination.ACCOUNTS)}) { DashText("Your calendar couldn’t be checked","Open accounts") }
                    calendar?.optString("status")!="available" -> DashRow(onClick={go(WorkspaceDestination.BRIEF)}) { DashText("Connect a calendar to see your day","Choose sources") }
                    events.isEmpty() -> DashMessage("Nothing on your calendar today.")
                    else -> {
                        events.take(if(allEvents)30 else 4).forEachIndexed { index,event ->
                            val key="pc-$index"
                            val title=event.optString("title").ifBlank { event.optString("summary").ifBlank { "Untitled event" } }
                            val link=event.optString("htmlLink").takeIf { it.startsWith("https://") }
                            EventRow(eventTime(event,zone),title,eventDetail(event),eventColor(index,accent),openEvent==key,{openEvent=if(openEvent==key)null else key}) {
                                EventActions(event.optString("location").takeIf(String::isNotBlank),onOpen=link?.let { l -> { uri.openUri(l) } },onPrep={talk("Help me prepare for “$title” today.")})
                            }
                        }
                        if(events.size>4)ShowAll(allEvents,events.size) { allEvents=!allEvents }
                    }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                Row(Modifier.fillMaxWidth().clickable(enabled=!state.busy) { onClose();vm.discussDailyBrief() }.heightIn(min=52.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    SparkleGlyph();Spacer(Modifier.width(12.dp))
                    Text("Plan my day with FRIDAY",Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                    Chevron()
                }
            } }
            val tz=briefZone(zone)
            fun shown(kind:String)=sections.firstOrNull { it.optString("kind")==kind }?.optString("status")!="disabled"
            if(shown("followups"))item { DashCard {
                Row(Modifier.padding(start=16.dp,top=14.dp,end=16.dp,bottom=2.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(LineIcons.Checklist,null,Modifier.size(22.dp),tint=accent);Spacer(Modifier.width(12.dp))
                    Text("Follow-ups",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    if(data.followups.isNotEmpty())Text("${data.followups.size}",style=MaterialTheme.typography.labelLarge,color=muted)
                }
                if(data.followups.isEmpty())Text("Nothing waiting on you.",Modifier.padding(start=50.dp,top=2.dp,bottom=4.dp),style=MaterialTheme.typography.bodyMedium,color=muted)
                data.followups.take(6).forEach { item -> key(item.optString("id")) { FollowupRow(item,tz,enabled,vm,::talk) } }
                if(data.followups.size>6)DashRow(onClick={go(WorkspaceDestination.BRIEF)}) { Text("See all ${data.followups.size}",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=muted) }
                AddFollowup(tz,enabled,vm)
                Spacer(Modifier.height(4.dp))
            } }
            val suggestions=if(!shown("situations"))emptyList() else data.proposals.filter { dashboardProposalKinds(level,it.optString("kind"))&&it.optString("kind")!="followup" }.take(level.suggestionLimit)
            if(suggestions.isNotEmpty())item { DashCard {
                Row(Modifier.padding(start=16.dp,top=14.dp,end=16.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    SparkleGlyph();Spacer(Modifier.width(12.dp));Text("Worth a look",style=MaterialTheme.typography.titleMedium)
                }
                suggestions.forEach { proposal ->
                    val source=proposal.optJSONObject("source")?:proposal
                    val topic=source.optString("topic").ifBlank { source.optString("title").ifBlank { source.optString("summary") } }
                    Column(Modifier.fillMaxWidth().padding(start=50.dp,end=16.dp,top=6.dp,bottom=6.dp)) {
                        Text(topic.ifBlank { "Something from your conversations" }.replaceFirstChar(Char::uppercase),style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        source.optString("summary",source.optString("detail")).takeIf { it.isNotBlank()&&it!=topic }?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=muted,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                        Row(Modifier.padding(top=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            DashPill("Pick it up",primary=true,enabled=!state.busy) { talk("Let’s pick up where we left off on $topic.") }
                            DashPill("Not now",enabled=enabled) { vm.dismissDashboardProposal(proposal.getString("id")) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            } }
            val attention=data.agentTasks.filter { it.optString("status") in FRIDAY_NEEDS_YOU }
            val working=data.agentTasks.filter { it.optString("status") in FRIDAY_WORKING }
            item { DashCard {
                Row(Modifier.padding(start=16.dp,top=14.dp,end=16.dp,bottom=2.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(LineIcons.Friday,null,Modifier.size(20.dp),tint=accent);Spacer(Modifier.width(14.dp))
                    Text("FRIDAY",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    if(attention.isNotEmpty()||working.isNotEmpty())TextButton(onClick={onClose();vm.openFriday()},shape=MaterialTheme.shapes.small,colors=ButtonDefaults.textButtonColors(contentColor=muted)) { Text("All tasks") }
                }
                attention.take(3).forEach { task ->
                    Row(Modifier.fillMaxWidth().clickable { onClose();vm.openFriday(task.optString("id")) }.padding(start=50.dp,end=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        DashText(task.optJSONObject("proposal")?.optString("prompt").orEmpty().ifBlank { "A task" },agentStatus(task.optString("status")))
                        DashPill("Review",primary=true) { onClose();vm.openFriday(task.optString("id")) }
                    }
                }
                working.take(2).forEach { task ->
                    Row(Modifier.fillMaxWidth().clickable { onClose();vm.openFriday(task.optString("id")) }.padding(start=50.dp,end=10.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        DashText(task.optJSONObject("proposal")?.optString("prompt").orEmpty().ifBlank { "A task" },agentStatus(task.optString("status")))
                        Chevron()
                    }
                }
                AskFriday(enabled=!state.busy) { request -> onClose();vm.askFriday(request) }
            } }
        }
    }
}

@Composable
private fun EventRow(time:String,title:String,detail:String?,dot:androidx.compose.ui.graphics.Color,open:Boolean,onToggle:()->Unit,actions:@Composable ()->Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick=onToggle)) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(start=16.dp,end=16.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(time,Modifier.width(76.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.padding(end=14.dp).size(8.dp).clip(CircleShape).background(dot))
            DashText(title,detail)
        }
        if(open)actions()
    }
}

/** What you can do with an event: open it, get there, or get ready for it. */
@Composable
private fun EventActions(location:String?,onOpen:(()->Unit)?,onPrep:()->Unit) {
    val uri=LocalUriHandler.current
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(Modifier.fillMaxWidth().padding(start=16.dp,end=12.dp,bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        onOpen?.let { DashPill("Open",icon=LineIcons.Calendar,onClick=it) }
        location?.takeIf(String::isNotBlank)?.let { place -> DashPill("Directions",icon=LineIcons.Place) { runCatching { uri.openUri("geo:0,0?q="+android.net.Uri.encode(place)) } } }
        DashPill("Prep with FRIDAY",icon=LineIcons.Chat,onClick=onPrep)
    }
}

@Composable
private fun WeatherDetail(source:JSONObject,onSource:()->Unit) {
    val low=source.optDouble("temperature_2m_min");val high=source.optDouble("temperature_2m_max")
    val rain=source.optDouble("precipitation_probability_max")
    val line=listOfNotNull(source.optString("conditions").takeIf(String::isNotBlank)?.replaceFirstChar(Char::uppercase),
        if(high.isFinite()&&low.isFinite())"High ${high.toInt()}° · Low ${low.toInt()}°" else null,
        if(rain.isFinite())"${rain.toInt()}% chance of rain" else null).joinToString(" · ")
    Row(Modifier.fillMaxWidth().clickable(onClick=onSource).padding(start=50.dp,end=16.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(source.optString("location"),style=MaterialTheme.typography.bodyMedium)
            Text(line,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Chevron()
    }
}

@Composable
private fun ShowAll(all:Boolean,count:Int,onClick:()->Unit) =
    Text(if(all)"Show fewer" else "Show all $count events",Modifier.fillMaxWidth().clickable(onClick=onClick).padding(start=108.dp,top=8.dp,bottom=12.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)

/** A small rounded action: the accent one is the likely next step, the others stay quiet. */
@Composable
internal fun DashPill(label:String,primary:Boolean=false,icon:androidx.compose.ui.graphics.vector.ImageVector?=null,enabled:Boolean=true,onClick:()->Unit) {
    val palette=com.localfirst.assistant.ui.theme.LocalFridayPalette.current
    val fill=if(primary)palette.accent.copy(alpha=if(palette.dark).18f else .14f) else MaterialTheme.colorScheme.onSurface.copy(alpha=.06f)
    Surface(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(50),color=fill,contentColor=if(primary)palette.accent else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.heightIn(min=36.dp).padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically) {
            icon?.let { Icon(it,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp)) }
            Text(label,style=MaterialTheme.typography.labelLarge)
        }
    }
}

/** "Ask FRIDAY to take something on": hands a request straight to her. */
@Composable
private fun AskFriday(enabled:Boolean,onAsk:(String)->Unit) {
    var text by remember { mutableStateOf("") }
    fun ask() { if(text.isNotBlank()) { onAsk(text);text="" } }
    OutlinedTextField(text,{text=it.take(4000)},Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,top=6.dp,bottom=12.dp),enabled=enabled,
        placeholder={Text("Ask FRIDAY to take something on…")},singleLine=true,shape=RoundedCornerShape(24.dp),
        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Send),
        keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSend={ask()}),
        trailingIcon={IconButton(onClick=::ask,enabled=enabled&&text.isNotBlank()) { Icon(LineIcons.Send,"Ask FRIDAY",Modifier.size(20.dp)) }})
}

@Composable
internal fun DashCard(modifier:Modifier=Modifier,onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
    val dark=com.localfirst.assistant.ui.theme.LocalFridayPalette.current.dark
    val color=if(dark)androidx.compose.ui.graphics.Color(0xFF232326) else androidx.compose.ui.graphics.Color.White
    if(onClick!=null)Surface(onClick=onClick,modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=color) { Column(content=content) }
    else Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=color) { Column(content=content) }
}

@Composable
internal fun DashRow(onClick:()->Unit,content:@Composable RowScope.()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).heightIn(min=56.dp).padding(start=16.dp,end=10.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
        content()
        Chevron()
    }
}

@Composable
internal fun RowScope.DashText(title:String,detail:String?,modifier:Modifier=Modifier.weight(1f),compact:Boolean=false) {
    Column(modifier) {
        Text(title,style=if(compact)MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        detail?.takeIf(String::isNotBlank)?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
    }
}

@Composable internal fun DashMessage(text:String) = Text(text,Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
@Composable internal fun DashHeader(text:String) = Text(text,Modifier.padding(start=16.dp,top=14.dp,bottom=2.dp),style=MaterialTheme.typography.titleSmall)
@Composable internal fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.7f))

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

/** A forecast page for people: the brief's weather page, else a search for the place. Never the API URL. */
internal fun forecastPage(source:JSONObject):String =
    source.optString("page").takeIf { it.startsWith("https://weather.com/") }
        ?: "https://duckduckgo.com/?q="+android.net.Uri.encode("weather "+source.optString("location"))

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
