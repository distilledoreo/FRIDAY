package com.localfirst.assistant.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.client.*
import com.localfirst.assistant.ui.AppIcons
import com.localfirst.assistant.ui.FridayDialogWindow
import com.localfirst.assistant.ui.LineIcons
import kotlinx.serialization.json.*

/** A playable address for a saved video (a cached file on Android, a blob URL in the browser), from its file path. */
val LocalFeatureVideos = staticCompositionLocalOf<suspend (String) -> String?> { { null } }

/** Plays a video, with the platform's own controls. */
@Composable
internal expect fun VideoSurface(url: String, modifier: Modifier)

internal val VIDEO_QUALITY_NAMES = listOf("Fastest", "Faster", "Balanced", "Better", "Best")

/** One quality level from the computer's options table: the size it makes and what that costs. */
internal data class VideoLevel(val width: Int, val height: Int, val frames: Int, val pausesChat: Boolean,
                               val estimateSeconds: Int, val chatPausedSeconds: Int)

internal fun JsonObject.videoDurations(): List<Int> = (this["durations"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
internal fun JsonObject.videoAspects(): List<String> = (this["aspects"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
internal fun JsonObject.videoInputs(): List<String> = (this["inputs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

/** The levels for a shape and length, fastest first; empty when that length doesn't fit. */
internal fun JsonObject.videoLevels(aspect: String, seconds: Int): List<VideoLevel> =
    (((this["plans"] as? JsonObject)?.get(aspect) as? JsonObject)?.get(seconds.toString()) as? JsonArray).orEmpty().mapNotNull { row ->
        val o = row as? JsonObject ?: return@mapNotNull null
        fun int(key: String) = (o[key] as? JsonPrimitive)?.intOrNull ?: 0
        VideoLevel(int("width"), int("height"), int("frames"), (o["pauses_chat"] as? JsonPrimitive)?.booleanOrNull == true,
            int("estimate_seconds"), int("chat_paused_seconds"))
    }

internal fun minutesLabel(seconds: Int): String = if (seconds < 90) "about 1 min" else "about ${(seconds + 30) / 60} min"

/** What the chosen level means: its size and time, and whether chat stops meanwhile. */
internal fun VideoLevel.summary() = "$width × $height · ${minutesLabel(estimateSeconds)}"
internal fun VideoLevel.chatNote() = if (pausesChat) "Uses both GPUs: chat and voice pause for ${minutesLabel(chatPausedSeconds)}." else "Chat keeps working while it renders."

/** Where a video job is, in words, with time left from its progress and estimate. */
internal fun JsonObject.videoStatus(): String {
    val step = this["step"] as? JsonObject
    val label = when (string("phase")) {
        "queued" -> "Waiting"
        "preparing", "pausing_images" -> "Preparing"
        "unloading_chat" -> "Pausing chat"
        "starting_worker", "loading" -> "Loading the model"
        "sampling" -> step?.let { "Rendering · step ${it["step"]?.jsonPrimitive?.intOrNull}/${it["steps"]?.jsonPrimitive?.intOrNull}" } ?: "Rendering"
        "decoding" -> "Decoding"
        "saving", "encoding" -> "Saving"
        "restoring_chat" -> "Restoring chat"
        else -> string("phase").ifBlank { string("status") }.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    val estimate = ((this["plan"] as? JsonObject)?.get("estimate_seconds") as? JsonPrimitive)?.intOrNull
    val progress = progressFraction()
    val left = if (string("status") == "running" && estimate != null && progress != null) " · ${minutesLabel(((1 - progress) * estimate).toInt())} left" else ""
    return label + left
}

/** The prompt bar: describe, choose a length and shape, then slide between speed and quality. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoComposer(
    options: JsonObject, prompt: String, onPrompt: (String) -> Unit,
    seconds: Int, onSeconds: (Int) -> Unit, aspect: String, onAspect: (String) -> Unit,
    quality: Int, onQuality: (Int) -> Unit, seed: String, onSeed: (String) -> Unit,
    inputs: Map<String, String>, onPickInput: (String) -> Unit, onRemoveInput: (String) -> Unit,
    busy: Boolean, onGenerate: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    val durations = options.videoDurations()
    val levels = options.videoLevels(aspect, seconds)
    val level = levels.getOrNull(quality.coerceIn(0, (levels.size - 1).coerceAtLeast(0)))
    val seedValid = seed.isBlank() || seed.toIntOrNull()?.let { it >= 0 } == true
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                TextField(prompt, { onPrompt(it.take(4000)) }, Modifier.weight(1f), maxLines = 5,
                    placeholder = { Text("Describe a video: the scene, the motion, the sound…") },
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                FilledIconButton(onGenerate, enabled = prompt.isNotBlank() && seedValid && level != null && !busy, shape = CircleShape) {
                    Icon(AppIcons.ArrowUpward, "Create video", Modifier.size(20.dp))
                }
            }
            if (durations.size > 1) Column {
                Text("Length · $seconds s", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
                val index = durations.indexOf(seconds).coerceAtLeast(0)
                Slider(index.toFloat(), { onSeconds(durations[it.toInt().coerceIn(0, durations.lastIndex)]) },
                    valueRange = 0f..durations.lastIndex.toFloat(), steps = (durations.size - 2).coerceAtLeast(0))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.videoAspects().forEach { a -> FilterChip(aspect == a, { onAspect(a) }, label = { Text(a) }, colors = choiceChipColors()) }
            }
            if (levels.size > 1) Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speed", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
                    Text(VIDEO_QUALITY_NAMES.getOrElse(quality) { "" }, style = MaterialTheme.typography.labelLarge,
                        color = com.localfirst.assistant.ui.theme.LocalFridayPalette.current.accent, modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text("Quality", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
                }
                Slider(quality.toFloat(), { onQuality(it.toInt()) }, valueRange = 0f..(levels.size - 1).toFloat(), steps = (levels.size - 2).coerceAtLeast(0))
            }
            if (level != null) Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(level.summary(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(level.chatNote(), style = MaterialTheme.typography.labelMedium,
                    color = if (level.pausesChat) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("No size fits $seconds s. Choose a shorter length.", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp))
            // Inputs the computer has switched on (start/end frames and so on); none appear until it supports them.
            val inputKinds = options.videoInputs()
            if (inputKinds.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                inputKinds.forEach { kind ->
                    val chosen = inputs[kind]
                    InputChip(chosen != null, { if (chosen != null) onRemoveInput(kind) else onPickInput(kind) },
                        label = { Text(kind.replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                        leadingIcon = if (chosen != null) {
                            { ImageThumb("/workspace/files/$chosen", kind, Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))) }
                        } else { { Icon(LineIcons.Add, null, Modifier.size(16.dp)) } })
                }
            }
            TextButton({ more = !more }) { Text(if (more) "Fewer options" else "More options") }
            if (more) OutlinedTextField(seed, { onSeed(it.filter(Char::isDigit).take(10)) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Seed (blank = random)") }, isError = !seedValid)
        }
    }
}

/** One square in the gallery: the video's poster frame, or its progress, or why it failed. */
@Composable
internal fun VideoTile(job: JsonObject, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        when {
            job.flag("poster") -> {
                ImageThumb("/workspace/videos/${job.string("id")}/poster", job.string("prompt").take(120), Modifier.fillMaxSize())
                Surface(shape = CircleShape, color = Color.Black.copy(alpha = .55f), modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("▶", color = Color.White) }
                }
                job["seconds"]?.jsonPrimitive?.doubleOrNull?.let { s ->
                    Surface(shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = .55f), modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                        Text("${kotlin.math.round(s).toInt()} s", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            !job.finished() -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(12.dp)) {
                val progress = job.progressFraction()
                if (progress != null) CircularProgressIndicator(progress = { progress }, Modifier.size(32.dp)) else CircularProgressIndicator(Modifier.size(32.dp))
                Text(job.videoStatus(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
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

/** Over the gallery: the video playing, its prompt and settings, and what to do with it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoViewer(job: JsonObject, busy: Boolean, onClose: () -> Unit, onSave: (String) -> Unit, onReuse: () -> Unit,
                         onCancel: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val artifact = job.artifactId()
    val plan = job["plan"] as? JsonObject
    val request = job["request"] as? JsonObject
    com.localfirst.assistant.ui.PlatformBackHandler(onBack = onClose)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().focusRequester(focus).focusable()
        .onKeyEvent { if (it.key == androidx.compose.ui.input.key.Key.Escape) { onClose(); true } else false }
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0D0D0F), contentColor = Color.White) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Video", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClose) { Icon(LineIcons.Close, "Close", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (artifact != null && !confirmDelete) {
                        val loader = LocalFeatureVideos.current
                        val url by produceState<String?>(null, artifact, loader) { value = runCatching { loader("/workspace/files/$artifact") }.getOrNull() }
                        url?.let { VideoSurface(it, Modifier.fillMaxSize()) } ?: CircularProgressIndicator(Modifier.size(28.dp))
                    } else if (artifact == null) VideoTile(job, Modifier.widthIn(max = 280.dp).fillMaxWidth()) {}
                }
                Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectionContainer { Text(job.string("prompt"), style = MaterialTheme.typography.bodyMedium) }
                    val details = listOfNotNull(
                        plan?.let { "${it["width"]?.jsonPrimitive?.intOrNull}×${it["height"]?.jsonPrimitive?.intOrNull}" },
                        (job["seconds"]?.jsonPrimitive?.doubleOrNull ?: request?.get("duration_seconds")?.jsonPrimitive?.doubleOrNull)?.let { "${kotlin.math.round(it).toInt()} s" },
                        request?.get("seed")?.jsonPrimitive?.longOrNull?.let { "seed $it" },
                        plan?.let { if (it["pauses_chat"]?.jsonPrimitive?.booleanOrNull == true) "both GPUs" else "one GPU" },
                    )
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .7f))
                    job.string("error").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (artifact != null) Button({ onSave(artifact) }) { Text("Save") }
                    OutlinedButton(onReuse) { Text("Reuse prompt") }
                    if (!job.finished()) OutlinedButton(onCancel, enabled = !busy) { Text("Cancel") }
                    else TextButton({ confirmDelete = true }, enabled = !busy) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { FridayDialogWindow(); Text("Delete this video?") },
        text = { Text("It's removed from your videos. The file stays in Library until you delete it there.") },
        confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}
