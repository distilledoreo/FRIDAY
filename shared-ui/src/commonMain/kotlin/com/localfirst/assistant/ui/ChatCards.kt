package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.client.CanvasDoc

/** Asks the user to approve a tool call before it runs, such as a PC task, a calendar read or a text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalCard(
    toolName: String,
    title: String,
    prompt: String,
    onAnswer: (Boolean) -> Unit,
    approveLabel: String = "Approve",
    alwaysLabel: String? = null,
    onAlways: () -> Unit = {},
    enabled: Boolean = true,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(toolIcon(toolName), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(prompt, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { onAnswer(false) }, enabled = enabled) { Text("Deny") }
                alwaysLabel?.let { always -> OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onAlways, enabled = enabled) { Text(always) } }
                Button(shape = MaterialTheme.shapes.small, onClick = { onAnswer(true) }, enabled = enabled) { Text(approveLabel) }
            }
        }
    }
}

/** A drafted answer, edited beside the chat and saved to files. */
@Composable
fun CanvasSheet(
    doc: CanvasDoc,
    busy: Boolean,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onSave: () -> Unit,
    onCopy: (String) -> Unit,
    onClose: () -> Unit,
    saveLabel: String = "Save to files",
    onDownload: (() -> Unit)? = null,
) {
    FridaySheet(onDismiss = onClose) { _, _, close ->
        // Scrolls so the actions stay reachable when the sheet is short.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Canvas", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = doc.title, onValueChange = onTitle, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = doc.body, onValueChange = onBody, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp), minLines = 8, maxLines = 30)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(shape = MaterialTheme.shapes.small, onClick = onSave, enabled = !busy && doc.body.isNotBlank()) { Text(saveLabel) }
                onDownload?.let { TextButton(shape = MaterialTheme.shapes.small, onClick = it, enabled = !busy && doc.body.isNotBlank()) { Text("Download") } }
                TextButton(shape = MaterialTheme.shapes.small, onClick = { onCopy(doc.body) }) { Text("Copy") }
                TextButton(shape = MaterialTheme.shapes.small, onClick = { close?.invoke(); onClose() }) { Text("Close") }
            }
        }
    }
}
