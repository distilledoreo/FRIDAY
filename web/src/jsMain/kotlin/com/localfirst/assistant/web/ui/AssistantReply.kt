package com.localfirst.assistant.web.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.genui.*
import com.localfirst.assistant.conversation.Message
import com.mikepenz.markdown.m3.Markdown
import kotlinx.browser.window

/** The same compiler and bounded script runtime as Android; platform rendering is Compose. */
@Composable
fun AssistantReply(content: String, interactive: Boolean, suggest: (String) -> Unit, messages: List<Message> = emptyList()) {
    if (!UiCompiler.isInterface(content)) { Markdown(content); return }
    if (!interactive) {
        val text = remember(content, messages) { UiFallback.markdown(UiRuntime(bindings = UiBindings.from(messages)).load(UiCompiler.compile(content, complete = true)).nodes) }
        Markdown(text.ifBlank { content }); return
    }
    val runtime = remember(content) { UiRuntime(bindings = UiBindings.from(messages)) }
    var rendered by remember(content) { mutableStateOf(runtime.load(UiCompiler.compile(content, complete = true))) }
    fun dispatch(callback: UiCallback, args: List<Any?> = emptyList()) {
        rendered = runtime.dispatch(callback, args)
        for (action in rendered.actions) when(action) {
            is UiAction.NewTurn -> suggest(action.text)
            is UiAction.OpenUrl -> if (action.url.startsWith("https://") || action.url.startsWith("http://")) window.open(action.url, "_blank", "noopener,noreferrer")
            is UiAction.Copy -> window.navigator.asDynamic().clipboard?.writeText(action.text)
            else -> Unit
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { for (node in rendered.nodes) RenderNode(node, ::dispatch) }
}
private fun plain(nodes: List<UiNode>): String = nodes.joinToString("") { when(it) { is UiText -> it.text; is UiElement -> plain(it.children); is UiRaw -> it.source } }
@Composable
private fun RenderNode(node: UiNode, dispatch: (UiCallback, List<Any?>) -> Unit) {
    when(node) {
        is UiText -> Markdown(node.text)
        is UiRaw -> Text(node.source, style = MaterialTheme.typography.bodySmall)
        is UiElement -> {
            val e = node
            when(e.type) {
                "row" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (child in e.children) RenderNode(child, dispatch) }
                "button", "link" -> Button({ e.callback("onClick")?.let { dispatch(it, emptyList()) } ?: e.string("href")?.let { if (it.startsWith("https://") || it.startsWith("http://")) window.open(it, "_blank", "noopener,noreferrer") } }, enabled = e.bool("disabled") != true) { Text(plain(e.children).ifBlank { e.string("label") ?: "Continue" }) }
                "input", "textarea" -> {
                    var value by remember(e.key) { mutableStateOf(e.string("value") ?: e.string("defaultValue").orEmpty()) }
                    OutlinedTextField(value, { value = it; e.callback("onChange")?.let { fn -> dispatch(fn, listOf(it)) } }, label = { Text(e.string("label") ?: e.string("placeholder").orEmpty()) }, singleLine = e.type == "input")
                }
                "checkbox", "switch" -> { var checked by remember(e.key) { mutableStateOf(e.bool("checked") ?: false) }; Row { Checkbox(checked, { checked = it; e.callback("onChange")?.let { fn -> dispatch(fn, listOf(it)) } }); Text(plain(e.children).ifBlank { e.string("label").orEmpty() }) } }
                "title" -> Text(plain(e.children), style = MaterialTheme.typography.titleLarge)
                "caption", "badge" -> Text(plain(e.children), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                "markdown" -> Markdown(e.string("content").orEmpty())
                "codeblock" -> Text(e.string("code").orEmpty(), style = MaterialTheme.typography.bodySmall)
                "slider" -> {
                    val min = (e.number("min") ?: 0.0).toFloat(); val max = (e.number("max") ?: 100.0).toFloat().coerceAtLeast(min + 1)
                    val value = (e.number("value") ?: e.number("defaultValue") ?: min.toDouble()).toFloat().coerceIn(min, max)
                    Column { e.string("label")?.let { Text(it) }; Slider(value, { e.callback("onChange")?.let { fn -> dispatch(fn, listOf(it.toDouble())) } }, valueRange = min..max, enabled = e.bool("disabled") != true) }
                }
                "table" -> Markdown(UiFallback.markdown(listOf(e)))
                "divider" -> HorizontalDivider()
                "spacer" -> Spacer(Modifier.height(8.dp))
                "card", "section", "stat" -> Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        e.string("title")?.let { Text(it, style = MaterialTheme.typography.titleMedium) }; e.string("value")?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                        for (child in e.children) RenderNode(child, dispatch)
                    }
                }
                else -> if (e.children.isEmpty()) Markdown(UiFallback.markdown(listOf(e))) else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { for (child in e.children) RenderNode(child, dispatch) }
            }
        }
    }
}
