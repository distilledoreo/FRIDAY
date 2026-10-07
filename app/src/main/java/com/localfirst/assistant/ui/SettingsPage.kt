package com.localfirst.assistant.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
