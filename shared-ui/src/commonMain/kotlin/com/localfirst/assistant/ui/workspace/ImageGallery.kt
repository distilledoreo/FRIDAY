package com.localfirst.assistant.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import com.localfirst.assistant.client.*
import com.localfirst.assistant.ui.AppIcons
import com.localfirst.assistant.ui.FridayDialogWindow
import com.localfirst.assistant.ui.LineIcons
import kotlinx.serialization.json.*

/** Shapes as width:height, and sizes by the longest side; the renderer works in multiples of 32 up to 1984. */
internal val IMAGE_ASPECTS = listOf(1 to 1, 4 to 3, 3 to 4, 3 to 2, 2 to 3, 16 to 9, 9 to 16)
internal val IMAGE_LONGEST = listOf(1024, 1344, 1536, 1984)
internal const val DEFAULT_LONGEST = 1344

/** Width and height for a shape at a longest side, the short side snapped to a multiple of 32. */
internal fun imageSize(aspect: Pair<Int, Int>, longest: Int): Pair<Int, Int> {
    val (w, h) = aspect
    val short = (kotlin.math.round(longest.toDouble() * minOf(w, h) / maxOf(w, h) / 32) * 32).toInt().coerceAtLeast(256)
    return if (w >= h) longest to short else short to longest
}

/** A rough time on this PC's P100, measured 2026-10-24: ~30 s to read a new prompt, steps that grow faster than the
 *  pixels (attention), and decoding in proportion to them. */
internal fun imageTimeLabel(width: Int, height: Int): String {
    val mp = width * height / 1_048_576.0
    val seconds = 30 + 17 * mp + 9 * mp * mp + 11 * mp
    return if (seconds < 90) "about ${(seconds / 5).toInt() * 5 + 5} s" else "about ${kotlin.math.round(seconds / 30) / 2.0} min".replace(".0 min", " min")
}

/** What a gallery tile shows for a job: its finished image id, or how far along it is. */
internal fun JsonObject.artifactId(): String? = (this["artifact"] as? JsonObject)?.string("id")?.takeIf { it.isNotBlank() }
internal fun JsonObject.finished() = string("status") in setOf("completed", "failed", "cancelled")

/** 0..1 from the job's progress, which the server reports as a fraction, a percentage or {step, steps}. */
internal fun JsonObject.progressFraction(): Float? = when (val p = this["progress"]) {
    is JsonPrimitive -> p.floatOrNull?.let { if (it > 1f) it / 100f else it }
    is JsonObject -> p["step"]?.jsonPrimitive?.floatOrNull?.let { step -> p["steps"]?.jsonPrimitive?.floatOrNull?.takeIf { it > 0 }?.let { step / it } }
    else -> null
}?.coerceIn(0f, 1f)

@Composable
internal fun ImageThumb(path: String, label: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val loader = LocalFeatureImages.current
    val bitmap by produceState<ImageBitmap?>(null, path, loader) { value = runCatching { loader(path) }.getOrNull() }
    Box(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, label, Modifier.fillMaxSize(), contentScale = contentScale) }
            ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/** The prompt bar: describe, pick a shape, add up to two references; the rest is under "More". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageComposer(
    prompt: String, onPrompt: (String) -> Unit,
    aspect: Int, onAspect: (Int) -> Unit,
    longest: Int, onLongest: (Int) -> Unit,
    seed: String, onSeed: (String) -> Unit,
    transparent: Boolean, onTransparent: (Boolean) -> Unit,
    references: List<String>, onRemoveReference: (String) -> Unit, onAddReference: () -> Unit,
    busy: Boolean, onGenerate: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    val seedValid = seed.isBlank() || seed.toIntOrNull()?.let { it >= 0 } == true
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                TextField(prompt, { onPrompt(it.take(4000)) }, Modifier.weight(1f), maxLines = 5,
                    placeholder = { Text(if (references.isEmpty()) "Describe an image…" else "Describe the change…") },
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                FilledIconButton(onGenerate, enabled = prompt.isNotBlank() && seedValid && !busy, shape = CircleShape) {
                    Icon(AppIcons.ArrowUpward, "Create image", Modifier.size(20.dp))
                }
            }
            if (references.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Editing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                references.forEach { id ->
                    Box(Modifier.size(52.dp)) {
                        ImageThumb("/workspace/files/$id", "Reference image", Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                        Surface(onClick = { onRemoveReference(id) }, shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface,
                            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp)) {
                            Icon(LineIcons.Close, "Remove reference", Modifier.padding(3.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                IMAGE_ASPECTS.forEachIndexed { i, (w, h) -> FilterChip(aspect == i, { onAspect(i) }, label = { Text("$w:$h") }, colors = choiceChipColors()) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                IMAGE_LONGEST.forEach { size -> FilterChip(longest == size, { onLongest(size) }, label = { Text(if (size == 1984) "2K" else "$size") }, colors = choiceChipColors()) }
            }
            val (width, height) = imageSize(IMAGE_ASPECTS[aspect], longest)
            Text("$width × $height · ${imageTimeLabel(width, height)}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AssistChip(onAddReference, label = { Text("Reference") }, enabled = references.size < 2,
                    leadingIcon = { Icon(LineIcons.Add, null, Modifier.size(16.dp)) })
                TextButton({ more = !more }) { Text(if (more) "Fewer options" else "More options") }
            }
            if (more) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(seed, { onSeed(it.filter(Char::isDigit).take(10)) }, Modifier.weight(1f), singleLine = true,
                        label = { Text("Seed (blank = random)") }, isError = !seedValid)
                    Text("Transparent", style = MaterialTheme.typography.bodyMedium)
                    Switch(transparent, onTransparent)
                }
            }
        }
    }
}

/** One square in the gallery: the image, or its progress while it renders, or why it failed. */
@Composable
internal fun ImageTile(job: JsonObject, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        val artifact = job.artifactId()
        when {
            artifact != null -> ImageThumb("/workspace/files/$artifact", job.string("prompt").take(120), Modifier.fillMaxSize())
            !job.finished() -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(12.dp)) {
                val progress = job.progressFraction()
                if (progress != null) CircularProgressIndicator(progress = { progress }, Modifier.size(32.dp)) else CircularProgressIndicator(Modifier.size(32.dp))
                Text(job.string("phase").ifBlank { job.string("status") }.replace('_', ' ').replaceFirstChar { it.uppercase() } +
                    (progress?.let { " · ${(it * 100).toInt()}%" } ?: ""), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                Text(if (job.string("status") == "cancelled") "Cancelled" else "Couldn't create", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error)
                Text(job.string("prompt"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Over the gallery: the image with its prompt and settings, and what to do with it. An overlay rather than a dialog
 *  window, which the web build measures smaller than the screen. Closes with its button, Escape or system back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageViewer(job: JsonObject, busy: Boolean, onClose: () -> Unit, onSave: (String) -> Unit, onEdit: (String) -> Unit,
                         onReuse: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val request = job["request"] as? JsonObject
    val artifact = job.artifactId()
    com.localfirst.assistant.ui.PlatformBackHandler(onBack = onClose)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().focusRequester(focus).focusable()
        .onKeyEvent { if (it.key == androidx.compose.ui.input.key.Key.Escape) { onClose(); true } else false }
        // Taps on the backdrop stay here instead of reaching the gallery underneath.
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0D0D0F), contentColor = Color.White) {
            // Clear of the status and navigation bars (Android draws edge to edge), so the buttons are never under them.
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Image", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClose) { Icon(LineIcons.Close, "Close", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (artifact != null) ImageThumb("/workspace/files/$artifact", job.string("prompt").take(120), Modifier.fillMaxSize(), ContentScale.Fit)
                    else ImageTile(job, Modifier.widthIn(max = 280.dp).fillMaxWidth()) {}
                }
                // A long prompt scrolls in its own space instead of pushing the buttons off the screen.
                Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectionContainer { Text(job.string("prompt"), style = MaterialTheme.typography.bodyMedium) }
                request?.let { r ->
                    val details = listOfNotNull(
                        "${r.int("width")}×${r.int("height")}".takeIf { r.int("width") > 0 },
                        r["seed"]?.jsonPrimitive?.longOrNull?.let { "seed $it" },
                        "transparent".takeIf { r["transparent"]?.jsonPrimitive?.booleanOrNull == true },
                        r["reference_file_ids"]?.jsonArray?.size?.takeIf { it > 0 }?.let { "edited from $it image" + if (it > 1) "s" else "" },
                    )
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .7f))
                }
                job.string("error").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (artifact != null) {
                        Button({ onSave(artifact) }) { Text("Save") }
                        OutlinedButton({ onEdit(artifact) }) { Text("Edit") }
                    }
                    OutlinedButton(onReuse) { Text("Reuse prompt") }
                    if (!job.finished()) OutlinedButton(onCancel, enabled = !busy) { Text("Cancel") }
                    else TextButton({ confirmDelete = true }, enabled = !busy) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { FridayDialogWindow(); Text("Delete this image?") },
        text = { Text("It's removed from your image history. A copy you saved elsewhere, or one in a chat, stays.") },
        confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}

/** Choose up to two images to edit from: your uploads and earlier results, newest first. */
@Composable
internal fun ReferencePicker(files: List<JsonObject>, selected: List<String>, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(selected) }
    val images = files.filter { it.string("mime").startsWith("image/") }
    AlertDialog(onDismissRequest = onDismiss, title = { FridayDialogWindow(); Text("Edit from an image") },
        text = {
            if (images.isEmpty()) Text("No images yet. Upload one in a chat, or create one first.")
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pick up to two. FRIDAY keeps what you don't ask to change.", style = MaterialTheme.typography.bodySmall)
                LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.heightIn(max = 420.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(images, key = { it.string("id") }) { file ->
                        val id = file.string("id"); val on = id in chosen
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                            .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                            .clickable(enabled = on || chosen.size < 2) { chosen = if (on) chosen - id else chosen + id }) {
                            ImageThumb("/workspace/files/$id", file.string("name"), Modifier.fillMaxSize())
                            if (on) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text("${chosen.indexOf(id) + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onDone(chosen) }) { Text("Done") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
private fun choiceChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = com.localfirst.assistant.ui.theme.LocalFridayPalette.current.accent.copy(alpha = .22f),
    selectedLabelColor = MaterialTheme.colorScheme.onSurface)

private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: 0
