package com.localfirst.assistant.ui.artifacts

import androidx.compose.runtime.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.*
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.events.Event

@Composable
actual fun RichDocumentEditor(content: JsonObject, session: String, onEvent: (String) -> Unit, close: () -> Unit) {
    val event by rememberUpdatedState(onEvent)
    val dismiss by rememberUpdatedState(close)
    DisposableEffect(session) {
        val overlay = document.createElement("div")
        overlay.asDynamic().style.cssText = "position:fixed;inset:0;z-index:20000;background:#fff;display:flex;flex-direction:column;padding:8px;gap:8px"
        val button = document.createElement("button"); button.textContent = "Done editing — keep selection"; button.asDynamic().style.cssText = "font:16px system-ui;padding:12px;align-self:flex-end"
        button.addEventListener("click", { dismiss() })
        val frame = document.createElement("iframe") as HTMLIFrameElement
        frame.title = "FRIDAY rich document editor"; frame.setAttribute("sandbox", "allow-scripts"); frame.setAttribute("referrerpolicy", "no-referrer")
        frame.asDynamic().style.cssText = "width:100%;flex:1;border:1px solid #ccc"
        val init = buildJsonObject { put("kind","init");put("content",content);put("channel",session) }.toString()
        val listener: (Event)->Unit = { e ->
            val message=e.asDynamic()
            if(message.source==frame.contentWindow) {
                val data=message.data as? String
                if(data!=null && data.length<=1500000) {
                    val parsed=runCatching{Json.parseToJsonElement(data).jsonObject}.getOrNull()
                    if(parsed?.get("kind")?.jsonPrimitive?.contentOrNull=="ready") frame.contentWindow?.postMessage(init,"*")
                    else if(parsed?.get("channel")?.jsonPrimitive?.contentOrNull==session)event(data)
                }
            }
        }
        window.addEventListener("message",listener)
        frame.src="/artifact-editor.html"; overlay.appendChild(button);overlay.appendChild(frame);document.body!!.appendChild(overlay)
        onDispose { window.removeEventListener("message",listener);frame.src="about:blank";overlay.remove() }
    }
}

@Composable
actual fun ArtifactDraftGuard(dirty: Boolean) {
    DisposableEffect(dirty) {
        val listener: (Event)->Unit = { e -> if(dirty){e.preventDefault();e.asDynamic().returnValue=""} }
        window.addEventListener("beforeunload",listener)
        onDispose {window.removeEventListener("beforeunload",listener)}
    }
}
