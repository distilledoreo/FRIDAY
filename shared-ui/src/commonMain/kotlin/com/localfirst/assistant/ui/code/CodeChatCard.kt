package com.localfirst.assistant.ui.code

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.ui.FridayCard
import com.localfirst.assistant.ui.LineIcons
import com.localfirst.assistant.ui.wire.JSONObject

/** A coding task started from chat: its live state, and the way into Code where the work happens. */
@Composable
fun CodeChatCard(json: String, code: CodeWorkspace, open: (String) -> Unit) {
    val stored = remember(json) { runCatching { JSONObject(json) }.getOrNull() } ?: return
    val id = stored.optString("id").takeIf { it.matches(Regex("[a-f0-9]{12}")) } ?: return
    val state by code.state.collectAsState()
    LaunchedEffect(id) { if (state.tasks.none { it.id == id }) code.refresh() }
    val task = state.tasks.firstOrNull { it.id == id } ?: CodeTask.from(stored)
    FridayCard(Modifier.fillMaxWidth(), onClick = { open(id) }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(LineIcons.Code, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(task.title.ifBlank { "Coding task" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${codeStateLabel(task)} · ${task.repo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (task.branch.isNotBlank()) Text(task.branch, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (task.stats.additions + task.stats.deletions > 0) LineCounts(task.stats.additions, task.stats.deletions)
                Text("Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
