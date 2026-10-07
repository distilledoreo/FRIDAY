package com.localfirst.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
internal fun MemoryHub(state: ChatUiState, vm: ChatViewModel, onImport: () -> Unit, onReview: () -> Unit, onArchive: () -> Unit, onEdit: (JSONObject) -> Unit, onContext: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val summary = state.memorySummary
    val settings = summary?.optJSONObject("settings")
    LaunchedEffect(query) { delay(350); while (true) { vm.refreshMemory(query); delay(10000) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Memory lives on your PC", style = MaterialTheme.typography.titleMedium)
            Text("The phone keeps settings and small previews. Imported history and the search index stay on the computer.", style = MaterialTheme.typography.bodyMedium)
            summary?.let { Text("${it.optInt("memories")} approved memories · ${it.optInt("sources")} chats · ${it.optLong("storage_bytes") / 1024 / 1024} MB on PC", style = MaterialTheme.typography.bodySmall) }
            Text(if (summary?.optBoolean("semantic") == true) "Meaning-based recall and text search" else "Text search · semantic encoder unavailable", style = MaterialTheme.typography.bodySmall)
            summary?.optInt("indexing")?.takeIf { it > 0 }?.let { Text("Indexing $it conversation excerpts…", style = MaterialTheme.typography.bodySmall) }
        }
        item { Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import ChatGPT export") } }
        item { OutlinedButton(onClick = onReview, modifier = Modifier.fillMaxWidth()) { Text("Review suggestions · ${summary?.optInt("suggestions") ?: 0}") } }
        item { TextButton(onClick = onArchive) { Text("Browse PC conversation archive") } }
        item { TextButton(onClick = onContext) { Text("What influenced the last reply?") } }
        item { TextButton(onClick = vm::indexExistingChats, enabled = !state.workspaceBusy && !state.busy) { Text("Index existing phone chats on PC") } }
        item { HorizontalDivider(); Text("Recall controls", style = MaterialTheme.typography.titleMedium) }
        items(listOf("use_memories" to "Use approved memories", "use_history" to "Recall past conversations", "archive_chats" to "Archive new chats on PC", "suggest_from_chats" to "Suggest memories from new chats", "track_situations" to "Track recent ongoing situations", "allow_checkins" to "Allow occasional relevant check-ins")) { (key, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(settings?.optBoolean(key, key != "suggest_from_chats") ?: (key != "suggest_from_chats"), { vm.setMemorySetting(key, it) }, enabled = settings != null && !state.workspaceBusy && !state.busy) }
        }
        item { Text("Suggestions need review before becoming facts. Forgetting a source-linked memory also excludes its original chat from recall, so it won’t immediately return.", style = MaterialTheme.typography.bodySmall) }
        item { HorizontalDivider(); Text("Recent situations", style = MaterialTheme.typography.titleMedium); Text("Tentative summaries from recent chats, separate from lasting facts. Open situations expire after 30 days; resolved ones after 7. Check-in offers have a three-day cooldown.", style = MaterialTheme.typography.bodySmall) }
        if (state.memorySituations.isEmpty()) item { Text("No active situations yet.") }
        items(state.memorySituations, key = { it.getString("id") }) { situation ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(situation.getString("topic"), style = MaterialTheme.typography.titleSmall)
                Text(situation.getString("summary"))
                Text(situation.getString("status") + " · " + situation.optString("source_title"), style = MaterialTheme.typography.labelSmall)
                Text("Source: “${situation.optString("quote") }”", style = MaterialTheme.typography.bodySmall)
                Row {
                    if (situation.optString("status") == "open") TextButton(onClick = { vm.resolveSituation(situation.getString("id")) }, enabled = !state.workspaceBusy && !state.busy) { Text("Mark resolved") }
                    TextButton(onClick = { vm.forgetSituation(situation.getString("id")) }, enabled = !state.workspaceBusy && !state.busy) { Text("Forget") }
                }
            }
        }
        item { HorizontalDivider(); OutlinedTextField(query, { query = it.take(2000) }, label = { Text("Search approved memories") }, modifier = Modifier.fillMaxWidth()) }
        if (state.pcMemories.isEmpty()) item { Text("No matching approved memories. Import an export, review suggestions, or add a memory.") }
        items(state.pcMemories, key = { it.getString("id") }) { memory ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(memory.getString("text"), style = MaterialTheme.typography.bodyLarge)
                Text(memory.optString("category") + if (memory.optString("source_title").let { it.isNotBlank() && it != "null" }) " · " + memory.optString("source_title") else "", style = MaterialTheme.typography.labelMedium)
                memory.optString("quote").takeIf { it.isNotBlank() && it != "null" }?.let { Text("Source: “$it”", style = MaterialTheme.typography.bodySmall) }
                Row { TextButton(onClick = { onEdit(memory) }, enabled = !state.workspaceBusy && !state.busy) { Text("Edit") }; TextButton(onClick = { vm.forgetMemory(memory.getString("id")) }, enabled = !state.workspaceBusy && !state.busy) { Text("Forget") } }
                HorizontalDivider()
            }
        }
        if (state.pcMemoriesMore) item(key = "more-memories") {
            // Composed only when scrolled into view, so reaching the end of the list loads the next page.
            LaunchedEffect(state.pcMemories.size) { vm.loadMoreMemories() }
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        }
        item { Text("Phone chat backups cover local chats and projects; the PC memory database is separate.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun ChatGptImport(state: ChatUiState, vm: ChatViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::stageChatGptImport) }
    var extract by rememberSaveable { mutableStateOf(true) }
    val preview = state.importPreview
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Bring your history with you", style = MaterialTheme.typography.titleLarge)
        Text("Choose your ChatGPT export ZIP or conversations.json. It streams straight to your PC, with no export copy saved on the phone.")
        Text("Full chat text stays in the PC archive. The assistant can recall relevant excerpts and propose lasting memories with supporting user quotes.")
        if (preview == null) Button(onClick = { picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream")) }, enabled = !state.workspaceBusy && !state.busy) { Text("Choose ChatGPT export") }
        else {
            Text("${preview.optInt("conversations")} readable chats · ${preview.optInt("new")} new · ${preview.optInt("duplicates")} existing · ${preview.optInt("skipped")} skipped", style = MaterialTheme.typography.titleMedium)
            preview.optJSONArray("titles")?.let { titles -> for (n in 0 until titles.length()) Text("• ${titles.getString(n)}") }
            preview.optJSONArray("warnings")?.let { warnings -> for (n in 0 until warnings.length()) Text(warnings.getString(n), style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(extract, { extract = it }); Text("Build memory suggestions for review", Modifier.weight(1f)) }
            Text("Nothing is a saved fact until you approve it. Existing imported chats are kept; re-import does not overwrite them.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.commitChatGptImport(extract) }, enabled = !state.workspaceBusy && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Import on PC") }
            TextButton(onClick = vm::discardChatGptImport, enabled = !state.workspaceBusy && !state.busy) { Text("Discard preview") }
        }
        Text("Upload limit: 256 MB; readable conversation JSON: 512 MB. Photos, files, system prompts and tool execution are excluded. If export structure differs, the app reports that before importing.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun MemoryReview(state: ChatUiState, vm: ChatViewModel) {
    LaunchedEffect(Unit) { while (true) { vm.refreshMemory(); delay(8000) } }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Review before remembering", style = MaterialTheme.typography.titleLarge); Text("The assistant proposes stable facts from user messages. Check each one against its original quote.") }
        state.memorySummary?.let { summary ->
            item { Text("${summary.optInt("extracting")} chats waiting or processing · ${summary.optInt("extraction_errors")} need retry", style = MaterialTheme.typography.bodySmall) }
        }
        if (state.memorySuggestions.isEmpty()) item { Text("No pending suggestions. Imported chats are processed in the background; you can also request suggestions from an archived chat.") }
        items(state.memorySuggestions, key = { it.getString("id") }) { suggestion ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(suggestion.getString("text"), style = MaterialTheme.typography.bodyLarge)
                Text(suggestion.optString("source_title"), style = MaterialTheme.typography.labelLarge)
                Text("“${suggestion.getString("quote")}”", style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = { vm.reviewMemory(suggestion.getString("id"), true) }, enabled = !state.workspaceBusy && !state.busy) { Text("Remember") }
                    TextButton(onClick = { editing = suggestion; text = suggestion.getString("text") }, enabled = !state.workspaceBusy && !state.busy) { Text("Edit") }
                    TextButton(onClick = { vm.reviewMemory(suggestion.getString("id"), false) }, enabled = !state.workspaceBusy && !state.busy) { Text("Dismiss") }
                }
                HorizontalDivider()
            }
        }
    }
    editing?.let { suggestion -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("Correct this memory") }, text = { OutlinedTextField(text, { text = it.take(2000) }, minLines = 3) }, confirmButton = { TextButton(onClick = { vm.reviewMemory(suggestion.getString("id"), true, text); editing = null }, enabled = text.isNotBlank()) { Text("Remember") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }) }
}

@Composable
internal fun MemoryArchive(state: ChatUiState, vm: ChatViewModel, onSource: () -> Unit) {
    var query by rememberSaveable { mutableStateOf(state.archiveQuery) }
    LaunchedEffect(query) { delay(350); vm.refreshArchive(query) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Conversation archive on PC", style = MaterialTheme.typography.titleMedium); OutlinedTextField(query, { query = it.take(2000) }, label = { Text("Search past chats") }, modifier = Modifier.fillMaxWidth()) }
        items(state.archiveSources, key = { it.getString("id") }) { source ->
            TextButton(onClick = { vm.openMemorySource(source.getString("id")); onSource() }, enabled = !state.workspaceBusy && !state.busy) { Column(Modifier.fillMaxWidth()) { Text(source.getString("title")); Text(source.optString("origin") + if (source.optBoolean("excluded")) " · excluded from recall" else "", style = MaterialTheme.typography.bodySmall) } }
        }
        item { Row { TextButton(onClick = { vm.refreshArchive(query, (state.archiveOffset - 50).coerceAtLeast(0)) }, enabled = state.archiveOffset > 0) { Text("Previous") }; TextButton(onClick = { vm.refreshArchive(query, state.archiveOffset + 50) }, enabled = state.archiveSources.size == 50) { Text("Next") } } }
    }
}

@Composable
internal fun MemorySource(state: ChatUiState, vm: ChatViewModel) {
    val source = state.archiveSource
    var deleting by remember { mutableStateOf(false) }
    if (source == null) { Text("Loading source…", Modifier.padding(20.dp)); return }
    val id = source.getString("id")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(source.getString("title"), style = MaterialTheme.typography.titleLarge); Text("Full chat stays on PC. This preview and continuation use up to 20 recent messages, 2,000 characters each.", style = MaterialTheme.typography.bodySmall) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Include in future recall", Modifier.weight(1f)); Switch(!source.optBoolean("excluded"), { vm.excludeMemorySource(id, !it) }, enabled = !state.workspaceBusy && !state.busy) } }
        item { Button(onClick = vm::continueMemorySource, enabled = !state.workspaceBusy && !state.busy) { Text("Continue with recent messages") } }
        item { TextButton(onClick = { vm.extractMemorySource(id) }, enabled = !state.workspaceBusy && !state.busy) { Text("Build memory suggestions") } }
        val messages = source.optJSONArray("messages")
        if (messages != null) items(messages.length()) { n -> val message = messages.getJSONObject(n); Text(message.getString("role"), style = MaterialTheme.typography.labelLarge); Text(message.getString("content")); HorizontalDivider() }
        item { TextButton(onClick = { deleting = true }, enabled = !state.workspaceBusy && !state.busy) { Text("Delete from PC archive") } }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete archived chat?") }, text = { Text("Deletes this PC archive source and its pending suggestions. Approved memories are managed separately.") }, confirmButton = { TextButton(onClick = { deleting = false; vm.deleteMemorySource(id) }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

@Composable
internal fun MemoryContext(state: ChatUiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Context supplied to the last reply", style = MaterialTheme.typography.titleLarge); Text(state.recallStatus ?: "No reply has used PC memory in this session."); Text("These are the sources supplied, not proof the model relied on every one.", style = MaterialTheme.typography.bodySmall) }
        items(state.recallSources) { source -> Text(source.optString("title"), style = MaterialTheme.typography.titleMedium); Text(source.optString("kind"), style = MaterialTheme.typography.labelSmall); Text(source.optString("excerpt")); HorizontalDivider() }
    }
}
