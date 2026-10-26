package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.presentation.ResponsePart
import com.localfirst.assistant.presentation.ResponseParts
import androidx.compose.foundation.horizontalScroll
import com.localfirst.assistant.presentation.ResponseLayout
import com.localfirst.assistant.presentation.ResponsePattern
import com.mikepenz.markdown.m3.Markdown

/** Reusable native content surface: real Markdown/table/code, expandable long content, text fallback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResponseRenderer(content:String,pattern:ResponsePattern,onCopy:()->Unit) {
    var expanded by remember { mutableStateOf(false) }
    if(pattern==ResponsePattern.EXPANDABLE) {
        Text(ResponseLayout.preview(content),style=MaterialTheme.typography.bodyLarge)
        TextButton(shape = MaterialTheme.shapes.small, onClick={expanded=true}) { Text("Expand response") }
    } else NativeResponseContent(content)
    if(expanded)Dialog(onDismissRequest={expanded=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        FridayDialogWindow(fullScreen = true)
        Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={TopAppBar(title={Text("Response")},navigationIcon={IconButton(onClick={expanded=false}) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back to conversation") }},actions={TextButton(shape = MaterialTheme.shapes.small, onClick=onCopy) { Text("Copy") }})}) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().dialogNavigationBarPadding().verticalScroll(rememberScrollState()).padding(20.dp)) { NativeResponseContent(content) }
        }
    }
}
