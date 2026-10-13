package com.localfirst.assistant.ui.workspace
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.localfirst.assistant.genui.*
import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.ui.NativeResponseContent
import com.localfirst.assistant.ui.genui.*
@Composable
fun AssistantReply(content: String, interactive: Boolean, suggest: (String) -> Unit, messages: List<Message> = emptyList()) {
    if (!UiCompiler.isInterface(content)) { NativeResponseContent(content); return }
    val bindings = remember(messages) { UiBindings.from(messages) }
    val runtime = remember(content) { UiRuntime(bindings = bindings) }
    var rendered by remember(content) { mutableStateOf(runtime.load(UiCompiler.compile(content, complete = true))) }
    if (!interactive) { NativeResponseContent(UiFallback.markdown(rendered.nodes).ifBlank { content }); return }
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var notice by remember { mutableStateOf<String?>(null) }
    var entity by remember { mutableStateOf<Any?>(null) }
    val act: (UiAction) -> Unit = { action -> when(action) {
        is UiAction.NewTurn -> suggest(action.text)
        is UiAction.OpenUrl -> uri.openUri(action.url)
        is UiAction.Copy -> clipboard.setText(AnnotatedString(action.text))
        is UiAction.Share -> { clipboard.setText(AnnotatedString(action.text)); notice = "Copied for sharing" }
        is UiAction.Toast -> notice = action.text
        is UiAction.OpenEntity -> entity = action.ref
    } }
    val context = GenUiContext(
        dispatch = { callback, args -> rendered = runtime.dispatch(callback, args); rendered.actions.forEach(act) },
        act = act, images = null, bindings = bindings, animate = false,
    )
    Column {
        GenUiContent(rendered.nodes, context)
        notice?.let { Text(it); TextButton({ notice = null }) { Text("Dismiss") } }
    }
    entity?.let { EntityDetails(it, act) { entity = null } }
}
