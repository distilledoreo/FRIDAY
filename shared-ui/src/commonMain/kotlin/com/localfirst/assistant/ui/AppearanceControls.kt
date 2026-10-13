@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.localfirst.assistant.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.settings.*
import kotlinx.serialization.json.*
@Composable
fun AppearanceControls(prefs: AppearanceSettings, enabled: Boolean = true,
    revision: (String) -> Int = { 0 }, save: (String, JsonPrimitive, Int) -> Unit) {
    fun preference(key: String, value: JsonPrimitive, expected: Int = revision(key)) = save(key, value, expected)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (theme in ThemeMode.entries) FilterChip(prefs.theme == theme, { preference("theme", JsonPrimitive(theme.name)) }, label = { Text(theme.label) }, enabled = enabled) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((hex, name) in listOf("E89980" to "Coral", "E5BC78" to "Amber", "91BCA0" to "Sage", "88ADD0" to "Blue", "B5A0D7" to "Lilac")) {
                IconButton({ preference("accent", JsonPrimitive(hex)) }, enabled = enabled,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "$name accent${if (prefs.accentHex == hex) ", selected" else ""}" }) {
                    Box(Modifier.size(if (prefs.accentHex == hex) 28.dp else 22.dp).background(Color(0xFF000000L or hex.toLong(16)), CircleShape))
                }
            }
        }
        PreferenceText("Accent color", prefs.accentHex, revision("accent"), enabled) { text, revision -> val accent = AppearanceSettings.accent(text); if (accent != null) preference("accent", JsonPrimitive(accent), revision) }
        PreferenceText("Display name", prefs.displayName, revision("display_name"), enabled) { text, revision -> preference("display_name", JsonPrimitive(text.trim().take(60)), revision) }
        Text("Proactivity", style = MaterialTheme.typography.titleMedium)
        for (level in ProactivityLevel.entries) Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(prefs.proactivity == level, { preference("proactivity", JsonPrimitive(level.name)) }, enabled = enabled); Text(level.label) }
        SettingSwitch("Reduce motion", prefs.reducedMotion, enabled) { preference("reduced_motion", JsonPrimitive(it)) }
        SettingSwitch("Interactive answers", prefs.interactiveAnswers, enabled) { preference("interactive_answers", JsonPrimitive(it)) }
        Text("These preferences apply across your clients. Connection addresses, credentials and phone access stay on each device.", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, change, enabled = enabled) } }
@Composable private fun PreferenceText(label: String, value: String, revision: Int, enabled: Boolean, save: (String, Int) -> Unit) {
    var text by remember { mutableStateOf(value) }; var baseline by remember { mutableStateOf(revision) }; var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(value, revision) { if (!dirty || text == value) { text = value; baseline = revision; dirty = false } }
    Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(text, { if (!dirty) baseline = revision; text = it; dirty = it != value }, Modifier.weight(1f), label = { Text(label) }, singleLine = true); TextButton({ save(text, baseline) }, enabled = enabled && dirty) { Text("Save") } }
}
