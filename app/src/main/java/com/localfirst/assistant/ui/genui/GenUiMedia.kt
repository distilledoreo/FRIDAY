package com.localfirst.assistant.ui.genui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localfirst.assistant.genui.UiAction
import com.localfirst.assistant.genui.UiElement
import com.localfirst.assistant.genui.UiFormat
import com.localfirst.assistant.genui.UiRaw
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import java.io.ByteArrayInputStream

/** Image search and loading through the PC, so the phone never contacts image hosts itself. */
internal interface GenUiImages {
    suspend fun search(query: String, count: Int): List<ImageHit>
    suspend fun load(url: String, maxEdge: Int): ImageBitmap?
}

data class ImageHit(val image: String, val thumbnail: String, val title: String, val source: String)

/** Saved state of AppBlock apps (friday.state), by block id. */
internal interface AppBlockStore {
    fun get(id: String): String?
    fun set(id: String, json: String)
}

private fun parseRatio(text: String?): Float? = text?.split(':', '/')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 2 && it[1] > 0 }?.let { it[0] / it[1] }

@Composable
internal fun GenImage(url: String, modifier: Modifier, contentDescription: String?, maxEdge: Int = 1024, fit: ContentScale = ContentScale.Crop) {
    val images = LocalGenUi.current.images
    val state = produceState<Any?>(null, url) { value = images?.load(url, maxEdge) ?: false }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        when (val bitmap = state.value) {
            is ImageBitmap -> Image(bitmap, contentDescription, contentScale = fit, modifier = Modifier.fillMaxSize())
            null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else -> GenIcon("image", 22.dp)
        }
    }
}

@Composable
private fun AsyncImageView(e: UiElement, query: String, index: Int = 0, width: Dp? = null) {
    val images = LocalGenUi.current.images
    val hits = produceState<List<ImageHit>?>(null, query) { value = runCatching { images?.search(query, 6) }.getOrNull() ?: emptyList() }
    val ratio = parseRatio(e.string("aspectRatio")) ?: (4f / 3f)
    val maxWidth = GenUiTokens.length(e.props["maxWidth"]) ?: width
    var open by remember { mutableStateOf(false) }
    val hit = hits.value?.getOrNull(index)
    Column(Modifier.let { if (maxWidth != null) it.widthIn(max = maxWidth) else it.fillMaxWidth() }) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(GenUiTokens.shape(e.props["radius"], 12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(enabled = hit != null) { open = true }, contentAlignment = Alignment.Center) {
            when {
                hits.value == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                hit == null -> Text(e.string("alt") ?: query, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                else -> GenImage(hit.thumbnail.ifEmpty { hit.image }, Modifier.fillMaxSize(), e.string("alt") ?: query, maxEdge = 720)
            }
        }
        e.string("caption")?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
    }
    if (open && hit != null) ImageViewer(hit.image, hit.title, hit.source) { open = false }
}

@Composable
private fun ImageViewer(url: String, title: String?, source: String?, onClose: () -> Unit) {
    val context = LocalGenUi.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize().clickable(onClick = onClose)) {
            Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                GenImage(url, Modifier.fillMaxWidth().weight(1f), title, maxEdge = 2048, fit = ContentScale.Fit)
                if (!source.isNullOrBlank()) TextButton(onClick = { context.act(UiAction.OpenUrl(source)) }) {
                    Text(title?.takeIf { it.isNotBlank() } ?: "Open source", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A tool result referenced by `ref`, or the element's own props. */
@Composable
private fun binding(e: UiElement): Map<*, *>? = (e.props["ref"] as? String)?.let { LocalGenUi.current.bindings[it] as? Map<*, *> } ?: (e.props["ref"] as? Map<*, *>)

private fun domain(url: String?): String? = runCatching { java.net.URI(url ?: return null).host?.removePrefix("www.") }.getOrNull()

internal fun registerMedia() {
    GenUiRegistry.register("image") { e ->
        val src = e.string("src") ?: return@register
        val ratio = parseRatio(e.string("aspectRatio"))
        val width = GenUiTokens.length(e.props["width"]) ?: GenUiTokens.length(e.props["maxWidth"])
        var open by remember { mutableStateOf(false) }
        Column(Modifier.let { if (width != null) it.widthIn(max = width) else it.fillMaxWidth() }) {
            GenImage(src, Modifier.fillMaxWidth().let { m -> ratio?.let { m.aspectRatio(it) } ?: m.height(GenUiTokens.length(e.props["height"]) ?: 200.dp) }
                .clip(GenUiTokens.shape(e.props["radius"], 12.dp)).clickable { open = true }, e.string("alt"),
                fit = if (e.string("fit") == "contain") ContentScale.Fit else ContentScale.Crop)
            e.string("caption")?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        }
        if (open) ImageViewer(src, e.string("alt"), null) { open = false }
    }
    GenUiRegistry.register("AsyncImage") { e -> e.string("query")?.let { AsyncImageView(e, it) } }
    GenUiRegistry.register("AsyncImageGroup") { e ->
        val queries = (e.props["queries"] as? List<*>)?.mapNotNull { it?.toString() }
        val height = GenUiTokens.length(e.props["height"]) ?: 140.dp
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (queries != null) queries.forEach { q -> Box(Modifier.width(height * 1.25f)) { AsyncImageView(e, q) } }
            else e.string("query")?.let { q -> repeat((e.number("count") ?: 4.0).toInt().coerceIn(1, 8)) { i -> Box(Modifier.width(height * 1.25f)) { AsyncImageView(e, q, i) } } }
        }
    }
    GenUiRegistry.register("Cite") { e ->
        val context = LocalGenUi.current
        val data = binding(e)
        val url = e.string("url") ?: data?.get("url")?.toString()
        val title = e.string("title") ?: data?.get("title")?.toString() ?: domain(url) ?: "Source"
        AssistChip(onClick = { url?.let { context.act(UiAction.OpenUrl(it)) } }, label = {
            Text((e.number("index")?.let { "${it.toInt()} · " } ?: "") + (domain(url) ?: title), maxLines = 1, overflow = TextOverflow.Ellipsis)
        })
    }
    GenUiRegistry.register("Entity") { e ->
        val context = LocalGenUi.current
        val data = binding(e)
        val name = e.string("name") ?: data?.get("title")?.toString() ?: data?.get("name")?.toString() ?: "Item"
        val subtitle = e.string("subtitle") ?: domain(e.string("url") ?: data?.get("url")?.toString())
        AssistChip(onClick = { context.act(UiAction.OpenEntity(data ?: e.props)) }, label = {
            Column {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        })
    }
    GenUiRegistry.register("product") { e ->
        val context = LocalGenUi.current
        val data = binding(e)
        fun field(name: String) = e.props[name] ?: data?.get(name)
        val url = field("url")?.toString()
        Surface(shape = RoundedCornerShape(16.dp), color = if (LocalFridayPalette.current.dark) Color(0xFF1F1F22) else Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
            modifier = Modifier.fillMaxWidth().clickable(enabled = url != null) { url?.let { context.act(UiAction.OpenUrl(it)) } }) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val image = field("image")?.toString()
                if (image != null) GenImage(image, Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)), field("name")?.toString())
                else field("name")?.toString()?.let { name -> Box(Modifier.size(84.dp)) { AsyncImageView(e, name, width = 84.dp) } }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    field("badge")?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = tokenColor("accent") ?: MaterialTheme.colorScheme.primary) }
                    Text(field("name")?.toString() ?: field("title")?.toString() ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    field("price")?.let { Text(if (it is Number) "$" + UiFormat.number(it.toDouble()) else it.toString(), style = MaterialTheme.typography.titleMedium) }
                    val rating = (field("rating") as? Number)?.toDouble()
                    listOfNotNull(field("merchant")?.toString(), rating?.let { "★ " + UiFormat.number(it) + ((field("reviews") as? Number)?.let { n -> " (${n.toLong()})" } ?: "") })
                        .takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    GenUiRegistry.register("place") { e ->
        val context = androidx.compose.ui.platform.LocalContext.current
        val data = binding(e)
        fun field(name: String) = e.props[name] ?: data?.get(name)
        val name = field("name")?.toString() ?: field("title")?.toString() ?: "Place"
        GenSurface(e, if (LocalFridayPalette.current.dark) Color(0xFF1F1F22) else Color.White, MaterialTheme.colorScheme.onSurface, title = null) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            listOfNotNull(field("category")?.toString(), (field("rating") as? Number)?.let { "★ " + UiFormat.number(it.toDouble()) }, field("hours")?.toString())
                .takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            field("address")?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = {
                    val lat = (field("lat") as? Number)?.toDouble(); val lng = (field("lng") as? Number)?.toDouble()
                    val uri = if (lat != null && lng != null) "geo:$lat,$lng?q=$lat,$lng(${android.net.Uri.encode(name)})" else "geo:0,0?q=" + android.net.Uri.encode(listOfNotNull(name, field("address")?.toString()).joinToString(", "))
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Directions") }
                field("phone")?.toString()?.let { phone ->
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Call") }
                }
            }
        }
    }
    GenUiRegistry.register("video") { e ->
        val context = LocalGenUi.current
        val url = e.string("url") ?: return@register
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable { context.act(UiAction.OpenUrl(url)) }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GenIcon("play", 28.dp, tokenColor("accent") ?: MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(e.string("title") ?: "Video", style = MaterialTheme.typography.titleSmall)
                    domain(url)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

// ---- AppBlock: a self-contained web app in an offline sandbox ------------------------------------------------

private class AppBridge(
    private val onResize: (Int) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onCopy: (String) -> Unit,
    private val load: () -> String?,
    private val save: (String) -> Unit,
) {
    @JavascriptInterface fun resize(height: Int) = onResize(height)
    @JavascriptInterface fun sendMessage(text: String) = onMessage(text.take(4_000))
    @JavascriptInterface fun copy(text: String) = onCopy(text.take(20_000))
    @JavascriptInterface fun loadState(): String = load() ?: "null"
    @JavascriptInterface fun saveState(json: String) { if (json.length <= 200_000) save(json) }
}

private fun cssColor(color: Color): String = "#%08X".format(color.toArgb()).let { "#" + it.substring(3) + it.substring(1, 3) }

@Composable
private fun appDocument(raw: UiRaw): String {
    val scheme = MaterialTheme.colorScheme
    val accent = tokenColor("accent") ?: scheme.primary
    val dark = LocalFridayPalette.current.dark
    val vars = mapOf(
        "--viz-text" to scheme.onSurface, "--viz-muted" to scheme.onSurfaceVariant, "--viz-card" to (if (dark) Color(0xFF1F1F22) else Color.White),
        "--viz-panel" to scheme.surfaceContainerHigh, "--viz-border" to scheme.outlineVariant, "--viz-accent" to accent, "--viz-bg" to scheme.background,
        "--viz-danger" to scheme.error,
    ).entries.joinToString(";") { (k, v) -> "$k:${cssColor(v)}" }
    return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:; font-src data:">
<style>:root{$vars;color-scheme:${if (dark) "dark" else "light"}}*{box-sizing:border-box}html,body{margin:0;padding:0;background:transparent;color:var(--viz-text);font:15px/1.5 system-ui,-apple-system,Roboto,sans-serif}
button,.btn{font:inherit;border:1px solid var(--viz-border);background:var(--viz-panel);color:var(--viz-text);border-radius:10px;padding:8px 14px}
input,select,textarea{font:inherit;color:var(--viz-text);background:var(--viz-card);border:1px solid var(--viz-border);border-radius:8px;padding:6px 8px}
.space-y-4>*+*{margin-top:16px}.w-full{width:100%}.min-w-0{min-width:0}.text-base{font-size:15px}.flex{display:flex}.gap-2{gap:8px}.items-center{align-items:center}</style>
<script>
window.friday={sendMessage:function(t){FridayHost.sendMessage(String(t))},copy:function(t){FridayHost.copy(String(t))},
state:{get:function(){try{return JSON.parse(FridayHost.loadState())}catch(e){return null}},set:function(v){FridayHost.saveState(JSON.stringify(v))}}};
window.openai=window.friday;
function __fridayHeight(){FridayHost.resize(Math.ceil(document.documentElement.scrollHeight))}
window.addEventListener('load',__fridayHeight);new ResizeObserver(__fridayHeight).observe(document.documentElement);
</script></head><body>${raw.source}</body></html>"""
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AppBlockView(raw: UiRaw) {
    val context = LocalGenUi.current
    val title = raw.props["title"]?.toString() ?: "App"
    if (!raw.complete) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  Building $title…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val id = raw.props["app_block_id"]?.toString() ?: raw.key
    var height by remember(raw.key) { mutableStateOf(GenUiTokens.length(raw.props["height"])?.value?.toInt() ?: 240) }
    var pending by remember { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    val document = appDocument(raw)
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val view: @Composable (Modifier) -> Unit = { modifier ->
        AndroidView(modifier = modifier, factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setGeolocationEnabled(false)
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.mediaPlaybackRequiresUserGesture = true
                webViewClient = object : WebViewClient() {
                    // Offline: nothing loads from the network, and links never navigate the frame.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val scheme = request.url.scheme
                        return if (scheme == "data" || scheme == "blob" || scheme == "about") null
                        else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                addJavascriptInterface(AppBridge(
                    onResize = { h -> main.post { height = h.coerceIn(60, 3000) } },
                    onMessage = { text -> main.post { pending = text } },
                    onCopy = { text -> main.post { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) } },
                    load = { context.appBlocks?.get(id) },
                    save = { json -> context.appBlocks?.set(id, json) },
                ), "FridayHost")
                loadDataWithBaseURL("https://appblock.invalid/", document, "text/html", "utf-8", null)
            }
        }, onRelease = { it.destroy() })
    }
    Surface(shape = RoundedCornerShape(16.dp), color = if (LocalFridayPalette.current.dark) Color(0xFF1F1F22) else Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GenIcon(raw.props["icon"]?.toString() ?: "bolt", 16.dp)
                Text("  $title", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { fullscreen = true }) { Text("Open") }
            }
            // CSS pixels are dp in a WebView at default zoom.
            if (raw.props["variant"] != "fullscreen") view(Modifier.fillMaxWidth().height(height.dp.coerceIn(60.dp, 1600.dp)))
        }
    }
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = { fullscreen = false }) { Text("Close") }
                }
                view(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
    pending?.let { text ->
        // The app can't send anything by itself: the user confirms each message.
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Send from $title?") }, text = { Text(text) },
            confirmButton = { Button(onClick = { context.act(UiAction.NewTurn(text)); pending = null }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
}
