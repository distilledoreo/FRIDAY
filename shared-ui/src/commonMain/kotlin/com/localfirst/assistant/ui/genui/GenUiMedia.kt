package com.localfirst.assistant.ui.genui

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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localfirst.assistant.genui.UiAction
import com.localfirst.assistant.platform.*
import com.localfirst.assistant.genui.UiElement
import com.localfirst.assistant.genui.UiFormat
import com.localfirst.assistant.genui.UiRaw
import com.localfirst.assistant.ui.theme.LocalFridayPalette

/** Image search and loading through the PC, so the phone never contacts image hosts itself. */
interface GenUiImages {
    suspend fun search(query: String, count: Int): List<ImageHit>
    suspend fun load(url: String, maxEdge: Int): ImageBitmap?
}

data class ImageHit(val image: String, val thumbnail: String, val title: String, val source: String)

/** Saved state of AppBlock apps (friday.state), by block id. */
interface AppBlockStore {
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

private fun domain(url: String?): String? = runCatching { PlatformUri(url ?: return null).host?.removePrefix("www.") }.getOrNull()

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
        val context = androidx.compose.ui.platform.LocalUriHandler.current
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
                    val uri = if (lat != null && lng != null) mapUri(lat, lng, name) else mapUri(null, null, listOfNotNull(name, field("address")?.toString()).joinToString(", "))
                    runCatching { context.openUri(uri) }
                }) { Text("Directions") }
                field("phone")?.toString()?.let { phone ->
                    OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { runCatching { context.openUri("tel:" + phone.filter { it.isDigit() || it == '+' }) } }) { Text("Call") }
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

@Composable
internal expect fun AppBlockView(raw: UiRaw)
