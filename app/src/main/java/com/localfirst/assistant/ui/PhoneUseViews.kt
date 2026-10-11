package com.localfirst.assistant.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localfirst.assistant.presentation.PhoneUseActivity
import com.localfirst.assistant.presentation.ToolStepState
import com.localfirst.assistant.presentation.TranscriptItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A run of phone-use steps as one compact card, like Codex's "Used computer · N actions" cell: the
 * running step while active, then failures and screenshots first, with every step a tap away.
 */
@Composable
internal fun PhoneUseActivityCard(item: TranscriptItem.ToolActivity) {
    val summary = remember(item) { PhoneUseActivity.summarize(item.steps) }
    var expanded by remember(item.key) { mutableStateOf(false) }
    var viewing by remember(item.key) { mutableStateOf<String?>(null) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (summary.active) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(AppIcons.PhoneUse, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    summary.header,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (summary.failures > 0 && !summary.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Show fewer steps" else "Show every step",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            val rows = if (expanded) item.steps.indices.toList() else summary.preview
            rows.forEachIndexed { position, index ->
                val step = item.steps[index]
                val last = position == rows.lastIndex && (expanded || summary.hidden == 0)
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = 6.dp)) {
                    Text(if (last) "└ " else "├ ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text(
                        step.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.state == ToolStepState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 4 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (expanded && step.image != null) {
                        Text(
                            "Screenshot",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { viewing = step.image }.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (!expanded && summary.hidden > 0) {
                Text("└ ${summary.hidden} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 6.dp))
            }
            summary.latestImage?.takeIf { !summary.active }?.let { path ->
                ScreenshotThumbnail(path, onOpen = { viewing = path })
            }
        }
    }
    viewing?.let { path ->
        Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val bitmap = rememberScreenshot(path, maxEdge = 2400)
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().clickable { viewing = null }) {
                bitmap?.let { Image(it, contentDescription = "Phone screenshot", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp)) }
                    ?: Text("This screenshot is no longer on the phone.", Modifier.padding(24.dp))
            }
        }
    }
}

@Composable
private fun ScreenshotThumbnail(path: String, onOpen: () -> Unit) {
    val bitmap = rememberScreenshot(path, maxEdge = 480) ?: return
    Image(
        bitmap,
        contentDescription = "Latest phone screenshot",
        contentScale = ContentScale.Fit,
        modifier = Modifier.padding(top = 6.dp).heightIn(max = 180.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpen),
    )
}

@Composable
private fun rememberScreenshot(path: String, maxEdge: Int): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, path, maxEdge) {
        value = withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.isFile) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    return state.value
}
