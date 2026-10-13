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
        error?.let { Text("Preview unavailable: $it", style = MaterialTheme.typography.bodySmall); TextButton(shape = MaterialTheme.shapes.small, onClick = { attempt++ }) { Text("Retry preview") } }
        TextButton(shape = MaterialTheme.shapes.small, onClick = { open("assistant://artifact/$id") }) { Text("Open or save image") }
    }
    if (full && path != null) ImageViewer(path!!) { full = false }
}

@Composable
internal fun ImageJobs(state: ChatUiState, vm: ChatViewModel) {
    com.localfirst.assistant.ui.workspace.ImagesScreen(vm.features) { vm.openArtifact("assistant://artifact/$it") }
}

@Composable
internal fun ImageOptions(state: ChatUiState, vm: ChatViewModel, onDone: () -> Unit) =
    com.localfirst.assistant.ui.ImageOptions(state.imageSettings, state.workspaceFiles.filter { it.mime.startsWith("image/") }.map { it.id to it.name }, state.busy,
        { vm.saveImageSettings(it); onDone() }, Modifier.imePadding())
