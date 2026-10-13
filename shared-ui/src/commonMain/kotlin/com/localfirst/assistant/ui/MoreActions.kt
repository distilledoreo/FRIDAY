package com.localfirst.assistant.ui
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
@Composable fun MoreActions(actions: List<Pair<String, () -> Unit>>,contentColor:androidx.compose.ui.graphics.Color=MaterialTheme.colorScheme.onSurfaceVariant) {
    var expanded by remember { mutableStateOf(false) }
    Box { IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.MoreVert, "More options",tint=contentColor) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) { actions.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() }) } }
    }
}
