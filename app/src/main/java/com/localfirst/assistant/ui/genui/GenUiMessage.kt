package com.localfirst.assistant.ui.genui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.genui.UiAction
import com.localfirst.assistant.genui.UiFallback
import com.localfirst.assistant.ui.NativeResponseContent

/** What an interface reply needs from the chat: its runtime sessions, images, bindings and a way to send. */
internal class GenUiHost(
    val sessions: GenUiSessions,
    val images: GenUiImages?,
    /** The conversation's id, or "draft"/"incognito" before it is saved. */
    val conversation: String,
    val bindings: Map<String, Any?>,
    /** Sends text as the user's next message; false when FRIDAY is still busy. */
    val sendMessage: (String) -> Boolean,
)

/** An assistant reply written in the interface language, rendered with native components. */
@Composable
internal fun GenUiMessage(index: Int, text: String, streaming: Boolean, host: GenUiHost) {
    val session = remember(host.conversation, index) { host.sessions.session(host.conversation, index, host.bindings) }
    LaunchedEffect(session, text, streaming) { session.update(text, complete = !streaming) }
    val result by session.result.collectAsState()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var entity by remember { mutableStateOf<Any?>(null) }
    val act: (UiAction) -> Unit = { action ->
        when (action) {
            is UiAction.NewTurn -> if (!host.sendMessage(action.text)) Toast.makeText(context, "FRIDAY is still answering. Try again in a moment.", Toast.LENGTH_SHORT).show()
            is UiAction.Copy -> { clipboard.setText(AnnotatedString(action.text)); Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show() }
            is UiAction.OpenUrl -> runCatching { uri.openUri(action.url) }
            is UiAction.Share -> runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, action.text), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            is UiAction.Toast -> Toast.makeText(context, action.text, Toast.LENGTH_SHORT).show()
            is UiAction.OpenEntity -> entity = action.ref
        }
    }
    val genContext = remember(session, streaming, host) {
        GenUiContext(
            dispatch = { callback, args -> session.dispatch(callback, args) { actions -> actions.forEach(act) } },
            act = act,
            images = host.images,
            bindings = host.bindings,
            animate = streaming,
            appBlocks = session.appBlocks,
        )
    }
    val nodes = result?.nodes
    if (nodes == null) {
        // The first compile takes milliseconds; until then the text reads as Markdown.
        NativeResponseContent(remember(text) { UiFallback.markdown(text, complete = !streaming) })
    } else {
        GenUiContent(nodes, genContext)
        // ChatGPT drops failing parts silently; FRIDAY says so, with the reasons a tap away.
        val problems = result?.diagnostics.orEmpty().filter { it.code in VISIBLE_PROBLEMS }
        if (!streaming && problems.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Column(Modifier.padding(top = 4.dp)) {
                Text(
                    "Part of this answer couldn't be shown · ${if (open) "Hide" else "Details"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { open = !open },
                )
                if (open) problems.take(8).forEach { Text("• ${it.message}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    entity?.let { EntityDetails(it, act) { entity = null } }
}

private val VISIBLE_PROBLEMS = setOf("runtime_error", "watchdog", "unknown_component", "parse_failed", "object_as_child", "render_loop")

@Composable
private fun EntityDetails(ref: Any?, act: (UiAction) -> Unit, onClose: () -> Unit) {
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
