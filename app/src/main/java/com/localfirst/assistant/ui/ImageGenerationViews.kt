package com.localfirst.assistant.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.workspace.ImageSettings
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

@Composable
internal fun GeneratedImage(result: String, loader: suspend (String) -> File, open: (String) -> Unit) {
    val artifact = remember(result) { runCatching { JSONObject(result).getJSONObject("artifact") }.getOrNull() } ?: return
    val id = artifact.optString("id")
    var path by remember(id) { mutableStateOf<String?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var full by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) { runCatching { loader(id).absolutePath }.onSuccess { path = it; error = null }.onFailure { error = it.message } }
    val image = rememberImage(path, 1024)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (image != null) Image(image, "Generated image", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).clickable { full = true })
        else if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text("Preview unavailable: $it", style = MaterialTheme.typography.bodySmall); TextButton(onClick = { attempt++ }) { Text("Retry preview") } }
        TextButton(onClick = { open("assistant://artifact/$id") }) { Text("Open or save image") }
    }
    if (full && path != null) ImageViewer(path!!) { full = false }
}

@Composable
internal fun ImageJobs(state: ChatUiState, vm: ChatViewModel) {
    LaunchedEffect(Unit) {
        while (true) { vm.refreshImages(); delay(2000) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("Images run on your computer. The chat model is restored before each result is marked complete.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.imageJobs.isEmpty()) item { Text("No generated images yet.") }
        items(state.imageJobs, key = { it.optString("id") }) { job ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(job.optString("prompt"), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(job.optString("phase").replace('_', ' '), style = MaterialTheme.typography.labelMedium)
                if (job.optString("status") == "completed") GeneratedImage(job.toString(), vm::loadImage, vm::openArtifact)
                job.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (job.optString("status") !in listOf("completed", "failed", "cancelled")) TextButton(onClick = { vm.cancelImage(job.optString("id")) }) { Text("Cancel") }
                else TextButton(onClick = { vm.removeImageJob(job.optString("id")) }) { Text("Remove from image history") }
                HorizontalDivider()
            }
        }
    }
}

@Composable
internal fun ImageOptions(state: ChatUiState, vm: ChatViewModel, onDone: () -> Unit) {
    val saved = state.imageSettings
    var automatic by rememberSaveable { mutableStateOf(saved.automatic) }
    var width by rememberSaveable { mutableStateOf(saved.width.toString()) }
    var height by rememberSaveable { mutableStateOf(saved.height.toString()) }
    var steps by rememberSaveable { mutableIntStateOf(saved.steps) }
    var seed by rememberSaveable { mutableStateOf(saved.seed?.toString().orEmpty()) }
    var transparent by rememberSaveable { mutableStateOf(saved.transparent) }
    var refs by rememberSaveable { mutableStateOf(saved.references) }
    var error by remember { mutableStateOf<String?>(null) }
    fun options(): ImageSettings? {
        val s = ImageSettings(automatic, width.toIntOrNull() ?: 0, height.toIntOrNull() ?: 0, steps, seed.takeIf { it.isNotBlank() }?.toIntOrNull(), transparent, refs)
        error = if (!automatic && seed.isNotBlank() && seed.toIntOrNull() == null) "Enter a valid integer seed, or leave it blank." else s.validate()
        return s.takeIf { error == null }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Image settings", style = MaterialTheme.typography.titleLarge)
        Text("Describe your image in the message box. These choices apply when you send it.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Let the assistant choose settings", Modifier.weight(1f)); Switch(automatic, { automatic = it }) }
        Text(if (automatic) "The assistant chooses size, aspect ratio, steps and references from your request. Selected references below are always used." else "These settings override the assistant’s choices.", style = MaterialTheme.typography.bodySmall)
        Text("Aspect ratio", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1:1" to (768 to 768), "16:9" to (1024 to 576), "9:16" to (576 to 1024)).forEach { (label, size) ->
                FilterChip(selected = !automatic && width == size.first.toString() && height == size.second.toString(), onClick = { automatic = false; width = size.first.toString(); height = size.second.toString() }, label = { Text(label) })
            }
        }
        if (!automatic) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(width, { width = it.take(4) }, label = { Text("Width") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(height, { height = it.take(4) }, label = { Text("Height") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            Text("Maximum 2000×2000. Dimensions use multiples of 16. Larger images can hit the time limit.", style = MaterialTheme.typography.bodySmall)
            Text("Steps: $steps", style = MaterialTheme.typography.labelLarge)
            Slider(steps.toFloat(), { steps = it.toInt() }, valueRange = 4f..12f, steps = 7)
            OutlinedTextField(seed, { seed = it.take(10) }, label = { Text("Seed (blank for random)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Transparent background", Modifier.weight(1f)); Switch(transparent, { transparent = it }) }
        }
        Text("Reference images · ${refs.size}/2", style = MaterialTheme.typography.labelLarge)
        Text("Choose uploaded images, or attach a photo in chat and ask the assistant to use it. References are resized to at most 512 pixels.", style = MaterialTheme.typography.bodySmall)
        state.workspaceFiles.filter { it.mime.startsWith("image/") }.forEach { file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(file.id in refs, { checked -> refs = if (checked) refs + file.id else refs - file.id }, enabled = file.id in refs || refs.size < 2)
                Text(file.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { options()?.let { vm.saveImageSettings(it); onDone() } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        Text("Default: 768×768, 8 steps. Rendering stops after 150 seconds and the chat model reloads. No image request can exceed the server limits.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
