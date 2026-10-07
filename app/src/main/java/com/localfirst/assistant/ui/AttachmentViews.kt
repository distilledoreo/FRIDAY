package com.localfirst.assistant.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localfirst.assistant.conversation.Attachment
import com.localfirst.assistant.conversation.AttachmentKind
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads a stored JPEG scaled down to about [maxEdgePx], off the main thread. */
@Composable
internal fun rememberImage(path: String?, maxEdgePx: Int): ImageBitmap? {
    var image by remember(path, maxEdgePx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, maxEdgePx) {
        image = path?.let { p ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(p, bounds)
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdgePx) sample *= 2
                    BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return image
}

@Composable
private fun Thumbnail(path: String?, size: Int, onClick: (() -> Unit)? = null) {
    val image = rememberImage(path, maxEdgePx = size * 3)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Attachments on the message being written, above the text field. */
@Composable
internal fun DraftAttachmentStrip(items: List<DraftAttachment>, onRemove: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp),
    ) {
        for (item in items) {
            Box {
                if (item.kind == AttachmentKind.IMAGE && item.status != DraftStatus.FAILED) {
                    Box(contentAlignment = Alignment.Center) {
                        Thumbnail(item.attachment?.path, size = 64)
                        if (item.status == DraftStatus.READING) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else {
                    FileChip(
                        name = item.name,
                        detail = when (item.status) {
                            DraftStatus.READING -> "Reading on your computer…"
                            DraftStatus.READY -> item.attachment?.let(::documentDetail)
                            DraftStatus.FAILED -> item.error
                        },
                        reading = item.status == DraftStatus.READING,
                        failed = item.status == DraftStatus.FAILED,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp),
                ) {
                    IconButton(onClick = { onRemove(item.id) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove ${item.name}",
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Attachments shown with a sent message: image thumbnails (tap to enlarge) and document chips. */
@Composable
internal fun MessageAttachments(attachments: List<Attachment>) {
    var viewing by remember { mutableStateOf<String?>(null) }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val images = attachments.filter { it.kind == AttachmentKind.IMAGE }
        if (images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                images.forEach { a -> Thumbnail(a.path, size = if (images.size == 1) 180 else 96) { viewing = a.path } }
            }
        }
        attachments.filter { it.kind == AttachmentKind.DOCUMENT }.forEach { doc ->
            FileChip(name = doc.name, detail = documentDetail(doc), onClick = doc.pageImages.firstOrNull()?.let { p -> { viewing = p } })
        }
    }
    viewing?.let { path -> ImageViewer(path) { viewing = null } }
}

@Composable
private fun FileChip(
    name: String,
    detail: String?,
    reading: Boolean = false,
    failed: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.widthIn(max = 260.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            when {
                reading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                failed -> Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun documentDetail(doc: Attachment): String {
    val type = doc.name.substringAfterLast('.', "").uppercase().ifEmpty { "File" }
    val size = when {
        doc.pageImages.isNotEmpty() -> "${doc.pageImages.size} page image${if (doc.pageImages.size == 1) "" else "s"}"
        doc.text != null -> "${"%,d".format(doc.text.orEmpty().length)} characters"
        else -> "no text found"
    }
    return listOfNotNull("$type · $size", doc.note).joinToString(" · ")
}

@Composable
internal fun ImageViewer(path: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val image = rememberImage(path, maxEdgePx = 2048)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim).clickable(onClick = onDismiss),
        ) {
            if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}
