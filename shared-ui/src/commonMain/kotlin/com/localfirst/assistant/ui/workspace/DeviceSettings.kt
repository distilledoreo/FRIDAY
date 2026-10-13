package com.localfirst.assistant.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Choices that stay on this device, as the phone keeps its voice and notification settings. */
class DeviceSettings(
    val voiceEngine: String, val voiceEngines: List<Pair<String, String>>, val bargeIn: Boolean,
    val notifications: String, val notificationsAvailable: Boolean, val pushAvailable: Boolean, val notificationNote: String?,
)

@Composable
fun DeviceSettingsSection(settings: DeviceSettings, onVoiceEngine: (String) -> Unit, onBargeIn: (Boolean) -> Unit, onNotifications: (String) -> Unit) {
    Text("Voice", style = MaterialTheme.typography.titleMedium)
    settings.voiceEngines.forEach { (id, label) ->
        Row(Modifier.fillMaxWidth().clickable { onVoiceEngine(id) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(settings.voiceEngine == id, { onVoiceEngine(id) }); Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Talk to interrupt")
            Text("In voice mode, start talking to stop FRIDAY and be heard. Turn off if speakers set it off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Switch(settings.bargeIn, onBargeIn)
    }
    HorizontalDivider()
    Text("Notifications", style = MaterialTheme.typography.titleMedium)
    Text("Finished tasks, approvals, PC questions and check-ins.", style = MaterialTheme.typography.bodySmall)
    listOf("off" to "No notifications", "open" to "With tab open", "closed" to "With tab closed").forEach { (mode, label) ->
        val available = mode == "off" || settings.notificationsAvailable && (mode != "closed" || settings.pushAvailable)
        Row(Modifier.fillMaxWidth().clickable(enabled = available) { onNotifications(mode) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(settings.notifications == mode, { onNotifications(mode) }, enabled = available)
            Text(label)
        }
    }
    Text("With tab closed uses your browser's push service. Delivery depends on browser and OS background settings. Locking FRIDAY keeps this choice; select No notifications to unsubscribe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    settings.notificationNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    HorizontalDivider()
}
