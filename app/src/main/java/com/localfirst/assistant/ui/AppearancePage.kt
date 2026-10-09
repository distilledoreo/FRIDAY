@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.localfirst.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.settings.*
import com.localfirst.assistant.ui.theme.*

@Composable
internal fun AppearancePage() {
    val settings=LocalAppearance.current
    val save=LocalSaveAppearance.current
    var accent by remember(settings.accentHex) { mutableStateOf(settings.accentHex) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text("Make FRIDAY yours",style=MaterialTheme.typography.titleLarge)
        AppearanceGroup("Theme") {
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode -> FilterChip(selected=settings.theme==mode,onClick={save(settings.copy(theme=mode))},label={Text(mode.label)},shape=MaterialTheme.shapes.small,modifier=Modifier.heightIn(min=48.dp)) }
            }
        }
        AppearanceGroup("Signature accent") {
            Text("A small touch of color, wherever it matters.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                listOf("E89980" to "Coral","E5BC78" to "Amber","91BCA0" to "Sage","88ADD0" to "Blue","B5A0D7" to "Lilac").forEach { (hex,name) ->
                    IconButton(onClick={save(settings.copy(accentHex=hex))},modifier=Modifier.size(48.dp).semantics { contentDescription="$name accent${if(settings.accentHex==hex) ", selected" else ""}" }) {
                        Box(Modifier.size(if(settings.accentHex==hex)28.dp else 22.dp).background(Color(0xFF000000L or hex.toLong(16)),CircleShape))
                    }
                }
            }
            OutlinedTextField(accent,{accent=it.take(7)},label={Text("Custom accent · hex color")},prefix={Text("#")},singleLine=true,
                isError=AppearanceSettings.accent(accent)==null,supportingText={if(AppearanceSettings.accent(accent)==null)Text("Enter six hex digits, such as E89980.")},modifier=Modifier.fillMaxWidth())
            TextButton(shape = MaterialTheme.shapes.small, onClick={AppearanceSettings.accent(accent)?.let { save(settings.copy(accentHex=it)) }},enabled=AppearanceSettings.accent(accent)!=null) { Text("Apply accent") }
        }
        AppearanceGroup("Proactivity") {
            Text("Suggestions on your personal dashboard. Notifications keep their separate Daily brief controls.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            ProactivityLevel.entries.forEach { level ->
                Surface(onClick={save(settings.copy(proactivity=level))},color=Color.Transparent,shape=MaterialTheme.shapes.small) {
                    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(settings.proactivity==level,{save(settings.copy(proactivity=level))})
                        Column(Modifier.weight(1f)) {
                            Text(level.label,style=MaterialTheme.typography.bodyLarge)
                            Text(when(level){ProactivityLevel.CONSERVATIVE->"Due follow-ups and essential attention";ProactivityLevel.THOUGHTFUL->"A few relevant contextual opportunities";ProactivityLevel.HIGH->"More opportunities from your real sources"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        AppearanceGroup("You") {
            var name by remember(settings.displayName) { mutableStateOf(settings.displayName) }
            OutlinedTextField(name,{ name=it.take(60) },label={Text("Your name")},singleLine=true,
                supportingText={Text("Optional. Shows your initial on the profile button; stays on this phone.")},modifier=Modifier.fillMaxWidth())
            TextButton(shape=MaterialTheme.shapes.small,onClick={save(settings.copy(displayName=name.trim()))},enabled=name.trim()!=settings.displayName) { Text("Save name") }
        }
        AppearanceGroup("Motion") {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Reduce motion",style=MaterialTheme.typography.bodyLarge);Text("Static ambient light and instant transitions. Your Android motion setting is respected too.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(settings.reducedMotion,{save(settings.copy(reducedMotion=it))},Modifier.semantics { contentDescription="Reduce motion" })
            }
        }
    }
}

@Composable
private fun AppearanceGroup(title:String,content:@Composable ColumnScope.()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
        }
    }
}
