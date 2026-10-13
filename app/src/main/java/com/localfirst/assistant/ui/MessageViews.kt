package com.localfirst.assistant.ui

import com.localfirst.assistant.presentation.UiBlocks
import com.localfirst.assistant.presentation.UiSegment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.presentation.ToolStep
import com.localfirst.assistant.presentation.ToolStepState
import com.localfirst.assistant.presentation.TranscriptItem
import com.localfirst.assistant.presentation.ResponseLayout
import com.localfirst.assistant.presentation.ResponsePattern
import com.localfirst.assistant.tools.SourceLink
import com.mikepenz.markdown.m3.Markdown
import com.localfirst.assistant.platform.PlatformUri

@Composable
internal fun UserMessage(item: TranscriptItem.User, actionsEnabled: Boolean, onCopy: (String) -> Unit,
    onEdit: (Int) -> Unit, onBranch: ((Int) -> Unit)? = null) = SharedUserMessage(item, actionsEnabled, onCopy,
    onEdit, onBranch, attachments = { if (item.attachments.isNotEmpty()) MessageAttachments(item.attachments) })
@Composable
internal fun AssistantMessage(item: TranscriptItem.Assistant, actionsEnabled: Boolean, onCopy: (String) -> Unit,
    onRegenerate: () -> Unit, onChoose: ((String) -> Unit)? = null, onCanvas: ((String) -> Unit)? = null,
    onShare: ((String) -> Unit)? = null, onFeedback: ((Boolean) -> Unit)? = null,
    genUi: com.localfirst.assistant.ui.genui.GenUiHost? = null) =
    SharedAssistantMessage(item, actionsEnabled, onCopy, onRegenerate, onChoose, onCanvas, onShare, onFeedback,
        interfaceContent = if (genUi == null) null else ({ com.localfirst.assistant.ui.genui.GenUiMessage(item.index, item.text, item.streaming, genUi) }))
@Composable
internal fun ToolActivityCard(item: TranscriptItem.ToolActivity, imageLoader: suspend (String) -> java.io.File,
    openImage: (String) -> Unit, fridayCard: @Composable (String) -> Unit = {},
    pcCard: @Composable (String) -> Unit = {}, codeCard: @Composable (String) -> Unit = {}) =
    SharedToolActivityCard(item) { step ->
        step.agentTask?.let { fridayCard(it) } ?: step.pcTask?.let { pcCard(it) } ?: step.codeTask?.let { codeCard(it) }
        step.imageResult?.let { GeneratedImage(it, imageLoader, openImage) }
        if (step.agentTask == null && step.pcTask == null && step.codeTask == null && step.imageResult == null)
            step.resultContent?.let { ToolResultSurface(step.name, it, step.callId) }
    }

/** Asks the user to approve a tool call, such as a phone call or a text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApprovalCard(approval: PendingApproval, onAnswer: (Boolean) -> Unit, onAlways: () -> Unit = {}) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    toolIcon(approval.toolName),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = approval.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = approval.prompt,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { onAnswer(false) }) { Text("Deny") }
                approval.alwaysLabel?.let { always ->
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onAlways) { Text(always) }
                }
                Button(shape = MaterialTheme.shapes.small, onClick = { onAnswer(true) }) { Text(approval.approveLabel) }
            }
        }
    }
}

/** A drafted answer, edited side by side with the chat and saved to files. */
@Composable
internal fun CanvasSheet(
    doc: CanvasDoc,
    busy: Boolean,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onSave: () -> Unit,
    onCopy: (String) -> Unit,
    onClose: () -> Unit,
) {
    FridaySheet(onDismiss = onClose) { _, _, close ->
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Canvas", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = doc.title,
                onValueChange = onTitle,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = doc.body,
                onValueChange = onBody,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 200.dp),
                minLines = 8,
                maxLines = 30,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(shape = MaterialTheme.shapes.small, onClick = onSave, enabled = !busy && doc.body.isNotBlank()) { Text("Save to files") }
                TextButton(shape = MaterialTheme.shapes.small, onClick = { onCopy(doc.body) }) { Text("Copy") }
                TextButton(shape = MaterialTheme.shapes.small, onClick = { close?.invoke(); onClose() }) { Text("Close") }
            }
        }
    }
}
