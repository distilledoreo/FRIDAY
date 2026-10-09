package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localfirst.assistant.presentation.ToolResponses

/** Local expand/copy/inspect controls are functional; this never starts another tool or model call. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolResultSurface(name:String,content:String,key:String) {
    val result=remember(name,content) { ToolResponses.parse(name,content) } ?: return
    var expanded by remember(key) { mutableStateOf(false) }
    val clipboard=LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        result.entries.take(2).forEach { row ->
            Text(row.title,style=MaterialTheme.typography.bodyMedium)
            row.details.take(2).forEach { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        TextButton(shape = MaterialTheme.shapes.small, onClick={expanded=true}) { Text(if(result.entries.isEmpty())"View result"else "View result · ${result.total} ${if(result.total==1) "item" else "items"}") }
    }
    if(expanded) Dialog(onDismissRequest={expanded=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        FridayDialogWindow()
        Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={TopAppBar(title={Text("Tool result")},navigationIcon={IconButton(onClick={expanded=false}) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back to conversation") }},actions={TextButton(shape = MaterialTheme.shapes.small, onClick={clipboard.setText(AnnotatedString(content))}) { Text("Copy") }})}) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                result.entries.forEach { row ->
                    Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) { Text(row.title,style=MaterialTheme.typography.titleSmall);row.details.forEach { Text(it,style=MaterialTheme.typography.bodyMedium) } }
                    }
                }
                Text("Complete source result",style=MaterialTheme.typography.titleSmall)
                // Plain selectable text is the fallback, including unknown/unsupported fields.
                androidx.compose.foundation.text.selection.SelectionContainer { Text(result.fallback,style=MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
