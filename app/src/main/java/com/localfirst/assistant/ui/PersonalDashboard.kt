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
import com.localfirst.assistant.client.BriefState
import com.localfirst.assistant.client.DashboardActions
import com.localfirst.assistant.client.wireJson
import com.localfirst.assistant.ui.dashboard.DashboardNavigation
import com.localfirst.assistant.ui.dashboard.DeviceCalendar
import com.localfirst.assistant.ui.dashboard.DeviceEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class DashboardState(val scope:String="",val loading:Boolean=false,val loaded:Boolean=false,val error:String?=null,
    val snapshot:JSONObject?=null,val followups:List<JSONObject> = emptyList(),val proposals:List<JSONObject> = emptyList(),val tasks:List<BackgroundTask> = emptyList(),val agentTasks:List<JSONObject> = emptyList(),
    /** Today's events from the phone's own calendar, used when no PC calendar is connected. Null when not read. */
    val phoneEvents:List<com.localfirst.assistant.tools.phone.CalendarEntry>?=null,
    /** The phone calendar needs permission before it can be shown. */
    val phoneCalendarNeedsPermission:Boolean=false)

/** The shared dashboard and brief screens over this app's native dashboard orchestration, which adds the phone's calendar. */
internal class NativeDashboardActions(private val vm: ChatViewModel) : DashboardActions {
    private val dashboardState = MutableStateFlow(com.localfirst.assistant.client.DashboardState())
    private val briefState = MutableStateFlow(BriefState())
    override val dashboard = dashboardState.asStateFlow()
    override val brief = briefState.asStateFlow()
    private var lastDashboard: DashboardState? = null
    private val converted = mutableMapOf<Any, Any>()
    private fun JSONObject.wire(): JsonObject = wireJson.parseToJsonElement(toString()).jsonObject
    @Suppress("UNCHECKED_CAST")
    private fun rows(list: List<JSONObject>) = converted.getOrPut(list) { list.map { it.wire() } } as List<JsonObject>
    fun update(native: ChatUiState) {
        val d = native.dashboard
        if (d !== lastDashboard) {
            lastDashboard = d
            dashboardState.value = com.localfirst.assistant.client.DashboardState(d.scope, d.loading, d.loaded, d.error, d.snapshot?.wire(), rows(d.followups), rows(d.proposals), rows(d.agentTasks))
        }
        briefState.value = BriefState(native.briefPreferences?.let { converted.getOrPut(it) { it.wire() } as JsonObject }, rows(native.briefFollowups), rows(native.connectedAccounts),
            rows(native.weatherLocations), busy = native.workspaceBusy, error = native.workspaceError)
        if (converted.size > 64) converted.clear()
    }
    override fun refreshDashboard(scope: String): Job? { vm.refreshDashboard(); return null }
    override fun clearDashboard() = vm.clearDashboard()
    override fun loadBrief(scope: String) = vm.refreshBrief()
    override fun saveBriefPreferences(preferences: JsonObject, scope: String) = vm.saveBriefPreferences(JSONObject(preferences.toString()))
    override fun findWeatherCity(query: String) = vm.findWeatherCity(query)
    override fun addFollowup(title: String, due: String?, scope: String) = vm.addFollowup(title, due)
    override fun completeFollowup(id: String, scope: String) = vm.changeFollowup(id)
    override fun deleteFollowup(id: String, scope: String) = vm.changeFollowup(id, delete = true)
    override fun snoozeFollowup(id: String, due: String, scope: String) = vm.snoozeFollowup(id, due)
    override fun dismissProposal(id: String, scope: String) = vm.dismissDashboardProposal(id)
}

@Composable
internal fun rememberNativeDashboardActions(vm: ChatViewModel, native: ChatUiState): NativeDashboardActions {
    val actions = remember(vm) { NativeDashboardActions(vm).also { it.update(native) } }
    SideEffect { actions.update(native) }
    return actions
}

@Composable
internal fun PersonalDashboard(state:ChatUiState,vm:ChatViewModel,onClose:()->Unit,expanded:Boolean=false,onToggle:(()->Unit)?=null) {
    val actions = rememberNativeDashboardActions(vm, state)
    val phone = state.dashboard.phoneEvents?.map { event ->
        DeviceEvent(if (event.allDay) "All day" else event.start.format(DateTimeFormatter.ofPattern("h:mm a")), event.title, phoneEventDetail(event), event.location) { vm.openPhoneCalendar(event.start) }
    }
    com.localfirst.assistant.ui.dashboard.PersonalDashboard(actions, state.projectId.orEmpty(), state.privacy.incognito, state.workspaceBusy || state.busy,
        LocalAppearance.current.proactivity, onClose,
        DashboardNavigation(settings = { vm.openWorkspace(WorkspaceDestination.BRIEF) }, accounts = { vm.openWorkspace(WorkspaceDestination.ACCOUNTS) },
            appSettings = { vm.openWorkspace(WorkspaceDestination.SETTINGS) }, talk = vm::chatAbout, planDay = vm::discussDailyBrief,
            openFriday = { vm.openFriday(it) }, askFriday = { vm.askFriday(it) }),
        DeviceCalendar(phone, state.dashboard.phoneCalendarNeedsPermission, vm::allowPhoneCalendar))
}

/** The same "1 hr · Place" line for an event from the phone's calendar. */
internal fun phoneEventDetail(event:com.localfirst.assistant.tools.phone.CalendarEntry):String? {
    val minutes=event.end?.let { Duration.between(event.start,it).toMinutes() }?.takeIf { !event.allDay&&it>0 }
    val length=minutes?.let { when { it<60 -> "$it min"; it%60==0L -> "${it/60} hr"; else -> "${it/60} hr ${it%60} min" } }
    return listOfNotNull(length,event.location?.takeIf(String::isNotBlank)).joinToString(" · ").ifBlank { null }
}
