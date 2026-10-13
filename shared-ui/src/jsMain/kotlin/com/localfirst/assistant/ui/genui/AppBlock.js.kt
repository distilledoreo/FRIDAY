package com.localfirst.assistant.ui.genui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.genui.UiRaw
import com.localfirst.assistant.genui.UiAction
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.events.Event

/** An opaque-origin iframe, opened above the canvas. No network, credentials, storage or parent DOM access. */
@Composable
internal actual fun AppBlockView(raw: UiRaw) {
    val context = LocalGenUi.current
    var open by remember(raw.key) { mutableStateOf(false) }
    var pending by remember(raw.key) { mutableStateOf<String?>(null) }
    val title = raw.props["title"]?.toString() ?: "Interactive app"
    val id = raw.props["id"]?.toString() ?: raw.key
    Column(Modifier.fillMaxWidth()) { Text(title, style = MaterialTheme.typography.titleMedium); OutlinedButton({ open = true }) { Text("Open interactive app") } }
    if (open) DisposableEffect(raw.source, id) {
        val overlay = document.createElement("div")
        overlay.asDynamic().style.cssText = "position:fixed;inset:0;z-index:10000;background:#161619;padding:16px;display:flex;flex-direction:column;gap:12px"
        val close = document.createElement("button"); close.textContent = "Close $title"; close.asDynamic().style.cssText = "align-self:flex-end;padding:12px;font:16px system-ui"
        val frame = document.createElement("iframe") as HTMLIFrameElement
        frame.title = title; frame.setAttribute("sandbox", "allow-scripts"); frame.setAttribute("referrerpolicy", "no-referrer")
        frame.asDynamic().style.cssText = "width:100%;flex:1;border:0;background:white;border-radius:12px"
        val saved = context.appBlocks?.get(id)?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).toString() }.getOrNull() } ?: "null"
        val initial = saved.replace("<", "\\u003c")
        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:; font-src data:; connect-src 'none'; form-action 'none'; base-uri 'none'">
<style>:root{--viz-text:#25252b;--viz-muted:#666;--viz-card:#fff;--viz-panel:#eee;--viz-border:#ddd;--viz-accent:#b66348;--viz-bg:#fff;--viz-danger:#ba1a1a}body{font:15px system-ui;margin:12px}</style>
<script>let savedState=$initial;window.friday={sendMessage:t=>parent.postMessage({kind:'friday-message',text:String(t)},'*'),copy:t=>parent.postMessage({kind:'friday-copy',text:String(t)},'*'),state:{get:()=>savedState,set:v=>{savedState=v;parent.postMessage({kind:'friday-state',json:JSON.stringify(v)},'*')}};</script></head><body>${raw.source}</body></html>"""
        val blob = js("new Blob([html], {type:'text/html'})") as org.w3c.files.Blob
        val url = org.w3c.dom.url.URL.createObjectURL(blob); frame.src = url
        val listener: (Event) -> Unit = { event ->
            val message = event.asDynamic()
            if (message.source == frame.contentWindow) {
                val data = message.data
                when(data?.kind as? String) {
                    "friday-message" -> { val text = (data.text as? String).orEmpty().take(4000); if (text.isNotBlank()) { pending = text; open = false } }
                    "friday-copy" -> context.act(UiAction.Copy((data.text as? String).orEmpty().take(20000)))
                    "friday-state" -> (data.json as? String)?.takeIf { it.length <= 200000 }?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull()?.let { value -> context.appBlocks?.set(id, value.toString()) } }
                }
            }
        }
        close.addEventListener("click", { open = false })
        overlay.appendChild(close); overlay.appendChild(frame); document.body!!.appendChild(overlay)
        window.addEventListener("message", listener)
        onDispose { window.removeEventListener("message", listener); frame.src = "about:blank"; overlay.remove(); org.w3c.dom.url.URL.revokeObjectURL(url) }
    }
    pending?.let { text -> AlertDialog(onDismissRequest = { pending = null }, title = { Text("Use this message from $title?") }, text = { Text(text) }, confirmButton = { TextButton({ context.act(UiAction.NewTurn(text)); pending = null }) { Text("Add to composer") } }, dismissButton = { TextButton({ pending = null }) { Text("Cancel") } }) }
}
