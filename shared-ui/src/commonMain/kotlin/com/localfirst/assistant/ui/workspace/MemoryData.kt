package com.localfirst.assistant.ui.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.client.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
/** Archive rows store exclusion as 0/1; a toggled preview carries a boolean. */
internal fun JsonObject.excluded() = (this["excluded"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.intOrNull == 1) } == true

/** Ways into memory import, review and investigation, shown on both clients' Memory screen. */
@Composable
fun MemoryTools(vm: WorkspaceActions, state: WorkspaceState, onImport: () -> Unit, onReview: () -> Unit, onArchive: () -> Unit, onContext: () -> Unit) {
    Button(onImport, enabled = !state.busy) { Text("Import ChatGPT export") }
    OutlinedButton(onReview) { Text("Review suggestions · ${state.memorySummary.number("suggestions").takeIf { it > 0 } ?: state.suggestions.size}") }
    Row { TextButton(onArchive) { Text("Browse archive") }; TextButton(onContext) { Text("What influenced the last reply?") } }
    if (state.situations.isNotEmpty()) {
        Text("Recent situations", style = MaterialTheme.typography.titleMedium)
        for (situation in state.situations) {
            val id = situation.text("id")
            Text(situation.text("topic"), style = MaterialTheme.typography.titleSmall)
            Text(situation.text("summary"))
            Row {
                if (situation.text("status") == "open") TextButton({ vm.resolveSituation(id) }, enabled = !state.busy) { Text("Mark resolved") }
                TextButton({ vm.forgetSituation(id) }, enabled = !state.busy) { Text("Forget") }
            }
        }
    }
}

/** Stages a ChatGPT export on the computer, previews it, then imports it after confirmation. */
@Composable
fun ChatGptImportScreen(vm: WorkspaceActions, state: WorkspaceState, onChoose: () -> Unit, platformNote: String) {
    var extract by remember { mutableStateOf(true) }
    val preview = state.importPreview
    DataPage("Bring your history with you") {
        Text(platformNote)
        Text("Full chat text stays in the computer's archive. FRIDAY can recall relevant excerpts and propose lasting memories with supporting quotes from you.")
        Notices(state)
        if (preview == null) Button(onChoose, enabled = !state.busy) { Text(if (state.busy) "Uploading…" else "Choose ChatGPT export") }
        else {
            Text("${preview.number("conversations")} readable chats · ${preview.number("new")} new · ${preview.number("duplicates")} existing · ${preview.number("skipped")} skipped", style = MaterialTheme.typography.titleMedium)
            for (title in preview["titles"]?.jsonArray.orEmpty()) Text("• ${title.jsonPrimitive.content}")
            for (warning in preview["warnings"]?.jsonArray.orEmpty()) Text(warning.jsonPrimitive.content, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(extract, { extract = it }); Text("Build memory suggestions for review", Modifier.weight(1f)) }
            Text("Nothing is a saved fact until you approve it. Chats imported before are kept; importing again does not overwrite them.", style = MaterialTheme.typography.bodySmall)
            Button({ vm.commitImport(extract) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Import on computer") }
            TextButton({ vm.discardImport() }, enabled = !state.busy) { Text("Discard preview") }
        }
        Text("Upload limit: 256 MB; readable conversation JSON: 512 MB. Photos, files, system prompts and tool execution are excluded. If the export's structure differs, the computer reports that before importing.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Proposed memories, each checked against the quote it came from before it is remembered. */
@Composable
fun MemoryReviewScreen(vm: WorkspaceActions, state: WorkspaceState) {
    LaunchedEffect(Unit) { while (true) { vm.refreshMemoryReview(); delay(8000) } }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var text by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Review before remembering", style = MaterialTheme.typography.headlineMedium)
            Text("FRIDAY proposes stable facts from your messages. Check each one against its original quote.")
            Text("${state.memorySummary.number("extracting")} chats waiting or processing · ${state.memorySummary.number("extraction_errors")} need retry", style = MaterialTheme.typography.bodySmall)
            Notices(state)
        }
        if (state.suggestions.isEmpty()) item { Text("No pending suggestions. Imported chats are processed in the background; you can also request suggestions from an archived chat.") }
        items(state.suggestions, key = { it.text("id") }) { suggestion ->
            val id = suggestion.text("id")
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(suggestion.text("text"), style = MaterialTheme.typography.bodyLarge)
                    Text(suggestion.text("source_title"), style = MaterialTheme.typography.labelLarge)
                    Text("“${suggestion.text("quote")}”", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton({ vm.reviewSuggestion(id, true) }, enabled = !state.busy) { Text("Remember") }
                        TextButton({ editing = suggestion; text = suggestion.text("text") }, enabled = !state.busy) { Text("Edit") }
                        TextButton({ vm.reviewSuggestion(id, false) }, enabled = !state.busy) { Text("Dismiss") }
                    }
                }
            }
        }
    }
    editing?.let { suggestion -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("Correct this memory") },
        text = { OutlinedTextField(text, { text = it.take(2000) }, minLines = 3) },
        confirmButton = { TextButton({ vm.reviewSuggestion(suggestion.text("id"), true, text); editing = null }, enabled = text.isNotBlank()) { Text("Remember") } },
        dismissButton = { TextButton({ editing = null }) { Text("Cancel") } }) }
}

/** Searches past chats in the computer's archive, including imported ones. */
@Composable
fun MemoryArchiveScreen(vm: WorkspaceActions, state: WorkspaceState, onSource: () -> Unit) {
    var query by remember { mutableStateOf(state.archiveQuery) }
    LaunchedEffect(query) { if (query != state.archiveQuery) { delay(350); while (vm.searchArchive(query) == null && vm.state.value.busy) delay(250) } }
    LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Conversation archive", style = MaterialTheme.typography.headlineMedium)
            Text("Every saved and imported chat FRIDAY can recall from. Open one to see exactly what it holds, keep it out of recall, or delete it.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it.take(2000) }, label = { Text("Search past chats") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        items(state.archiveSources, key = { it.text("id") }) { source ->
            TextButton({ vm.source(source.text("id")); onSource() }, enabled = !state.busy) {
                Column(Modifier.fillMaxWidth()) {
                    Text(source.text("title"))
                    Text(source.text("origin") + if (source.excluded()) " · excluded from recall" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.archiveSources.isEmpty()) item { Text(if (query.isBlank()) "No archived chats yet." else "No archived chats match.") }
        if (state.archiveSources.size >= 50 && state.archiveSources.size % 50 == 0) item { OutlinedButton({ vm.moreSources() }, enabled = !state.busy) { Text("More") } }
    }
}

/** One archived chat: what recall can see, with controls to exclude, continue, extract or delete it. */
@Composable
fun MemorySourceScreen(vm: WorkspaceActions, state: WorkspaceState, onContinue: () -> Unit, onDeleted: () -> Unit) {
    val source = state.sourcePreview
    var deleting by remember { mutableStateOf(false) }
    if (source == null) { Text(if (state.busy) "Loading source…" else "Choose a chat in the archive.", Modifier.padding(24.dp)); return }
    val id = source.text("id")
    LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(source.text("title"), style = MaterialTheme.typography.headlineSmall)
            Text("The full chat stays on the computer. This preview and continuing use up to 20 recent messages, 2,000 characters each.", style = MaterialTheme.typography.bodySmall)
            Notices(state)
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Include in future recall", Modifier.weight(1f)); Switch(!source.excluded(), { vm.excludeSource(id, !it) }, enabled = !state.busy) } }
        item { Button({ vm.continueSource()?.invokeOnCompletion { if (vm.state.value.error == null) onContinue() } }, enabled = !state.busy && !state.incognito) { Text("Continue with recent messages") } }
        item { TextButton({ vm.extractSource(id) }, enabled = !state.busy) { Text("Build memory suggestions") } }
        items(source["messages"]?.jsonArray.orEmpty().map { it.jsonObject }) { message ->
            Text(message.text("role"), style = MaterialTheme.typography.labelLarge); Text(message.text("content")); HorizontalDivider()
        }
        if ((source["truncated"] as? JsonPrimitive)?.booleanOrNull == true) item { Text("This preview shows a bounded excerpt.", color = MaterialTheme.colorScheme.secondary) }
        item { TextButton({ deleting = true }, enabled = !state.busy) { Text("Delete from archive") } }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete archived chat?") },
        text = { Text("Deletes this archive source and its pending suggestions. Approved memories are managed separately.") },
        confirmButton = { TextButton({ deleting = false; vm.deleteSource(id)?.invokeOnCompletion { if (vm.state.value.error == null) onDeleted() } }) { Text("Delete") } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } })
}

/** The memories and past chats supplied to the last reply. */
@Composable
fun MemoryContextScreen(state: WorkspaceState) {
    LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Context supplied to the last reply", style = MaterialTheme.typography.headlineSmall)
            Text(state.recallStatus ?: "No reply has used shared memory in this session.")
            Text("These are the sources supplied, not proof the model relied on every one.", style = MaterialTheme.typography.bodySmall)
        }
        items(state.recall.orEmpty()) { source ->
            Text(source.text("title"), style = MaterialTheme.typography.titleMedium)
            Text(source.text("kind"), style = MaterialTheme.typography.labelSmall)
            Text(source.text("excerpt")); HorizontalDivider()
        }
    }
}

/** Exports and backups: the portable format both clients restore. */
@Composable
fun DataControlsScreen(
    state: WorkspaceState,
    onExportChat: ((String) -> Unit)?,
    onExportToLibrary: (() -> Unit)?,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onImportChatGpt: () -> Unit,
    extras: @Composable ColumnScope.() -> Unit = {},
) {
    DataPage("Data controls") {
        Notices(state)
        extras()
        Text("Export and restore", style = MaterialTheme.typography.titleMedium)
        if (onExportChat != null) {
            Text("Current chat", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onExportChat("md") }, enabled = !state.busy) { Text("Markdown") }
                OutlinedButton({ onExportChat("json") }, enabled = !state.busy) { Text("JSON") }
                onExportToLibrary?.let { OutlinedButton(it, enabled = !state.busy) { Text("Save to Library") } }
            }
        }
        DataRow("Export chats and files", "A portable backup of every chat, its projects and attachment images, without server settings or credentials.", "Export backup", state.busy, onBackup)
        DataRow("Restore a backup", "Import a backup from either client. Chats are restored as new copies; nothing is overwritten.", "Restore", state.busy, onRestore)
        DataRow("Import ChatGPT export", "History and memory suggestions stored on the computer.", "Import", state.busy, onImportChatGpt)
    }
}

@Composable private fun DataRow(title: String, body: String, action: String, busy: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedButton(onClick, enabled = !busy) { Text(action) }
        }
    }
}

@Composable private fun Notices(state: WorkspaceState) {
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

@Composable private fun DataPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium); content()
    }
}
