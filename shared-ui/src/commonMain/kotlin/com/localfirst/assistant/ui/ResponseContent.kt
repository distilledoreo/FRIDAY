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

/** Native rows avoid the third-party table renderer's invisible intrinsic-measurement semantics. */
@Composable
fun NativeResponseContent(content:String) {
    if(ResponseLayout.requiresTextFallback(content)) {
        androidx.compose.foundation.text.selection.SelectionContainer { Text(content,style=MaterialTheme.typography.bodyLarge) };return
    }
    val parts=remember(content) { ResponseParts.parse(content) }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        parts.forEach { part -> when(part) {
            is ResponsePart.Prose -> if(part.markdown.isNotBlank())Markdown(content=part.markdown,modifier=Modifier.fillMaxWidth())
            is ResponsePart.Table -> {
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Column(Modifier.horizontalScroll(rememberScrollState()).width((part.headers.size*160).dp)) {
                            Row { part.headers.forEach { cell -> Text(cell,Modifier.width(160.dp).heightIn(min=48.dp).padding(12.dp),style=MaterialTheme.typography.labelLarge) } }
                            part.rows.forEach { row ->
                                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant,thickness=.5.dp)
                                Row { row.forEach { cell -> Text(cell,Modifier.width(160.dp).heightIn(min=48.dp).padding(12.dp),style=MaterialTheme.typography.bodyMedium) } }
                            }
                        }
                    }
                }
                if(part.headers.size>2)Text("Scroll across for more columns",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
    }
}
