package com.localfirst.assistant.ui.genui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.localfirst.assistant.genui.UiAction
import com.localfirst.assistant.genui.UiCallback
import com.localfirst.assistant.genui.UiElement
import com.localfirst.assistant.genui.UiNode
import com.localfirst.assistant.genui.UiRaw
import com.localfirst.assistant.genui.UiText
import com.localfirst.assistant.ui.NativeResponseContent
import com.localfirst.assistant.ui.theme.LocalFridayPalette

/** What interface components need from the chat around them. */
internal class GenUiContext(
    /** Runs a component callback in the runtime (slider moved, button tapped). */
    val dispatch: (UiCallback, List<Any?>) -> Unit,
    /** Things only the app can do: send a message, open a link, copy. */
    val act: (UiAction) -> Unit,
    /** Image search and loading through the PC; null shows placeholders. */
    val images: GenUiImages?,
    /** Tool results by ref, for Cite and Entity. */
    val bindings: Map<String, Any?>,
    /** New parts animate in while the reply streams. */
    val animate: Boolean,
    /** Saved state of AppBlock apps in this conversation. */
    val appBlocks: AppBlockStore? = null,
)

internal val LocalGenUi = staticCompositionLocalOf<GenUiContext> { error("Interface content outside GenUiContent") }

/** Native renderers by catalog name. Each component file adds its own; unknown types show their children. */
internal object GenUiRegistry {
    val renderers = HashMap<String, @Composable (UiElement) -> Unit>()
    fun register(vararg names: String, render: @Composable (UiElement) -> Unit) { names.forEach { renderers[it] = render } }

    init {
        registerLayout()
        registerInputs()
        registerData()
        registerMedia()
    }
}

/** An interface reply: Markdown prose and native components, in order. */
@Composable
internal fun GenUiContent(nodes: List<UiNode>, context: GenUiContext, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalGenUi provides context) {
        Column(modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            nodes.forEach { node -> androidx.compose.runtime.key(node.key) { GenNode(node) } }
        }
    }
}

@Composable
internal fun GenBlocks(nodes: List<UiNode>, gap: Dp = 10.dp, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        nodes.forEach { node -> androidx.compose.runtime.key(node.key) { GenNode(node) } }
    }
}

@Composable
internal fun GenNode(node: UiNode) {
    when (node) {
        is UiText -> Appear(node.key, slide = false) { NativeResponseContent(node.text.trim()) }
        is UiRaw -> AppBlockView(node)
        is UiElement -> {
            if (node.bool("hidden") == true) return
            val render = GenUiRegistry.renderers[node.type]
            Appear(node.key, slide = true) { if (render != null) render(node) else GenBlocks(node.children) }
        }
    }
}

/** ChatGPT-style entrances while streaming: text fades in over 0.7 s, new components slide in over 0.42 s. */
@Composable
private fun Appear(key: String, slide: Boolean, content: @Composable () -> Unit) {
    val animate = LocalGenUi.current.animate && !LocalFridayPalette.current.reducedMotion
    val state = remember(key) { MutableTransitionState(!animate).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = if (slide) fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 6 } + expandVertically(tween(420)) else fadeIn(tween(700)),
    ) { content() }
}

// ---- tokens in composition ------------------------------------------------------------------------

@Composable
internal fun tokenColor(value: Any?): Color? {
    val palette = LocalFridayPalette.current
    return GenUiTokens.color(value, MaterialTheme.colorScheme, com.localfirst.assistant.ui.theme.readableAccent(palette.accent, MaterialTheme.colorScheme.surface), palette.dark)
}

@Composable
internal fun tone(name: String?): Pair<Color, Color> {
    val palette = LocalFridayPalette.current
    return GenUiTokens.tone(name, MaterialTheme.colorScheme, com.localfirst.assistant.ui.theme.readableAccent(palette.accent, MaterialTheme.colorScheme.surface), palette.dark)
}

/** Sizing, padding, border, background and click from the common layout props. */
@Composable
internal fun Modifier.layout(e: UiElement, defaultPadding: Dp = 0.dp, defaultRadius: Dp = 14.dp): Modifier {
    val context = LocalGenUi.current
    var m = this
    when (val width = e.props["width"]) {
        "full", "100%" -> m = m.fillMaxWidth()
        is String -> if (width.endsWith("%")) width.removeSuffix("%").toFloatOrNull()?.let { m = m.fillMaxWidth((it / 100f).coerceIn(.05f, 1f)) } else GenUiTokens.length(width)?.let { m = m.width(it) }
        else -> GenUiTokens.length(width)?.let { m = m.widthIn(max = it).fillMaxWidth() }
    }
    GenUiTokens.length(e.props["maxWidth"])?.let { m = m.widthIn(max = it) }
    GenUiTokens.length(e.props["minWidth"])?.let { m = m.widthIn(min = it) }
    GenUiTokens.length(e.props["height"])?.let { m = m.height(it) }
    GenUiTokens.length(e.props["minHeight"])?.let { m = m.heightIn(min = it) }
    GenUiTokens.length(e.props["maxHeight"])?.let { m = m.heightIn(max = it) }
    val shape = GenUiTokens.shape(e.props["radius"], defaultRadius)
    val background = tokenColor(e.props["background"])
    val border = e.bool("border") == true
    if (background != null || border) m = m.clip(shape)
    background?.let { m = m.background(it, shape) }
    if (border) m = m.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    e.callback("onClick")?.let { cb -> m = m.clickable { context.dispatch(cb, emptyList()) } }
    val padding = GenUiTokens.spacing(e.props["padding"], defaultPadding)
    val px = e.props["paddingX"]?.let { GenUiTokens.spacing(it) } ?: padding
    val py = e.props["paddingY"]?.let { GenUiTokens.spacing(it) } ?: padding
    return m.padding(horizontal = px, vertical = py)
}

private fun horizontal(justify: String?, gap: Dp): Arrangement.Horizontal = when (justify) {
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    "end" -> Arrangement.spacedBy(gap, Alignment.End)
    "between" -> Arrangement.SpaceBetween
    "around" -> Arrangement.SpaceAround
    "evenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(gap)
}

private fun vertical(justify: String?, gap: Dp): Arrangement.Vertical = when (justify) {
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterVertically)
    "end" -> Arrangement.spacedBy(gap, Alignment.Bottom)
    "between" -> Arrangement.SpaceBetween
    "around" -> Arrangement.SpaceAround
    "evenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(gap)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenRow(e: UiElement, defaultGap: Int = 2, centered: Boolean = true) {
    val gap = GenUiTokens.spacing(e.props["gap"] ?: defaultGap)
    val align = when (e.string("align")) { "start" -> Alignment.Top; "end" -> Alignment.Bottom; "center" -> Alignment.CenterVertically; else -> if (centered) Alignment.CenterVertically else Alignment.Top }
    val modifier = Modifier.layout(e, defaultRadius = 10.dp)
    if (e.bool("wrap") == true) {
        FlowRow(modifier, horizontalArrangement = horizontal(e.string("justify"), gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            e.children.forEach { child -> androidx.compose.runtime.key(child.key) { GenNode(child) } }
        }
        return
    }
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = horizontal(e.string("justify"), gap), verticalAlignment = align) {
        e.children.forEach { child ->
            androidx.compose.runtime.key(child.key) {
                val grow = (child as? UiElement)?.number("grow") ?: if (child is UiText || (child as? UiElement)?.type in setOf("text", "column", "box", "card", "stat", "input", "select")) 1.0 else null
                Box(if (grow != null && grow > 0) Modifier.weight(grow.toFloat()) else Modifier) { GenNode(child) }
            }
        }
    }
}

@Composable
internal fun GenColumn(e: UiElement, defaultGap: Int = 2) {
    val gap = GenUiTokens.spacing(e.props["gap"] ?: defaultGap)
    val align = when (e.string("align")) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start }
    Column(Modifier.layout(e, defaultRadius = 10.dp).fillMaxWidth(), verticalArrangement = vertical(e.string("justify"), gap), horizontalAlignment = align) {
        e.children.forEach { child -> androidx.compose.runtime.key(child.key) { GenNode(child) } }
    }
}

/** A titled surface: card, section and callout share it. */
@Composable
internal fun GenSurface(e: UiElement, container: Color, content: Color, title: String? = e.string("title"), border: Boolean = true, body: @Composable () -> Unit) {
    Surface(
        shape = GenUiTokens.shape(e.props["radius"], 16.dp),
        color = container,
        contentColor = content,
        border = if (border) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (LocalFridayPalette.current.dark) .45f else .7f)) else null,
        modifier = Modifier.fillMaxWidth().let { m -> e.callback("onClick")?.let { cb -> val ctx = LocalGenUi.current; m.clickable { ctx.dispatch(cb, emptyList()) } } ?: m },
    ) {
        Column(Modifier.padding(GenUiTokens.spacing(e.props["padding"], 16.dp)), verticalArrangement = Arrangement.spacedBy(GenUiTokens.spacing(e.props["gap"] ?: 2))) {
            if (title != null || e.string("subtitle") != null || e.string("icon") != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GenIcon(e.string("icon"), 20.dp, content)
                    if (e.string("icon") != null) Spacer(Modifier.width(8.dp))
                    Column {
                        title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                        e.string("subtitle")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            body()
        }
    }
}

@Composable
internal fun GenIcon(name: String?, size: Dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    name ?: return
    val vector = GenUiTokens.icon(name)
    if (vector != null) Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    else GenUiTokens.emoji(name)?.let { Text(it, fontSize = (size.value * .8f).sp) }
}

// ---- text ---------------------------------------------------------------------------------------------

private val inlineMarkdown = Regex("(\\*\\*(.+?)\\*\\*)|(__(.+?)__)|(~~(.+?)~~)|(`([^`]+)`)|(\\[([^\\]]+)]\\((https?://[^)\\s]+)\\))|(\\*([^*\\s][^*]*?)\\*)|(\\b_([^_]+)_\\b)")

/** Text, bold, italic, code and link spans as one styled string. */
@Composable
internal fun inlineText(nodes: List<UiNode>, linkColor: Color): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inline = HashMap<String, InlineTextContent>()
    val code = MaterialTheme.colorScheme.surfaceContainerHighest
    val context = LocalGenUi.current
    val palette = LocalFridayPalette.current
    val scheme = MaterialTheme.colorScheme
    val accent = com.localfirst.assistant.ui.theme.readableAccent(palette.accent, scheme.surface)
    fun colorOf(value: Any?) = GenUiTokens.color(value, scheme, accent, palette.dark)
    val text = buildAnnotatedString {
        fun markdown(text: String) {
            var last = 0
            for (m in inlineMarkdown.findAll(text)) {
                append(text.substring(last, m.range.first))
                val g = m.groupValues
                when {
                    g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]) }
                    g[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[4]) }
                    g[6].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[6]) }
                    g[8].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = code)) { append(g[8]) }
                    g[10].isNotEmpty() -> withLink(LinkAnnotation.Url(g[11], TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(g[10]) }
                    g[13].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[13]) }
                    g[15].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[15]) }
                }
                last = m.range.last + 1
            }
            append(text.substring(last))
        }
        fun walk(list: List<UiNode>) {
            for (node in list) when (node) {
                is UiText -> markdown(node.text)
                is UiRaw -> Unit
                is UiElement -> when (node.type) {
                    "bold" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(node.children) }
                    "italic" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(node.children) }
                    "underline" -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { walk(node.children) }
                    "strike" -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { walk(node.children) }
                    "code", "kbd" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = code)) { walk(node.children) }
                    "mark" -> withStyle(SpanStyle(background = Color(0x66FFD54F))) { walk(node.children) }
                    "link" -> {
                        val href = node.string("href")
                        val cb = node.callback("onClick")
                        val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                        when {
                            href != null -> withLink(LinkAnnotation.Clickable("link:${node.key}", style) { context.act(UiAction.OpenUrl(href)) }) { walk(node.children) }
                            cb != null -> withLink(LinkAnnotation.Clickable("cb:${node.key}", style) { context.dispatch(cb, emptyList()) }) { walk(node.children) }
                            else -> walk(node.children)
                        }
                    }
                    "icon" -> {
                        val id = "icon:${node.key}"
                        inline[id] = InlineTextContent(Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.TextCenter)) { GenIcon(node.string("name"), 18.dp, tokenColor(node.props["color"]) ?: MaterialTheme.colorScheme.onSurfaceVariant) }
                        appendInlineContent(id, " ")
                    }
                    "badge", "chip", "text", "caption", "title", "color" -> withStyle(SpanStyle(color = colorOf(node.props["color"] ?: node.props["value"]) ?: Color.Unspecified)) { walk(node.children) }
                    else -> walk(node.children)
                }
            }
        }
        walk(nodes)
    }
    return text to inline
}

@Composable
internal fun GenText(e: UiElement, base: TextStyle, defaultColor: Color = Color.Unspecified) {
    val color = tokenColor(e.props["color"]) ?: defaultColor
    val (text, inline) = inlineText(e.children, com.localfirst.assistant.ui.theme.readableAccent(LocalFridayPalette.current.accent, MaterialTheme.colorScheme.surface))
    val weight = when (e.string("weight")) { "medium" -> FontWeight.Medium; "semibold" -> FontWeight.SemiBold; "bold" -> FontWeight.Bold; "regular" -> FontWeight.Normal; else -> null }
    val style = base.copy(
        fontSize = GenUiTokens.textSize(e.props["size"], base.fontSize),
        lineHeight = if (e.props["size"] != null) GenUiTokens.textSize(e.props["size"], base.fontSize) * 1.3f else base.lineHeight,
        fontWeight = weight ?: base.fontWeight,
        fontStyle = if (e.bool("italic") == true) FontStyle.Italic else base.fontStyle,
        fontFamily = if (e.bool("mono") == true) FontFamily.Monospace else base.fontFamily,
        textAlign = when (e.string("align")) { "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start },
    )
    val lines = e.number("lines")?.toInt()?.takeIf { it > 0 }
    Text(text, style = style, color = color, inlineContent = inline, maxLines = lines ?: Int.MAX_VALUE, overflow = if (lines != null) TextOverflow.Ellipsis else TextOverflow.Clip,
        modifier = Modifier.layout(e, defaultRadius = 6.dp))
}

// ---- layout components ------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun registerLayout() {
    GenUiRegistry.register("box") { e -> if (e.string("direction") == "row") GenRow(e, centered = false) else GenColumn(e) }
    GenUiRegistry.register("row") { e -> GenRow(e) }
    GenUiRegistry.register("column") { e -> GenColumn(e) }
    GenUiRegistry.register("grid") { e ->
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = when (val c = e.props["columns"]) {
                is Number -> c.toInt()
                is Map<*, *> -> {
                    // Breakpoints: base under 600 dp, md from 600, lg from 900.
                    val base = (c["base"] as? Number)?.toInt() ?: 1
                    val md = (c["md"] as? Number)?.toInt() ?: (c["sm"] as? Number)?.toInt() ?: base
                    val lg = (c["lg"] as? Number)?.toInt() ?: md
                    when { maxWidth >= 900.dp -> lg; maxWidth >= 600.dp -> md; else -> base }
                }
                else -> GenUiTokens.length(e.props["minItemWidth"])?.let { (maxWidth / it).toInt() } ?: (if (maxWidth >= 600.dp) 3 else 2)
            }.coerceIn(1, 6)
            val gap = GenUiTokens.spacing(e.props["gap"] ?: 2)
            Column(Modifier.layout(e).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                e.children.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { child -> androidx.compose.runtime.key(child.key) { Box(Modifier.weight(1f).fillMaxHeight()) { GenNode(child) } } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    GenUiRegistry.register("card") { e ->
        val (container, content) = when (e.string("variant")) {
            "filled" -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
            "ghost" -> Color.Transparent to MaterialTheme.colorScheme.onSurface
            else -> e.string("tone")?.let { tone(it) } ?: ((tokenColor(e.props["background"]) ?: if (LocalFridayPalette.current.dark) Color(0xFF1F1F22) else Color.White) to MaterialTheme.colorScheme.onSurface)
        }
        GenSurface(e, container, content, border = e.string("variant") != "ghost") { GenBlocks(e.children, GenUiTokens.spacing(e.props["gap"] ?: 2)) }
    }
    GenUiRegistry.register("section") { e ->
        var open by rememberSaveable(e.key) { mutableStateOf(e.bool("defaultOpen") ?: true) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            e.string("title")?.let { title ->
                Row(Modifier.fillMaxWidth().let { if (e.bool("collapsible") == true) it.clickable { open = !open } else it }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        e.string("subtitle")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (e.bool("collapsible") == true) GenIcon(if (open) "chevron-up" else "chevron-down", 20.dp)
                }
            }
            if (open) GenBlocks(e.children, GenUiTokens.spacing(e.props["gap"] ?: 2), Modifier.layout(e))
        }
    }
    GenUiRegistry.register("collapsible") { e ->
        val context = LocalGenUi.current
        var open by rememberSaveable(e.key) { mutableStateOf(e.bool("open") ?: e.bool("defaultOpen") ?: false) }
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.animateContentSize()) {
                Row(Modifier.fillMaxWidth().clickable { open = !open; e.callback("onToggle")?.let { context.dispatch(it, listOf(open)) } }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.string("title") ?: "Details", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    GenIcon(if (open) "chevron-up" else "chevron-down", 20.dp)
                }
                if (open) GenBlocks(e.children, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
            }
        }
    }
    GenUiRegistry.register("tabs") { e ->
        val context = LocalGenUi.current
        val tabs = e.children.filterIsInstance<UiElement>().filter { it.type == "tab" }
        if (tabs.isEmpty()) { GenBlocks(e.children); return@register }
        val controlled = e.string("value")
        var local by rememberSaveable(e.key) { mutableStateOf(e.string("defaultValue") ?: tabs.first().let { it.string("value") ?: it.string("label") ?: "0" }) }
        val current = controlled ?: local
        val index = tabs.indexOfFirst { (it.string("value") ?: it.string("label")) == current }.coerceAtLeast(0)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryScrollableTabRow(selectedTabIndex = index, edgePadding = 0.dp, containerColor = Color.Transparent) {
                tabs.forEachIndexed { i, tab ->
                    val value = tab.string("value") ?: tab.string("label") ?: i.toString()
                    Tab(selected = i == index, onClick = {
                        local = value
                        e.callback("onChange")?.let { context.dispatch(it, listOf(value)) }
                    }, text = { Text(tab.string("label") ?: value, maxLines = 1) })
                }
            }
            GenBlocks(tabs[index].children)
        }
    }
    GenUiRegistry.register("tab") { e -> GenBlocks(e.children) }
    GenUiRegistry.register("scroll") { e ->
        Row(Modifier.horizontalScroll(rememberScrollState()).height(IntrinsicSize.Max).layout(e), horizontalArrangement = Arrangement.spacedBy(GenUiTokens.spacing(e.props["gap"] ?: 3))) {
            e.children.forEach { child -> androidx.compose.runtime.key(child.key) { Box(Modifier.widthIn(max = 280.dp).fillMaxHeight()) { GenNode(child) } } }
        }
    }
    GenUiRegistry.register("divider") { e ->
        val label = e.string("label")
        val spacing = GenUiTokens.spacing(e.props["spacing"] ?: 1)
        if (e.bool("vertical") == true) VerticalDivider(Modifier.height(24.dp)) else if (label == null) HorizontalDivider(Modifier.padding(vertical = spacing), color = MaterialTheme.colorScheme.outlineVariant)
        else Row(Modifier.padding(vertical = spacing), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            Text(label, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    GenUiRegistry.register("spacer") { e -> Spacer(Modifier.height(GenUiTokens.spacing(e.props["size"] ?: 2))) }
    GenUiRegistry.register("title") { e -> GenText(e, MaterialTheme.typography.titleLarge.let { if (e.props["size"] == null) it else it.copy(fontWeight = FontWeight.SemiBold) }) }
    GenUiRegistry.register("text") { e -> GenText(e, MaterialTheme.typography.bodyLarge) }
    GenUiRegistry.register("caption") { e -> GenText(e, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant) }
    GenUiRegistry.register("bold", "italic", "underline", "strike", "code", "mark", "link", "color", "kbd") { e ->
        GenText(UiElement("text", e.key, emptyMap(), listOf(e)), MaterialTheme.typography.bodyLarge)
    }
    GenUiRegistry.register("quote") { e ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(tokenColor("accent") ?: MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            Column(Modifier.padding(start = 12.dp)) {
                GenBlocks(e.children)
                e.string("cite")?.let { Text("— $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    GenUiRegistry.register("list") { e ->
        val ordered = e.bool("ordered") == true
        val items = e.children.filterIsInstance<UiElement>()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GenUiTokens.spacing(e.props["gap"] ?: 1.5))) {
            items.forEachIndexed { i, item ->
                androidx.compose.runtime.key(item.key) {
                    Row(verticalAlignment = Alignment.Top) {
                        val marker = item.string("icon")
                        Box(Modifier.width(24.dp).padding(top = 2.dp)) {
                            if (marker != null) GenIcon(marker, 18.dp, tokenColor("accent") ?: MaterialTheme.colorScheme.primary)
                            else Text(if (ordered) "${i + 1}." else (e.string("marker") ?: "•"), style = MaterialTheme.typography.bodyLarge, color = tokenColor("accent") ?: MaterialTheme.colorScheme.primary)
                        }
                        Box(Modifier.weight(1f)) {
                            if (item.children.all { it is UiText || (it as? UiElement)?.type in setOf("bold", "italic", "code", "link", "strike", "icon") }) GenText(UiElement("text", item.key, emptyMap(), item.children), MaterialTheme.typography.bodyLarge)
                            else GenBlocks(item.children, 6.dp)
                        }
                    }
                }
            }
        }
    }
    GenUiRegistry.register("item") { e -> GenBlocks(e.children) }
    GenUiRegistry.register("callout") { e ->
        val tone = e.string("tone") ?: "info"
        val (container, content) = tone(tone)
        GenSurface(e, container, content, border = false, title = e.string("title")) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) { GenBlocks(e.children, 6.dp) }
        }
    }
}
