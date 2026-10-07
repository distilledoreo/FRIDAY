package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

@Composable
internal fun Composer(
    draft: String,
    busy: Boolean,
    editing: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onCancelEdit: () -> Unit,
    onVoice: (() -> Unit)?,
    sendEnabled: Boolean,
    attachments: List<DraftAttachment> = emptyList(),
    editingHasAttachments: Boolean = false,
    onAttach: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (editing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (editingHasAttachments) "Editing message · attachments kept" else "Editing message",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                IconButton(onClick = onCancelEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel editing", modifier = Modifier.size(18.dp))
                }
            }
        }
        if (attachments.isNotEmpty()) DraftAttachmentStrip(attachments, onRemoveAttachment)
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(end = 6.dp, bottom = 6.dp),
            ) {
                IconButton(
                    onClick = onAttach,
                    enabled = !busy && !editing && attachments.size < ChatViewModel.MAX_ATTACHMENTS,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Attach photos or files")
                }
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                if (!busy && !editing && attachments.isNotEmpty() && attachments.all { it.status == DraftStatus.READY } && onVoice != null) {
                    IconButton(onClick = onVoice) { Icon(AppIcons.Mic, contentDescription = "Talk about attachments") }
                }
                if (busy) {
                    FilledIconButton(
                        onClick = onStop,
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Icon(AppIcons.Stop, contentDescription = "Stop", modifier = Modifier.size(18.dp))
                    }
                } else if (draft.isBlank() && attachments.isEmpty() && onVoice != null && !editing) {
                    FilledIconButton(
                        onClick = onVoice,
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Icon(AppIcons.Mic, contentDescription = "Voice mode", modifier = Modifier.size(20.dp))
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = sendEnabled,
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Icon(AppIcons.ArrowUpward, contentDescription = "Send", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
