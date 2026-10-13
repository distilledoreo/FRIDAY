package com.localfirst.assistant.ui.genui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.genui.UiAction
@Composable
fun EntityDetails(ref: Any?, act: (UiAction) -> Unit, onClose: () -> Unit) {
    val data = ref as? Map<*, *> ?: emptyMap<String, Any?>()
    val title = (data["title"] ?: data["name"])?.toString() ?: "Details"
    val url = data["url"]?.toString()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("subtitle", "domain", "price", "merchant", "address", "snippet", "published").forEach { key ->
                    data[key]?.toString()?.takeIf { it.isNotBlank() }?.let { Text(it, style = if (key == "snippet") MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { if (url != null) Button(onClick = { act(UiAction.OpenUrl(url)); onClose() }) { Text("Open") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
