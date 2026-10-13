package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.client.ImageSettings

/** Image mode's settings: let FRIDAY choose, or fix the size, steps, seed and reference images. [images] are (file id, name). */
@Composable
fun ImageOptions(saved: ImageSettings, images: List<Pair<String, String>>, busy: Boolean, onDone: (ImageSettings) -> Unit, modifier: Modifier = Modifier) {
    var automatic by remember { mutableStateOf(saved.automatic) }
    var width by remember { mutableStateOf(saved.width.toString()) }
    var height by remember { mutableStateOf(saved.height.toString()) }
    var steps by remember { mutableIntStateOf(saved.steps) }
    var seed by remember { mutableStateOf(saved.seed?.toString().orEmpty()) }
    var transparent by remember { mutableStateOf(saved.transparent) }
    var refs by remember { mutableStateOf(saved.references) }
    var error by remember { mutableStateOf<String?>(null) }
    fun options(): ImageSettings? {
        val s = ImageSettings(automatic, width.toIntOrNull() ?: 0, height.toIntOrNull() ?: 0, steps, seed.takeIf { it.isNotBlank() }?.toIntOrNull(), transparent, refs)
        error = if (!automatic && seed.isNotBlank() && seed.toIntOrNull() == null) "Enter a valid integer seed, or leave it blank." else s.validate()
        return s.takeIf { error == null }
    }
    Column(modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Image settings", style = MaterialTheme.typography.titleLarge)
        Text("Describe your image in the message box. These choices apply when you send it.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Let the assistant choose settings", Modifier.weight(1f)); Switch(automatic, { automatic = it }) }
        Text(if (automatic) "The assistant chooses size, aspect ratio, steps and references from your request. Selected references below are always used." else "These settings override the assistant’s choices.", style = MaterialTheme.typography.bodySmall)
        Text("Aspect ratio", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1:1" to (768 to 768), "16:9" to (1024 to 576), "9:16" to (576 to 1024)).forEach { (label, size) ->
                FilterChip(shape = MaterialTheme.shapes.small, selected = !automatic && width == size.first.toString() && height == size.second.toString(),
                    onClick = { automatic = false; width = size.first.toString(); height = size.second.toString() }, label = { Text(label) })
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
        images.forEach { (id, name) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(id in refs, { checked -> refs = if (checked) refs + id else refs - id }, enabled = id in refs || refs.size < 2)
                Text(name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(shape = MaterialTheme.shapes.small, onClick = { options()?.let(onDone) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        Text("Default: 768×768, 8 steps. Rendering stops after 15 minutes and the chat model reloads. No image request can exceed the server limits.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
