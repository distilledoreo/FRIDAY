package com.localfirst.assistant.ui.genui
import androidx.compose.runtime.Composable
import com.localfirst.assistant.genui.UiRaw
import com.localfirst.assistant.ui.NativeResponseContent
/** Browser embedded apps need a sandboxed DOM host; show readable source until supplied. */
@Composable
internal actual fun AppBlockView(raw: UiRaw) { NativeResponseContent("```html\n${raw.source}\n```") }
