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
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
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
import java.net.URI

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserMessage(
    item: TranscriptItem.User,
    actionsEnabled: Boolean,
    onCopy: (String) -> Unit,
    onEdit: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    if (item.attachments.isNotEmpty()) MessageAttachments(item.attachments)
                    if (item.text.isNotBlank()) Text(text = item.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    leadingIcon = { Icon(AppIcons.ContentCopy, contentDescription = null) },
                    onClick = {
                        menu = false
                        onCopy(item.text)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Edit message") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    enabled = actionsEnabled,
                    onClick = {
                        menu = false
                        onEdit(item.index)
                    },
                )
            }
        }
    }
}

@Composable
internal fun AssistantMessage(
    item: TranscriptItem.Assistant,
    actionsEnabled: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onChoose: ((String) -> Unit)? = null,
) {
    val plain = remember(item.text) { if (UiBlocks.contains(item.text)) UiBlocks.plainText(item.text) else item.text }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Text renders as before; FRIDAY's components sit between text at full width.
        UiBlocks.segments(item.text).forEachIndexed { index, segment ->
            when (segment) {
                is UiSegment.Text -> {
                    val pattern=ResponseLayout.choose(segment.markdown)
                    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface,modifier=if(pattern==ResponsePattern.BUBBLE)Modifier.fillMaxWidth(.94f)else Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal=16.dp,vertical=12.dp)) {
                            ResponseRenderer(segment.markdown.trim(),pattern) { onCopy(plain) }
                        }
                    }
                }
                is UiSegment.Block -> FridayComponent(segment.block, "${item.index}-$index", onChoose.takeIf { item.isLatest && actionsEnabled && !item.streaming })
                is UiSegment.Invalid -> ComponentFallback(segment.raw)
                UiSegment.Pending -> ComponentPending()
            }
        }
        if (item.streaming) {
            PulsingDot(modifier = Modifier.padding(top = 6.dp))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                SmallIconButton(AppIcons.ContentCopy, "Copy") { onCopy(plain) }
                if (item.isLatest) {
                    SmallIconButton(Icons.Filled.Refresh, "Regenerate", enabled = actionsEnabled, onClick = onRegenerate)
                }
            }
        }
    }
}

@Composable
internal fun ToolActivityCard(
    item: TranscriptItem.ToolActivity,
    imageLoader: suspend (String) -> java.io.File,
    openImage: (String) -> Unit,
    fridayCard: @Composable (String) -> Unit = {},
    pcCard: @Composable (String) -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item.steps.forEach { step ->
                step.agentTask?.let { fridayCard(it) } ?: step.pcTask?.let { pcCard(it) } ?: ToolStepRow(step)
                step.imageResult?.let { GeneratedImage(it, imageLoader, openImage) }
                if(step.agentTask==null&&step.pcTask==null&&step.imageResult==null)step.resultContent?.let { ToolResultSurface(step.name,it,step.callId) }
            }
        }
    }
}

@Composable
private fun ToolStepRow(step: ToolStep) {
    var expanded by remember(step.callId) { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = if (step.state == ToolStepState.FAILED) Icons.Filled.Warning else toolIcon(step.name)
            val tint = if (step.state == ToolStepState.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            if (step.state == ToolStepState.RUNNING) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = step.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (step.sources.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = !expanded }
                        .heightIn(min=48.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "${step.sources.size} sources",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Hide sources" else "Show sources",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        step.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 28.dp, top = 2.dp),
            )
        }
        AnimatedVisibility(visible = expanded,enter=if(com.localfirst.assistant.ui.theme.LocalFridayPalette.current.reducedMotion)androidx.compose.animation.EnterTransition.None else androidx.compose.animation.fadeIn(tween(180)),exit=if(com.localfirst.assistant.ui.theme.LocalFridayPalette.current.reducedMotion)androidx.compose.animation.ExitTransition.None else androidx.compose.animation.fadeOut(tween(150))) {
            Column(
                modifier = Modifier.padding(start = 28.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                step.sources.forEachIndexed { index, source -> SourceRow(index + 1, source) }
            }
        }
    }
}

@Composable
private fun SourceRow(number: Int, source: SourceLink) {
    val uriHandler = LocalUriHandler.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { runCatching { uriHandler.openUri(source.url) } }
            .padding(vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(20.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        ) {
            Text("$number", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = domainOf(source.url),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

internal fun toolIcon(name: String): ImageVector = when (name) {
    "web_search" -> Icons.Filled.Search
    "set_media_volume" -> AppIcons.VolumeUp
    "open_app" -> Icons.Filled.ExitToApp
    "open_url" -> Icons.Filled.Share
    "open_maps" -> Icons.Filled.Place
    "media_control", "play_music", "now_playing" -> Icons.Filled.PlayArrow
    "set_alarm", "set_timer" -> Icons.Filled.Notifications
    "flashlight" -> AppIcons.Flashlight
    "battery_status" -> Icons.Filled.Info
    "search_contacts" -> Icons.Filled.Person
    "place_call" -> Icons.Filled.Call
    "send_text" -> Icons.Filled.Email
    "add_calendar_event", "upcoming_events" -> Icons.Filled.DateRange
    else -> Icons.Filled.Build
}

/** Asks the user to approve a tool call, such as a phone call or a text. */
@Composable
internal fun ApprovalCard(approval: PendingApproval, onAnswer: (Boolean) -> Unit) {
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
                    text = "Allow this?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = approval.prompt,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { onAnswer(false) }) { Text("Deny") }
                Button(shape = MaterialTheme.shapes.small, onClick = { onAnswer(true) }) { Text("Approve") }
            }
        }
    }
}

@Composable
internal fun ThinkingIndicator() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        repeat(3) { i -> PulsingDot(delayMillis = i * 160) }
    }
}

@Composable
private fun PulsingDot(modifier: Modifier = Modifier, delayMillis: Int = 0) {
    if(com.localfirst.assistant.ui.theme.LocalFridayPalette.current.reducedMotion) {
        Box(modifier.size(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant,CircleShape));return
    }
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = delayMillis), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier = modifier
            .size(9.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.onSurface, CircleShape),
    )
}

@Composable
internal fun ErrorCard(message: String, retryEnabled: Boolean, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = onRetry, enabled = retryEnabled) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun SmallIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

internal fun domainOf(url: String): String =
    runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
