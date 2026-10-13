package com.localfirst.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Dashboard and daily brief settings: the shared screen, with this phone's calendar as a source. */
@Composable
internal fun DailyBriefPage(state: ChatUiState, vm: ChatViewModel, onOpen: (WorkspaceDestination) -> Unit = {}) {
    val context = LocalContext.current
    val actions = rememberNativeDashboardActions(vm, state)
    val phoneAllowed = com.localfirst.assistant.phone.PermissionBroker.isGranted(context, android.Manifest.permission.READ_CALENDAR)
    com.localfirst.assistant.ui.dashboard.BriefSettingsScreen(actions, state.projectId.orEmpty(), state.privacy.incognito, state.busy, "phone",
        onAccounts = { onOpen(WorkspaceDestination.ACCOUNTS) },
        deviceCalendar = com.localfirst.assistant.ui.dashboard.DeviceCalendarChoice("This phone’s calendar", "Whatever calendars are synced to this phone", phoneAllowed, vm::allowPhoneCalendar),
        notificationNote = "Android may deliver them a few minutes late.")
}
