package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material3.InputChip
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
    voiceActive: Boolean = false,
    sendEnabled: Boolean,
    attachments: List<DraftAttachment> = emptyList(),
    editingHasAttachments: Boolean = false,
    onAttach: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    imageMode: Boolean = false,
    imageSummary: String = "Auto",
    onImageSettings: () -> Unit = {},
    onExitImageMode: () -> Unit = {},
    onDictate: (() -> Unit)? = null,
    dictating: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = com.localfirst.assistant.ui.theme.LocalFridayPalette.current
    val accent = palette.accent
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
                IconButton(onClick = onCancelEdit, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel editing", modifier = Modifier.size(18.dp))
                }
            }
        }
        if (attachments.isNotEmpty()) DraftAttachmentStrip(attachments, onRemoveAttachment)
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = if (palette.dark) Color(0xFF1E1E21) else Color.White,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.outline.copy(alpha = if (palette.dark) .28f else .18f),
                        MaterialTheme.colorScheme.outline.copy(alpha = if (palette.dark) .16f else .1f),
                        accent.copy(alpha = if (palette.dark) .5f else .35f),
                    ),
                ),
            ),
            shadowElevation = if (palette.dark) 0.dp else 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                if (imageMode && !editing) {
                    InputChip(shape = MaterialTheme.shapes.small,
                        selected = true, onClick = onImageSettings, enabled = !busy,
                        label = { Text("Create image · $imageSummary") },
                        trailingIcon = { IconButton(onClick = onExitImageMode, enabled = !busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, "Exit image mode", Modifier.size(16.dp)) } },
                        modifier = Modifier.padding(start = 14.dp, top = 8.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    Surface(
                        onClick = onAttach,
                        enabled = !busy && !editing,
                        shape = CircleShape,
                        color = if (palette.dark) Color.White.copy(alpha = .07f) else Color.Black.copy(alpha = .05f),
                        modifier = Modifier.padding(start = 4.dp).size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(LineIcons.Add, contentDescription = "Attachments and tools", modifier = Modifier.size(22.dp)) }
                    }
                    TextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (imageMode && !editing) "Describe an image" else "Message FRIDAY…",style=MaterialTheme.typography.bodyMedium) },
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
                    if (onDictate != null && !voiceActive) IconButton(onClick = onDictate, enabled = !busy, modifier = Modifier.size(44.dp)) {
                        Icon(LineIcons.Mic, contentDescription = if (dictating) "Stop dictation" else "Dictate a message",
                            tint = if (dictating) accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
                    }
                    val hasText = draft.isNotBlank() || attachments.isNotEmpty() || editing
                    if (onVoice != null && !busy && !hasText && !voiceActive) {
                        Surface(
                            onClick = onVoice,
                            shape = CircleShape,
                            color = accent.copy(alpha = if (voiceActive) .9f else if (palette.dark) .26f else .16f),
                            modifier = Modifier.padding(end = 2.dp).size(44.dp).semantics { contentDescription = if (voiceActive) "End voice conversation" else "Start voice conversation" },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                WaveformIcon(18.dp, if (voiceActive) MaterialTheme.colorScheme.onPrimary else if (palette.dark) com.localfirst.assistant.ui.theme.readableAccent(accent, Color(0xFF1E1E21)) else com.localfirst.assistant.ui.theme.readableAccent(accent, Color.White))
                            }
                        }
                    } else if (busy) {
                        FilledIconButton(
                            onClick = onStop,
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(AppIcons.Stop, contentDescription = "Stop", modifier = Modifier.size(18.dp))
                        }
                    } else if (hasText) {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = sendEnabled,
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(AppIcons.ArrowUpward, contentDescription = "Send", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
