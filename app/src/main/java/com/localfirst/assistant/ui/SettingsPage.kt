package com.localfirst.assistant.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.localfirst.assistant.phone.MediaListenerService
import com.localfirst.assistant.settings.ServerSettings

@Composable
internal fun SettingsPage(
    initial: ServerSettings,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (ServerSettings) -> Unit,
) {
    var baseUrl by remember(initial) { mutableStateOf(initial.baseUrl) }
    var model by remember(initial) { mutableStateOf(initial.model) }
    var apiKey by remember(initial) { mutableStateOf(initial.apiKey) }
    var timeout by remember(initial) { mutableStateOf(initial.timeoutSeconds.toString()) }
    var searchUrl by remember(initial) { mutableStateOf(initial.searchBaseUrl) }
    var searchApiKey by remember(initial) { mutableStateOf(initial.searchApiKey) }
    var bargeIn by remember(initial) { mutableStateOf(initial.voiceBargeIn) }
    var voiceEngine by remember(initial) { mutableStateOf(initial.voiceEngine) }
    var voiceName by remember(initial) { mutableStateOf(initial.voiceName) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Server", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "OpenAI-compatible root, usually ending in /v1. The app calls /chat/completions on it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server address") },
                    placeholder = { Text(ServerSettings.DEFAULT_BASE_URL) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model name") },
                    placeholder = { Text("llama3.2") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { timeout = it.filter { ch -> ch.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Timeout seconds") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("Search", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Separate from the model. The phone POSTs /search to this address. Leave blank to keep chat and volume without web search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = searchUrl,
                    onValueChange = { searchUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search service address") },
                    placeholder = { Text(ServerSettings.DEFAULT_SEARCH_BASE_URL) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = searchApiKey,
                    onValueChange = { searchApiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search API key (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                VoiceSection(
                    engine = voiceEngine,
                    onEngineChange = { voiceEngine = it },
                    voiceName = voiceName,
                    onVoiceChange = { voiceName = it },
                    bargeIn = bargeIn,
                    onBargeInChange = { bargeIn = it },
                )
                PhoneAccessSection()
                if (error != null) {
                    Text(text = error, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSave(
                                ServerSettings(
                                    baseUrl = baseUrl,
                                    model = model,
                                    apiKey = apiKey,
                                    timeoutSeconds = timeout.toIntOrNull() ?: -1,
                                    searchBaseUrl = searchUrl,
                                    searchApiKey = searchApiKey,
                                    voiceBargeIn = bargeIn,
                                    voiceEngine = voiceEngine,
                                    voiceName = voiceName,
                                ),
                            )
                        },
                    ) {
                        Text("Save")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

/**
 * Notification access is a special permission the app can't request with a
 * dialog; it only links to the system screen. Other permissions are asked for
 * the first time a tool needs them.
 */
@Composable
private fun PhoneAccessSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(MediaListenerService.isEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        enabled = MediaListenerService.isEnabled(context)
        onPauseOrDispose { }
    }
    Text("Phone access", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "Contacts, calls, texts, and calendar are requested the first time you ask for them. " +
            "Calls and texts always ask for your approval in the chat. " +
            "Notification access lets the assistant see what's playing (for example in Spotify) and control that player directly; " +
            "it is used only for media.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, MediaListenerService.component(context).flattenToString())
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        },
    ) {
        Text(if (enabled) "Notification access: allowed" else "Allow notification access")
    }
}

@Composable
private fun VoiceSection(
    engine: String,
    onEngineChange: (String) -> Unit,
    voiceName: String,
    onVoiceChange: (String) -> Unit,
    bargeIn: Boolean,
    onBargeInChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(isDefaultAssistant(context)) }
    LifecycleResumeEffect(Unit) {
        isDefault = isDefaultAssistant(context)
        onPauseOrDispose { }
    }
    Text("Voice & assistant", style = MaterialTheme.typography.titleMedium)
    EngineOption(
        selected = engine == ServerSettings.VOICE_COMPUTER,
        title = "Your computer (best)",
        detail = "Parakeet speech recognition and Kokoro voices on the computer at the search service address. " +
            "Audio stays on your devices. Falls back to the phone if the computer can't be reached.",
        onSelect = { onEngineChange(ServerSettings.VOICE_COMPUTER) },
    )
    EngineOption(
        selected = engine == ServerSettings.VOICE_PHONE,
        title = "This phone",
        detail = "Android's speech recognition and text-to-speech. Works without the computer's voice service; lower quality.",
        onSelect = { onEngineChange(ServerSettings.VOICE_PHONE) },
    )
    if (engine == ServerSettings.VOICE_COMPUTER) VoicePicker(voiceName, onVoiceChange)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Interrupt by talking", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "In voice mode, start speaking to cut the assistant off. Works best with headphones; " +
                    "on the loudspeaker it needs a clearly louder voice. Tapping always interrupts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = bargeIn, onCheckedChange = onBargeInChange)
    }
    OutlinedButton(
        onClick = {
            // Android doesn't let apps make themselves the assistant; send the user to the chooser.
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        },
    ) {
        Text(if (isDefault) "Default assistant: this app" else "Set as default assistant")
    }
    if (!isDefault) {
        Text(
            text = "Choose Digital assistant app → Assistant. Then long-press the power button (or your assistant gesture) " +
                "to start a new voice chat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun isDefaultAssistant(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val roles = context.getSystemService(RoleManager::class.java) ?: return false
    return roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
}

@Composable
private fun EngineOption(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A short list of Kokoro's best-rated English voices. */
private val KOKORO_VOICES = listOf(
    "af_heart" to "Heart — warm, US female",
    "af_bella" to "Bella — bright, US female",
    "af_nicole" to "Nicole — soft, US female",
    "am_michael" to "Michael — US male",
    "am_fenrir" to "Fenrir — deep, US male",
    "bf_emma" to "Emma — British female",
    "bm_george" to "George — British male",
)

@Composable
private fun VoicePicker(voiceName: String, onVoiceChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = KOKORO_VOICES.firstOrNull { it.first == voiceName }?.second ?: voiceName
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Voice: $label") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            KOKORO_VOICES.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onVoiceChange(id)
                        open = false
                    },
                )
            }
        }
    }
}
