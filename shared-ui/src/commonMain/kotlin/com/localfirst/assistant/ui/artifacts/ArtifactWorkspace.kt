@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.localfirst.assistant.ui.artifacts

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localfirst.assistant.artifacts.*
import com.localfirst.assistant.client.*
import com.localfirst.assistant.genui.*
import com.localfirst.assistant.ui.NativeResponseContent
import com.localfirst.assistant.ui.genui.*
import com.localfirst.assistant.ui.workspace.ServiceImage
import kotlinx.serialization.json.*

@Composable
expect fun RichDocumentEditor(content: JsonObject, session: String, onEvent: (String) -> Unit, close: () -> Unit)
@Composable
expect fun ArtifactDraftGuard(dirty: Boolean)

/** A wide-screen companion to chat, with the same guarded full-screen workspace on phones. */
@Composable
fun ArtifactWorkspaceHost(vm: ArtifactController, visible: Boolean, download: (String) -> Unit, close: () -> Unit, content: @Composable () -> Unit) {
    var fraction by remember { mutableStateOf(.48f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (visible && maxWidth >= 1100.dp) {
            val paneWidth = (maxWidth * fraction).coerceIn(440.dp, maxWidth - 420.dp)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                VerticalDivider()
                Column(Modifier.width(paneWidth).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Workspace width", style = MaterialTheme.typography.labelMedium)
                        Slider(fraction, { fraction = it }, valueRange = .35f.. .65f, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    }
                    Box(Modifier.weight(1f)) { ArtifactWorkspace(vm, download, close, embedded = true) }
                }
            }
        } else {
            content()
            if (visible) ArtifactWorkspace(vm, download, close)
        }
    }
}

/** Shared workspace chrome, history, suggestions and specialized editors for Android and browser. */
@Composable
fun ArtifactWorkspace(vm: ArtifactController, download: (String) -> Unit, close: () -> Unit) = ArtifactWorkspace(vm, download, close, embedded = false)

@Composable
private fun ArtifactWorkspace(vm: ArtifactController, download: (String) -> Unit, close: () -> Unit, embedded: Boolean) {
    val s by vm.state.collectAsState()
    var rich by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("Edit") }
    var instruction by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    var purging by remember { mutableStateOf(false) }
    var purgeTitle by remember { mutableStateOf("") }
    var reset by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var projectFilter by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refresh() }
    ArtifactDraftGuard(s.dirty || s.appDirty)
    val workspace: @Composable () -> Unit = {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (s.selected == null) "Artifacts" else s.title, style = MaterialTheme.typography.titleLarge)
                    TextButton({ if (s.dirty || s.appDirty || s.busy) discard = true else if (s.selected != null) vm.back() else close() }) { Text(if (s.selected == null) "Close" else "All artifacts") }
                    TextButton({ vm.backup(download) }, enabled = !s.busy && !s.dirty) { Text("Back up artifacts") }
                }
                if (s.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(vm::cancel) { Text("Stop waiting") } }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(vm::dismissError) { Text("Dismiss error") } }
                s.notice?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (s.transitioning) { Text("Loading artifact…") } else if (s.selected == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("document", "app", "slides", "sheet").forEach { kind -> Button({ vm.create(kind) }, enabled = !s.busy) { Text("New $kind") } }
                        FilterChip(s.deleted, { vm.refresh(!s.deleted) }, label = { Text("Trash") })
                        TextButton({ vm.refresh() }, enabled = !s.busy) { Text("Refresh") }
                    }
                    FlowRow { FilterChip(projectFilter.isEmpty(), { projectFilter = "" }, label = { Text("All projects") }); s.projects.forEach { project -> FilterChip(projectFilter == project.string("id"), { projectFilter = project.string("id") }, label = { Text(project.string("name")) }) } }
                    OutlinedTextField(search, { search = it }, label = { Text("Find an artifact") }, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.weight(1f)) {
                        items(s.items.filter { it.string("title").contains(search, true) && (projectFilter.isEmpty() || it.string("project") == projectFilter) }, key = { it.string("id") }) { item ->
                            ListItem(headlineContent = { Text(item.string("title")) }, supportingContent = { Text("${item.string("kind")} · revision ${item["revision"]}${if(item["template"]?.jsonPrimitive?.intOrNull == 1) " · template" else ""}") },
                                modifier = Modifier.clickable(enabled = !s.busy) { vm.open(item.string("id")) }, trailingContent = {
                                    if (item["template"]?.jsonPrimitive?.intOrNull == 1) TextButton({ vm.create(item.string("kind"), item) }, enabled = !s.busy) { Text("Use template") }
                                })
                        }
                        if (s.items.isEmpty()) item { Text("Saved documents, apps, slides and sheets appear here. Import files from Library or save an answer from chat.") }
                    }
                } else {
                    val deleted = s.selected!!["deleted"]?.jsonPrimitive?.intOrNull == 1
                    val kind = s.selected!!.string("kind")
                    if (deleted) {
                        Text("This artifact is in Trash. Its revisions and attachments are retained.")
                        Button({ vm.trash(false) }, enabled = !s.busy) { Text("Restore artifact") }
                        TextButton({ purging = true; purgeTitle = "" }, enabled = !s.busy) { Text("Delete permanently") }
                    } else {
                        s.selected!!.string("warnings").takeIf { it.isNotBlank() }?.let { value ->
                            val warnings = runCatching { wireJson.parseToJsonElement(value).jsonArray }.getOrNull()
                            warnings?.forEach { Text(it.jsonPrimitive.content, style = MaterialTheme.typography.bodySmall) }
                        }
                        FlowRow {
                            FilterChip(s.selected!!.string("project").isEmpty(), { vm.project("") }, enabled = !s.busy, label = { Text("No project") })
                            s.projects.forEach { project -> FilterChip(s.selected!!.string("project") == project.string("id"), { vm.project(project.string("id")) }, enabled = !s.busy, label = { Text(project.string("name")) }) }
                        }
                        s.selected!!.string("source").takeIf { it.startsWith("file:") }?.let { source -> TextButton({ download(source.removePrefix("file:")) }) { Text("Download original import") } }
                        OutlinedTextField(s.title, { vm.edit(title = it) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (s.dirty || s.appDirty) "Unsaved changes" else "Saved · revision ${s.revision}", Modifier.padding(8.dp))
                            TextButton({ vm.save() }, enabled = !s.busy && (s.dirty || s.appDirty)) { Text("Save") }
                            TextButton({ vm.saveCopy() }, enabled = !s.busy) { Text("Save copy") }
                            TextButton({ vm.template() }, enabled = !s.busy) { Text("Make template") }
                            TextButton({ discard = true }, enabled = !s.busy) { Text("Reload") }
                            TextButton({ vm.trash(true) }, enabled = !s.busy) { Text("Move to trash") }
                        }
                        ScrollableTabRow(listOf("Edit", "Suggestions", "Comments", "History", "Export").indexOf(tab)) {
                            listOf("Edit", "Suggestions", "Comments", "History", "Export").forEach { name -> Tab(tab == name, { tab = name }, text = { Text(name) }) }
                        }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                            when (tab) {
                                "Edit" -> {
                                    when(kind) {
                                        "document" -> {
                                            item { Button({ rich = true }) { Text("Open rich editor") }; Text("Format text, edit tables, insert images, select a passage and return here to request an AI edit.") }
                                            item { OutlinedTextField(instruction, { instruction = it }, label = { Text("Ask FRIDAY to edit the selected text") }, modifier = Modifier.fillMaxWidth()); Button({ vm.suggest(instruction); tab = "Suggestions" }, enabled = !s.busy && instruction.isNotBlank() && s.selection?.string("text")?.isNotBlank() == true) { Text("Suggest edit") } }
                                            s.selection?.string("text")?.takeIf { it.isNotBlank() }?.let { quote -> item { Text("Selected: $quote", style = MaterialTheme.typography.bodySmall) } }
                                            item { val headings = s.content!!["blocks"]!!.rows().filter { it.string("type") == "heading" }; if(headings.isNotEmpty()) Text("Outline\n" + headings.joinToString("\n") { it.string("text") }) }
                                            items(s.content!!["blocks"]!!.rows(), key = { it.string("id") }) { block ->
                                                when (block.string("type")) {
                                                    "image" -> ServiceImage("/workspace/files/${block.string("file")}", block.string("text"))
                                                    "table" -> Column(Modifier.horizontalScroll(rememberScrollState())) { block["rows"]!!.jsonArray.forEach { row -> Text(row.jsonArray.joinToString("  |  ") { it.jsonPrimitive.content }) } }
                                                    else -> Text(block.string("text"), style = if (block.string("type") == "heading") MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
                                                }
                                            }
                                        }
                                        "app" -> item { AppEditor(vm, s, onReset = { reset = true }) }
                                        "slides" -> item { SlideEditor(s.content!!, { vm.edit(it) }) }
                                        "sheet" -> item { SheetEditor(s.content!!, s.calculation, { vm.edit(it) }, { vm.calculate() }, !s.busy) }
                                    }
                                }
                                "Suggestions" -> {
                                    item { Text("Review before applying. Accepting creates a revision; History can undo it. Suggestions for an older revision must be regenerated."); TextButton({ vm.open(s.selected!!.string("id")) }, enabled = !s.busy && !s.dirty) { Text("Refresh suggestions") } }
                                    if (s.proposals.isEmpty()) item { Text("No suggestions yet. Select text in the rich editor, then ask FRIDAY for an edit.") }
                                    items(s.proposals, key = { it.string("id") }) { proposal ->
                                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                            Text(proposal.string("summary"), style = MaterialTheme.typography.titleMedium)
                                            Text("${proposal.string("status")} · based on revision ${proposal["base"]}")
                                            proposal["operations"]!!.rows().forEach { op ->
                                                if (op.string("type") == "replace_text") { Text("Before\n"+op.string("expected"), color = MaterialTheme.colorScheme.error); Text("After\n"+op.string("text")) }
                                                else { Text("Proposed document/source changes"); Text(op["content"].toString(), style = MaterialTheme.typography.bodySmall) }
                                            }
                                            if (proposal.string("status") == "pending") Row {
                                                TextButton({ vm.decide(proposal.string("id"), true) }, enabled = !s.busy && proposal["base"]?.jsonPrimitive?.int == s.revision && !s.dirty) { Text("Accept") }
                                                TextButton({ vm.decide(proposal.string("id"), false) }, enabled = !s.busy) { Text("Reject") }
                                            }
                                        } }
                                    }
                                }
                                "Comments" -> {
                                    item { OutlinedTextField(comment, { comment = it }, label = { Text("Comment on the selection or document") }, modifier = Modifier.fillMaxWidth()); Button({ vm.comment(comment); comment = "" }, enabled = !s.busy && comment.isNotBlank()) { Text("Add comment") } }
                                    items(s.comments, key = { it.string("id") }) { c -> Card { Column(Modifier.padding(12.dp)) {
                                        Text("Revision ${c["revision"]} · ${if(c["resolved"]?.jsonPrimitive?.int == 1) "Resolved" else "Open"}")
                                        if (c.string("quote").isNotBlank()) Text("“${c.string("quote")}”")
                                        Text(c.string("text")); TextButton({ vm.resolve(c.string("id"), c["resolved"]?.jsonPrimitive?.int != 1) }, enabled = !s.busy) { Text(if(c["resolved"]?.jsonPrimitive?.int == 1) "Reopen" else "Resolve") }
                                    } } }
                                }
                                "History" -> items(s.history) { rev -> ListItem(headlineContent = { Text("Revision ${rev["revision"]}: ${rev.string("summary")}") }, trailingContent = { TextButton({ vm.restore(rev["revision"]!!.jsonPrimitive.int) }, enabled = !s.busy && rev["revision"]!!.jsonPrimitive.int != s.revision) { Text("Restore as new revision") } }) }
                                "Export" -> {
                                    item { Text("Exports use the saved revision. PDF/DOCX previews show the converted output; failed previews leave the last successful preview available."); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val formats = when(kind) { "document" -> listOf("md","docx","pdf"); "sheet" -> listOf("xlsx","csv"); "slides" -> listOf("pdf","pptx"); else -> listOf(if(s.content!!.string("format") == "html") "html" else "json") }
                                        formats.forEach { format -> Button({ vm.export(format) }, enabled = !s.busy) { Text(format.uppercase()) } }
                                    } }
                                    s.exported?.let { result -> item {
                                        val file = result["file"]!!.jsonObject
                                        Text("Exported revision ${result["revision"]}")
                                        Row { TextButton({ download(file.string("id")) }) { Text("Download") }; if (file.string("name").substringAfterLast('.') in listOf("pdf","docx","pptx")) TextButton({ vm.preview(file.string("id")) }, enabled = !s.busy) { Text("Preview exported file") } }
                                    } }
                                    s.preview?.let { item { ArtifactPreview(vm, it) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (embedded) workspace() else Dialog(onDismissRequest = { if (s.dirty || s.appDirty || s.busy) discard = true else close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) { workspace() }
    if (rich && s.content != null) RichDocumentEditor(s.content!!, s.editorKey, { message ->
        runCatching {
            val data = wireJson.parseToJsonElement(message).jsonObject
            if (data.string("channel") == s.editorKey && data.string("kind") == "edit") {
                vm.edit(data["content"]!!.jsonObject); vm.selection(data["selection"] as? JsonObject)
            }
        }
    }, { rich = false })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Keep your draft?") }, text = { Text("Save before leaving. Reload discards this device’s unsaved changes and loads the latest saved revision.") },
        confirmButton = { TextButton({ vm.save(); discard = false }, enabled = !s.busy) { Text("Save draft") } }, dismissButton = { Row { TextButton({ discard = false }) { Text("Keep editing") }; TextButton({ vm.reload(); discard = false }, enabled = !s.busy) { Text("Discard and reload") } } })
    if (purging) AlertDialog(onDismissRequest = { purging = false }, title = { Text("Delete this artifact permanently?") }, text = { Column { Text("This removes its source, revisions, comments and app data. Downloaded backups and Library files remain. Type the exact title to confirm: ${s.title}"); OutlinedTextField(purgeTitle, { purgeTitle = it }, label = { Text("Artifact title") }) } }, confirmButton = { TextButton({ vm.purge(purgeTitle); purging = false }, enabled = purgeTitle == s.title && !s.busy) { Text("Delete permanently") } }, dismissButton = { TextButton({ purging = false }) { Text("Cancel") } })
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Reset this app’s data?") }, text = { Text("This resets the current inputs and state. Source and data history are retained; you can restore an earlier data revision.") }, confirmButton = { TextButton({ vm.resetAppData(); reset = false }) { Text("Reset data") } }, dismissButton = { TextButton({ reset = false }) { Text("Cancel") } })
}

@Composable
fun ArtifactPreview(vm: ArtifactController, preview: JsonObject) {
    var note by remember { mutableStateOf("") }
    val state by vm.state.collectAsState()
    Column {
        ServiceImage("/workspace/files/${preview["file"]!!.jsonObject.string("id")}", "Rendered page")
        val page = preview["page"]!!.jsonPrimitive.int; val pages = preview["pages"]!!.jsonPrimitive.int
        Text("Page annotations are saved separately; the original file is preserved.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(note, { note = it }, label = { Text("Note on page ${page+1}") }, modifier = Modifier.fillMaxWidth())
        TextButton({ vm.annotate(preview.string("source"), page, note); note = "" }, enabled = note.isNotBlank() && !state.busy) { Text("Add annotation") }
        state.fileComments.filter { it.string("quote") == "Page ${page+1}" }.forEach { Text(it.string("text")) }

        Row { TextButton({ vm.preview(preview.string("source"), page - 1) }, enabled = page > 0 && !vm.state.value.busy) { Text("Previous") }; Text("Page ${page+1} of $pages"); TextButton({ vm.preview(preview.string("source"), page + 1) }, enabled = page+1 < pages && !vm.state.value.busy) { Text("Next") } }
    }
}

@Composable
private fun SlideEditor(content: JsonObject, change: (JsonObject) -> Unit) {
    var presenting by remember { mutableStateOf(false) }; var index by remember { mutableStateOf(0) }
    val slides = content["slides"]!!.rows()
    fun update(rows: List<JsonObject> = slides, theme: String = content.string("theme")) = change(buildJsonObject { put("version",1); put("theme", theme); put("slides",JsonArray(rows)) })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow { listOf("light","dark","warm").forEach { theme -> FilterChip(content.string("theme") == theme, { update(theme = theme) }, label = { Text(theme) }) }; TextButton({ presenting = true; index = 0 }) { Text("Present") }; TextButton({ update(slides + buildJsonObject { put("title","New slide"); put("body","") }) }) { Text("Add slide") } }
        slides.forEachIndexed { i, slide -> Card { Column(Modifier.padding(12.dp)) {
            Text("Slide ${i+1}")
            fun field(key: String, value: String) = update(slides.mapIndexed { j, old -> if(i != j) old else JsonObject(old + (key to JsonPrimitive(value))) })
            OutlinedTextField(slide.string("title"), { field("title",it) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(slide.string("body"), { field("body",it) }, label = { Text("Slide text") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Row { TextButton({ val next=slides.toMutableList(); next.add(i-1,next.removeAt(i)); update(next) }, enabled=i>0) { Text("Move up") }; TextButton({ val next=slides.toMutableList(); next.add(i+1,next.removeAt(i)); update(next) }, enabled=i<slides.lastIndex) { Text("Move down") }; TextButton({ update(slides.filterIndexed { j,_ -> j != i }) }, enabled=slides.size>1) { Text("Remove") } }
        } } }
    }
    if(presenting) Dialog(onDismissRequest={presenting=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        val dark = content.string("theme") == "dark"
        Surface(Modifier.fillMaxSize(), color = if(dark) androidx.compose.ui.graphics.Color(0xff19191e) else if(content.string("theme")=="warm") androidx.compose.ui.graphics.Color(0xfffff4e6) else androidx.compose.ui.graphics.Color.White,
            contentColor = if(dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black) {
            Column(Modifier.fillMaxSize().padding(36.dp).verticalScroll(rememberScrollState())) {
                Text(slides[index].string("title"),style=MaterialTheme.typography.headlineLarge); Spacer(Modifier.height(24.dp)); Text(slides[index].string("body"),style=MaterialTheme.typography.headlineSmall)
                Row { TextButton({ index-- },enabled=index>0){Text("Previous")}; TextButton({index++},enabled=index<slides.lastIndex){Text("Next")}; TextButton({presenting=false}){Text("Close presentation")} }
            }
        }
    }
}

@Composable
private fun SheetEditor(content: JsonObject, calculation: JsonObject?, change: (JsonObject) -> Unit, calculate: () -> Unit, enabled: Boolean) {
    val rows = content["cells"]!!.jsonArray.map { it.jsonArray.toList() }
    var row by remember { mutableStateOf(0) }; var col by remember { mutableStateOf(0) }; var raw by remember(row,col,content) { mutableStateOf((rows[row][col] as? JsonPrimitive)?.contentOrNull.orEmpty()) }
    var type by remember(row,col,content) { mutableStateOf(if((rows[row][col] as? JsonPrimitive)?.isString != false) "Text / formula" else if((rows[row][col] as? JsonPrimitive)?.booleanOrNull != null) "Boolean" else "Number") }
    var error by remember { mutableStateOf<String?>(null) }
    var paste by remember { mutableStateOf("") }
    fun update(cells: List<List<JsonElement>>) = change(buildJsonObject { put("version",1);put("cells",JsonArray(cells.map(::JsonArray))) })
    Column {
        Text("Bounded sheet: 100 rows, 26 columns, 2,000 cells. Formulas: arithmetic, references and SUM/AVERAGE/MIN/MAX/COUNT. Unsupported formulas show an error.")
        Row { TextButton(calculate, enabled=enabled){Text("Recalculate")}; TextButton({ update(rows + listOf(List(rows[0].size){JsonPrimitive("")})) },enabled=rows.size<100&&(rows.size+1)*rows[0].size<=2000){Text("Add row")}; TextButton({update(rows.map{it+JsonPrimitive("")})},enabled=rows[0].size<26&&rows.size*(rows[0].size+1)<=2000){Text("Add column")} }
        Text("Cell ${('A'.code+col).toChar()}${row+1}")
        OutlinedTextField(raw,{raw=it},modifier=Modifier.fillMaxWidth(),label={Text("Value or formula")})
        FlowRow { listOf("Text / formula","Number","Boolean","Blank").forEach { t -> FilterChip(type==t,{type=t},label={Text(t)}) }; Button({
            val value: JsonElement? = when(type){"Number"->raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive);"Boolean"->raw.toBooleanStrictOrNull()?.let(::JsonPrimitive);"Blank"->JsonNull;else->JsonPrimitive(raw)}
            if(value==null)error="Enter a valid ${type.lowercase()}" else { error=null;update(rows.mapIndexed{r,line->line.mapIndexed{c,v->if(r==row&&c==col)value else v}}) }
        }){Text("Apply cell")} }
        OutlinedTextField(paste, { paste = it }, label = { Text("Paste a tab-separated range at the selected cell") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        TextButton({
            val grid = paste.trimEnd('\n').split('\n').map { it.trimEnd('\r').split('\t') }
            if (grid.size + row > rows.size || grid.any { it.size + col > rows[0].size }) error = "The range does not fit. Add rows or columns first."
            else { update(rows.mapIndexed { r, line -> line.mapIndexed { c, value -> grid.getOrNull(r-row)?.getOrNull(c-col)?.let(::JsonPrimitive) ?: value } }); paste = ""; error = null }
        }, enabled = paste.isNotEmpty()) { Text("Apply range as text / formulas") }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { r,line -> Row { Text("${r+1}",Modifier.width(32.dp));line.forEachIndexed{c,v->
                val display=calculation?.get("cells")?.jsonArray?.getOrNull(r)?.jsonArray?.getOrNull(c) ?: v
                OutlinedButton({row=r;col=c},Modifier.width(130.dp)){Text((display as? JsonPrimitive)?.contentOrNull.orEmpty(),maxLines=3)}
            } } }
        }
    }
}

private fun JsonElement.kotlinValue(): Any? = when(this) { JsonNull -> null; is JsonObject -> mapValues { it.value.kotlinValue() }; is JsonArray -> map { it.kotlinValue() }; is JsonPrimitive -> if(isString)content else booleanOrNull ?: doubleOrNull ?: content }
private fun jsonValue(value: Any?): JsonElement = when(value) { null -> JsonNull; is Map<*,*> -> JsonObject(value.entries.associate { it.key.toString() to jsonValue(it.value) }); is List<*> -> JsonArray(value.map(::jsonValue)); is Boolean -> JsonPrimitive(value); is Number -> JsonPrimitive(value); else -> JsonPrimitive(value.toString()) }

@Composable
private fun AppEditor(vm: ArtifactController, s: ArtifactState, onReset: () -> Unit) {
    var preview by remember { mutableStateOf(false) }; var notice by remember { mutableStateOf<String?>(null) }
    val content = s.content!!
    val clipboard = LocalClipboardManager.current
    Column {
        FlowRow { FilterChip(!preview,{preview=false},label={Text("Source")});FilterChip(preview,{preview=true},label={Text("Preview")});TextButton(onReset,enabled=!s.busy){Text("Reset app data")};TextButton({vm.appHistory()},enabled=!s.busy){Text("App data history")} }
        s.appHistory.forEach { row -> TextButton({vm.restoreAppData(row["revision"]!!.jsonPrimitive.int)},enabled=!s.busy && row["revision"]!!.jsonPrimitive.int != s.appRevision){Text("Restore data revision ${row["revision"]}")} }
        if(!preview) {
            FlowRow { listOf("html","genui").forEach{ format->FilterChip(content.string("format")==format,{vm.edit(JsonObject(content+("format" to JsonPrimitive(format))))},label={Text(format)})} }
            OutlinedTextField(content.string("source"),{vm.edit(JsonObject(content+("source" to JsonPrimitive(it))))},modifier=Modifier.fillMaxWidth(),minLines=12,label={Text("App source")})
        } else key(s.editorKey,content.string("source")) {
            val runtime = remember { UiRuntime(savedState = (s.appData["runtime"]?.kotlinValue() as? Map<String,Any?>).orEmpty()) }
            var rendered by remember { mutableStateOf(if(content.string("format")=="genui")runtime.load(UiCompiler.compile(content.string("source"),true)) else RenderResult(listOf(UiRaw("artifact-app",mapOf("title" to s.title,"app_block_id" to "artifact-app","id" to "artifact-app"),content.string("source"),true)),emptyList())) }
            val act: (UiAction)->Unit = { action->when(action){is UiAction.Copy->clipboard.setText(AnnotatedString(action.text));is UiAction.NewTurn->notice="App requested: ${action.text}";is UiAction.Toast->notice=action.text;else->notice="This app requested an external action. Use the chat to perform it explicitly."} }
            val context=GenUiContext(dispatch={callback,args->rendered=runtime.dispatch(callback,args);vm.appState("runtime",jsonValue(runtime.snapshotState()).toString(),s.selected!!.string("id"));rendered.actions.forEach(act)},act=act,images=null,bindings=emptyMap(),animate=false,
                appBlocks=object:AppBlockStore{override fun report(message:String){notice=message};override fun get(id:String)=vm.state.value.appData[id]?.toString();override fun set(id:String,json:String){runCatching{vm.appState(id,json,s.selected!!.string("id"))}.onFailure{notice=it.message}}})
            GenUiContent(rendered.nodes,context)
            rendered.diagnostics.forEach { Text(it.toString(),color=MaterialTheme.colorScheme.error) }
        }
        notice?.let{Text(it)}
        Text("App code runs offline. Source revisions and saved app data are separate; editing source does not reset data.",style=MaterialTheme.typography.bodySmall)
    }
}
