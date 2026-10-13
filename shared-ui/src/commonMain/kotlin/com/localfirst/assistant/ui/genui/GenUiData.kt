package com.localfirst.assistant.ui.genui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localfirst.assistant.platform.*
import com.localfirst.assistant.genui.UiElement
import com.localfirst.assistant.genui.UiFormat
import com.localfirst.assistant.genui.UiNode
import com.localfirst.assistant.genui.UiText
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.delay

private fun num(v: Any?): Double? = when (v) { is Number -> v.toDouble(); is String -> v.replace(",", "").replace("$", "").removeSuffix("%").trim().toDoubleOrNull(); else -> null }
private fun str(v: Any?): String = when (v) { null -> ""; is Double -> UiFormat.number(v); is Number -> UiFormat.number(v.toDouble()); else -> v.toString() }

/** Series colors: the accent, then distinct palette hues. */
@Composable
private fun seriesColors(e: UiElement): List<Color> {
    val custom = (e.props["colors"] as? List<*>).orEmpty().mapNotNull { tokenColor(it) }
    if (custom.isNotEmpty()) return custom
    return listOf("accent", "blue", "green", "amber", "violet", "pink", "teal", "orange").mapNotNull { tokenColor(it) }
}

private fun formatter(e: UiElement): (Double) -> String = when (e.string("format")) {
    "currency" -> { v -> "$" + if (abs(v) >= 1000) formatUiDecimal(v, 0) else formatUiDecimal(v, 2).removeSuffix(".00") }
    "percent" -> { v -> UiFormat.number(v) + "%" }
    "compact" -> { v -> when { abs(v) >= 1e9 -> UiFormat.number(v / 1e9) + "B"; abs(v) >= 1e6 -> UiFormat.number(v / 1e6) + "M"; abs(v) >= 1e3 -> UiFormat.number(v / 1e3) + "K"; else -> UiFormat.number(v) } }
    else -> { v -> if (abs(v) >= 10_000) formatUiDecimal(v, 0) else UiFormat.number(v) }
}

/** A chart's data as labels plus named series, from labels/values, data + x/y, or series. */
private data class ChartData(val labels: List<String>, val series: List<Pair<String, List<Double>>>)

private fun chartData(e: UiElement): ChartData {
    val labels = (e.props["labels"] as? List<*>)?.map(::str)
    val values = (e.props["values"] as? List<*>)?.map { num(it) ?: 0.0 }
    if (labels != null && values != null) return ChartData(labels, listOf((e.string("title") ?: "Value") to values))
    val series = (e.props["series"] as? List<*>)?.mapNotNull { s ->
        val m = s as? Map<*, *> ?: return@mapNotNull null
        str(m["name"] ?: m["label"]) to ((m["data"] ?: m["values"]) as? List<*>).orEmpty().map { v -> num(if (v is Map<*, *>) v["y"] ?: v["value"] else v) ?: 0.0 }
    }
    if (series != null && series.isNotEmpty()) return ChartData(labels ?: series.first().second.indices.map { (it + 1).toString() }, series)
    val rows = (e.props["data"] as? List<*>).orEmpty()
    if (rows.isNotEmpty() && rows.first() !is Map<*, *>) return ChartData(labels ?: rows.indices.map { (it + 1).toString() }, listOf("Value" to rows.map { num(it) ?: 0.0 }))
    val maps = rows.filterIsInstance<Map<*, *>>()
    val x = e.string("x") ?: maps.firstOrNull()?.keys?.firstOrNull { k -> maps.first()[k] is String }?.toString() ?: "label"
    val ys = when (val y = e.props["y"]) {
        is List<*> -> y.map(::str)
        null -> maps.firstOrNull()?.keys?.map { it.toString() }?.filter { k -> k != x && num(maps.first()[k]) != null }.orEmpty()
        else -> listOf(str(y))
    }
    return ChartData(maps.map { str(it[x]) }, ys.map { k -> k to maps.map { num(it[k]) ?: 0.0 } })
}

/** Charts draw in over 1.8 s the first time they appear while a reply streams, as ChatGPT's do. */
@Composable
private fun drawProgress(key: String): Float {
    val animate = LocalGenUi.current.animate && !LocalFridayPalette.current.reducedMotion
    val progress = remember(key) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(1800, easing = FastOutSlowInEasing)) }
    return progress.value
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chart(e: UiElement) {
    val data = remember(e.props) { chartData(e) }
    val type = e.string("type") ?: "bar"
    val colors = seriesColors(e)
    val format = formatter(e)
    val progress = drawProgress(e.key)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    var selected by remember(e.key) { mutableStateOf<Int?>(null) }
    Surface(shape = RoundedCornerShape(16.dp), color = if (LocalFridayPalette.current.dark) Color(0xFF1F1F22) else Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            e.string("title")?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            if (data.series.isEmpty() || data.labels.isEmpty()) { Text("No data", color = labelColor); return@Column }
            val height = GenUiTokens.length(e.props["height"]) ?: 180.dp
            if (type == "pie" || type == "donut") {
                val values = data.series.first().second
                val total = values.sumOf { max(it, 0.0) }.takeIf { it > 0 } ?: 1.0
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Canvas(Modifier.size(height.coerceAtMost(160.dp))) {
                        var start = -90f
                        values.forEachIndexed { i, v ->
                            val sweep = (max(v, 0.0) / total * 360 * progress).toFloat()
                            if (type == "donut") drawArc(colors[i % colors.size], start, sweep, false, style = Stroke(size.minDimension * .18f))
                            else drawArc(colors[i % colors.size], start, sweep, true)
                            start += sweep
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        data.labels.forEachIndexed { i, label ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i % colors.size]))
                                Text("  $label", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                                Text("  " + format(values.getOrElse(i) { 0.0 }), style = MaterialTheme.typography.bodySmall, color = labelColor)
                            }
                        }
                    }
                }
                return@Column
            }
            val all = data.series.flatMap { it.second }
            val stacked = e.bool("stacked") == true && type == "bar"
            val maxValue = (if (stacked) data.labels.indices.maxOfOrNull { i -> data.series.sumOf { it.second.getOrElse(i) { 0.0 } } } else all.maxOrNull())?.takeIf { it > 0 } ?: 1.0
            val minValue = minOf(0.0, all.minOrNull() ?: 0.0)
            val range = (maxValue - minValue).takeIf { it > 0 } ?: 1.0
            selected?.let { i ->
                Text(data.labels.getOrElse(i) { "" } + ": " + data.series.joinToString(", ") { (name, v) -> (if (data.series.size > 1) "$name " else "") + format(v.getOrElse(i) { 0.0 }) },
                    style = MaterialTheme.typography.labelLarge, color = tokenColor("accent") ?: MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth().height(height)) {
                Column(Modifier.fillMaxHeight().padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                    Text(format(maxValue), style = MaterialTheme.typography.labelSmall, color = labelColor)
                    Text(format(minValue + range / 2), style = MaterialTheme.typography.labelSmall, color = labelColor)
                    Text(format(minValue), style = MaterialTheme.typography.labelSmall, color = labelColor)
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().clickable { selected = selected?.let { (it + 1) % data.labels.size } ?: 0 }) {
                    val n = data.labels.size
                    fun y(v: Double) = size.height - ((v - minValue) / range * size.height).toFloat()
                    for (k in 0..2) drawLine(grid, Offset(0f, size.height * k / 2), Offset(size.width, size.height * k / 2), 1f)
                    when (type) {
                        "line", "area", "scatter" -> {
                            val step = if (n > 1) size.width / (n - 1) else size.width
                            data.series.forEachIndexed { s, (_, values) ->
                                val color = colors[s % colors.size]
                                val points = values.take(n).mapIndexed { i, v -> Offset(i * step, y(v)) }
                                if (type == "scatter") points.forEach { drawCircle(color, 6f, it) } else {
                                    val path = Path()
                                    val visible = (points.size * progress).coerceAtLeast(1f)
                                    points.forEachIndexed { i, p -> if (i < visible) { if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) } }
                                    if (type == "area" && points.isNotEmpty()) {
                                        val fill = Path().apply { addPath(path); lineTo(points[(visible.toInt() - 1).coerceIn(0, points.lastIndex)].x, size.height); lineTo(points.first().x, size.height); close() }
                                        drawPath(fill, color.copy(alpha = .18f))
                                    }
                                    drawPath(path, color, style = Stroke(5f, cap = StrokeCap.Round))
                                    points.take(visible.toInt()).forEach { drawCircle(color, 5f, it) }
                                }
                            }
                        }
                        else -> {
                            val slot = size.width / n
                            val groups = if (stacked) 1 else data.series.size
                            val barWidth = (slot * .7f / groups).coerceAtMost(48f)
                            for (i in 0 until n) {
                                var base = 0.0
                                data.series.forEachIndexed { s, (_, values) ->
                                    val v = values.getOrElse(i) { 0.0 } * progress
                                    val color = colors[s % colors.size].let { if (selected == null || selected == i) it else it.copy(alpha = .45f) }
                                    val left = i * slot + (slot - barWidth * groups) / 2 + if (stacked) 0f else s * barWidth
                                    val top = y(base + max(v, 0.0))
                                    val bottom = y(if (stacked) base else minOf(0.0, v).coerceAtLeast(minValue))
                                    drawRoundRect(color, Offset(left, top), Size(barWidth - 3f, (bottom - top).coerceAtLeast(1f)), androidx.compose.ui.geometry.CornerRadius(6f, 6f))
                                    if (stacked) base += max(v, 0.0)
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 36.dp)) {
                val shown = if (data.labels.size > 8) data.labels.filterIndexed { i, _ -> i % ((data.labels.size + 5) / 6) == 0 } else data.labels
                shown.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
            }
            if (data.series.size > 1 && e.bool("legend") != false) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                data.series.forEachIndexed { s, (name, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).clip(CircleShape).background(colors[s % colors.size])); Text(" $name", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, color: Color, height: Dp, key: String) {
    val progress = drawProgress(key)
    Canvas(Modifier.fillMaxWidth().height(height)) {
        if (values.size < 2) return@Canvas
        val min = values.min(); val range = (values.max() - min).takeIf { it > 0 } ?: 1.0
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v -> val p = Offset(i * step, size.height - ((v - min) / range * size.height).toFloat()); if (i == 0) path.moveTo(p.x, p.y) else if (i <= values.size * progress) path.lineTo(p.x, p.y) }
        drawPath(path, color, style = Stroke(4f, cap = StrokeCap.Round))
    }
}

@Composable
private fun Ring(fraction: Float, size: Dp, color: Color, track: Color, label: String?) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * .1f
            drawArc(track, -90f, 360f, false, style = Stroke(stroke))
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(label ?: "${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Countdown(e: UiElement, totalSeconds: Long?, target: Long?) {
    // Runs on the phone: the sandbox has no timers, so time-based components are native.
    var remaining by rememberSaveable(e.key) { mutableLongStateOf(totalSeconds ?: 0L) }
    var running by rememberSaveable(e.key) { mutableStateOf(target != null || e.bool("autoStart") == true) }
    var now by remember { mutableLongStateOf(PlatformSystem.currentTimeMillis()) }
    LaunchedEffect(running, target) {
        while (running) {
            delay(1000)
            now = PlatformSystem.currentTimeMillis()
            if (target == null) { remaining = (remaining - 1).coerceAtLeast(0); if (remaining == 0L) running = false }
        }
    }
    val left = target?.let { ((it - now) / 1000).coerceAtLeast(0) } ?: remaining
    val days = left / 86400
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        e.string("label")?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text((if (days > 0) "${days}d " else "") + UiFormat.duration(left % 86400), style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace))
        if (target == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { running = !running }, shape = MaterialTheme.shapes.small) { Text(if (running) "Pause" else if (remaining == totalSeconds) "Start" else "Resume") }
            TextButton(onClick = { running = false; remaining = totalSeconds ?: 0 }) { Text("Reset") }
        }
    }
}

@Composable
private fun TableView(e: UiElement) {
    val columns = (e.props["columns"] as? List<*>).orEmpty().map { c -> (c as? Map<*, *>)?.let { str(it["label"] ?: it["key"]) to str(it["key"] ?: it["label"]) } ?: (str(c) to str(c)) }
    val rows: List<List<String>> = (e.props["rows"] as? List<*>).orEmpty().map { row ->
        when (row) {
            is List<*> -> row.map(::str)
            is Map<*, *> -> if (columns.isNotEmpty()) columns.map { (_, key) -> str(row[key]) } else row.values.map(::str)
            else -> listOf(str(row))
        }
    }
    val fromChildren = e.children.filterIsInstance<UiElement>().flatMap { if (it.type == "tr") listOf(it) else it.children.filterIsInstance<UiElement>().filter { r -> r.type == "tr" } }
    val header: List<String>
    val body: List<List<Any>>
    if (columns.isNotEmpty() || rows.isNotEmpty()) {
        header = columns.map { it.first }
        body = rows
    } else {
        val headRow = fromChildren.firstOrNull()?.takeIf { r -> r.children.filterIsInstance<UiElement>().all { it.type == "th" } }
        header = headRow?.children?.filterIsInstance<UiElement>()?.map { cell -> plain(cell.children) }.orEmpty()
        body = fromChildren.filter { it !== headRow }.map { r -> r.children.filterIsInstance<UiElement>() }
    }
    val compact = e.bool("compact") == true
    val cellPadding = if (compact) 8.dp else 12.dp
    val widthPerColumn = 150.dp
    val count = maxOf(header.size, body.maxOfOrNull { it.size } ?: 0)
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.horizontalScroll(rememberScrollState()).width(widthPerColumn * count)) {
            if (header.isNotEmpty()) Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                header.forEach { Text(it, Modifier.width(widthPerColumn).padding(cellPadding), style = MaterialTheme.typography.labelLarge) }
            }
            body.forEachIndexed { i, row ->
                if (i > 0 || header.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = .5.dp)
                Row(Modifier.background(if (e.bool("striped") == true && i % 2 == 1) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .5f) else Color.Transparent)) {
                    row.forEach { cell ->
                        Box(Modifier.width(widthPerColumn).padding(cellPadding)) {
                            when (cell) {
                                is UiElement -> GenText(UiElement("text", cell.key, emptyMap(), cell.children), MaterialTheme.typography.bodyMedium)
                                else -> Text(cell.toString(), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    e.string("caption")?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
}

private fun plain(nodes: List<UiNode>): String = nodes.joinToString("") { n -> when (n) { is UiText -> n.text; is UiElement -> plain(n.children); else -> "" } }

@OptIn(ExperimentalLayoutApi::class)
internal fun registerData() {
    GenUiRegistry.register("chart") { e -> Chart(e) }
    GenUiRegistry.register("sparkline") { e ->
        Sparkline((e.props["data"] as? List<*>).orEmpty().mapNotNull(::num), tokenColor(e.props["color"] ?: "accent") ?: MaterialTheme.colorScheme.primary, GenUiTokens.length(e.props["height"]) ?: 36.dp, e.key)
    }
    GenUiRegistry.register("progress") { e ->
        val max = e.number("max")?.takeIf { it > 0 } ?: 100.0
        val fraction = ((e.number("value") ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
        val (_, color) = tone(e.string("tone") ?: "accent")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (e.string("label") != null || e.bool("showValue") == true) Row(Modifier.fillMaxWidth()) {
                Text(e.string("label").orEmpty(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { fraction }, color = color, trackColor = color.copy(alpha = .15f), modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
        }
    }
    GenUiRegistry.register("ring") { e ->
        val max = e.number("max")?.takeIf { it > 0 } ?: 100.0
        val (_, color) = tone(e.string("tone") ?: "accent")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Ring(((e.number("value") ?: 0.0) / max).toFloat() * drawProgress(e.key), GenUiTokens.length(e.props["size"]) ?: 96.dp, color, color.copy(alpha = .15f), null)
            e.string("label")?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    GenUiRegistry.register("stat") { e ->
        val delta = e.props["delta"]
        val deltaText = str(delta)
        val positive = num(delta)?.let { it >= 0 } ?: !deltaText.startsWith("-")
        val deltaTone = when (e.string("deltaTone")) { "success" -> "success"; "danger" -> "danger"; "neutral" -> null; else -> if (positive) "success" else "danger" }
        Surface(shape = RoundedCornerShape(16.dp), color = if (LocalFridayPalette.current.dark) Color(0xFF1F1F22) else Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GenIcon(e.string("icon"), 16.dp)
                    Text((if (e.string("icon") != null) " " else "") + e.string("label").orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(str(e.props["value"]).ifEmpty { plain(e.children) }, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
                if (deltaText.isNotEmpty()) Text((if (positive && !deltaText.startsWith("+") && num(delta) != null) "+" else "") + deltaText, style = MaterialTheme.typography.labelLarge, color = deltaTone?.let { tokenColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant)
                e.string("caption")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                (e.props["trend"] as? List<*>)?.mapNotNull(::num)?.takeIf { it.size > 1 }?.let { Sparkline(it, tokenColor(deltaTone ?: "accent") ?: MaterialTheme.colorScheme.primary, 28.dp, e.key + "#trend") }
            }
        }
    }
    GenUiRegistry.register("facts") { e ->
        val items = (e.props["items"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val columns = (e.number("columns") ?: 2.0).toInt().coerceIn(1, 4)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    row.forEach { item ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).fillMaxHeight()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(str(item["label"]), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(str(item["value"]), style = MaterialTheme.typography.titleMedium)
                                item["note"]?.let { Text(str(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    GenUiRegistry.register("timeline") { e ->
        val events = e.children.filterIsInstance<UiElement>()
        Column(Modifier.fillMaxWidth()) {
            events.forEachIndexed { i, ev ->
                val (_, color) = tone(ev.string("tone") ?: "accent")
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(20.dp)) {
                        Box(Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape).background(color))
                        if (i < events.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                    Column(Modifier.padding(start = 10.dp, bottom = 14.dp).weight(1f)) {
                        ev.string("time")?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        ev.string("title")?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                        if (ev.children.isNotEmpty()) GenBlocks(ev.children, 4.dp)
                    }
                }
            }
        }
    }
    GenUiRegistry.register("steps") { e ->
        val steps = e.children.filterIsInstance<UiElement>()
        var done by rememberSaveable(e.key) { mutableStateOf(steps.mapIndexedNotNull { i, s -> i.takeIf { s.string("status") == "done" } }.toSet()) }
        val accent = tokenColor("accent") ?: MaterialTheme.colorScheme.primary
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEachIndexed { i, s ->
                val finished = i in done
                Row(Modifier.fillMaxWidth().clickable { done = if (finished) done - i else done + i }, verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(if (finished) accent else accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                        Text(if (finished) "✓" else "${i + 1}", style = MaterialTheme.typography.labelLarge, color = if (finished) MaterialTheme.colorScheme.onPrimary else accent)
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        s.string("title")?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface) }
                        if (s.children.isNotEmpty()) GenBlocks(s.children, 4.dp)
                    }
                }
            }
        }
    }
    GenUiRegistry.register("checklist") { e -> Column { e.children.forEach { child -> androidx.compose.runtime.key(child.key) { GenNode(child) } } } }
    GenUiRegistry.register("check") { e ->
        val context = LocalGenUi.current
        var checked by rememberSaveable(e.key) { mutableStateOf(e.bool("checked") == true) }
        Row(Modifier.fillMaxWidth().clickable { checked = !checked; e.callback("onChange")?.let { context.dispatch(it, listOf(checked)) } }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { checked = it; e.callback("onChange")?.let { cb -> context.dispatch(cb, listOf(it)) } })
            Text(e.string("label") ?: plain(e.children), style = MaterialTheme.typography.bodyLarge, color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (checked) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
        }
    }
    GenUiRegistry.register("weather") { e ->
        val days = (e.props["days"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        GenSurface(e, if (LocalFridayPalette.current.dark) Color(0xFF1F2733) else Color(0xFFEAF2FC), MaterialTheme.colorScheme.onSurface, title = null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.string("place").orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text(e.string("condition").orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOfNotNull(e.props["high"]?.let { "H ${str(it)}" }, e.props["low"]?.let { "L ${str(it)}" }).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString("  "), style = MaterialTheme.typography.bodySmall) }
                }
                Text((GenUiTokens.emoji(e.string("icon") ?: e.string("condition")?.lowercase()?.split(' ')?.lastOrNull()) ?: "") + " " + str(e.props["temperature"]), style = MaterialTheme.typography.displaySmall)
            }
            if (days.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                days.forEach { d ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(str(d["day"]), style = MaterialTheme.typography.labelMedium)
                        Text(GenUiTokens.emoji(str(d["icon"] ?: d["condition"]).lowercase().split(' ').lastOrNull()) ?: "·", fontSize = 20.sp)
                        Text(str(d["high"]), style = MaterialTheme.typography.labelLarge)
                        Text(str(d["low"]), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    GenUiRegistry.register("map") { e ->
        val context = androidx.compose.ui.platform.LocalUriHandler.current
        val markers = (e.props["markers"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val query = e.string("query") ?: markers.firstOrNull()?.let { str(it["label"]) }
        fun open(lat: Double?, lng: Double?, label: String?) {
            val uri = if (lat != null && lng != null) mapUri(lat, lng, label ?: query.orEmpty()) else mapUri(null, null, label ?: query.orEmpty())
            runCatching { context.openUri(uri) }
        }
        GenSurface(e, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, title = null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open(e.number("lat"), e.number("lng"), query) }) {
                GenIcon("place", 28.dp, tokenColor("danger") ?: Color.Red)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(query ?: "Location", style = MaterialTheme.typography.titleSmall)
                    if (e.number("lat") != null) Text(formatUiDecimal(e.number("lat") ?: 0.0, 4, false) + ", " + formatUiDecimal(e.number("lng") ?: 0.0, 4, false), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Open in Maps", style = MaterialTheme.typography.labelLarge, color = tokenColor("accent") ?: MaterialTheme.colorScheme.primary)
            }
            markers.drop(if (e.string("query") == null) 1 else 0).forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { open(num(m["lat"]), num(m["lng"]), str(m["label"])) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    GenIcon("place", 18.dp)
                    Text("  " + str(m["label"]), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    GenUiRegistry.register("timer") { e -> Countdown(e, (e.number("seconds") ?: 60.0).toLong(), null) }
    GenUiRegistry.register("countdown") { e ->
        val target = e.string("to")?.let { PlatformTime.parse(it).takeIf(Double::isFinite)?.toLong() }
        if (target == null) Text("Countdown: no date") else Countdown(e, null, target)
    }
    GenUiRegistry.register("badge") { e ->
        val (bg, fg) = tone(e.string("tone"))
        val solid = e.string("variant") == "solid"
        Row(Modifier.clip(RoundedCornerShape(50)).background(if (solid) fg else bg).padding(horizontal = 9.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            GenIcon(e.string("icon"), 14.dp, if (solid) MaterialTheme.colorScheme.surface else fg)
            val (text, inline) = inlineText(e.children, fg)
            Text(text, inlineContent = inline, style = MaterialTheme.typography.labelMedium, color = if (solid) MaterialTheme.colorScheme.surface else fg)
        }
    }
    GenUiRegistry.register("kbd") { e ->
        Text(plain(e.children), style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 6.dp, vertical = 2.dp))
    }
    GenUiRegistry.register("icon") { e -> GenIcon(e.string("name"), when (e.string("size")) { "xs" -> 14.dp; "sm" -> 18.dp; "lg" -> 28.dp; "xl" -> 36.dp; "2xl", "3xl" -> 48.dp; else -> 22.dp }, tokenColor(e.props["color"]) ?: MaterialTheme.colorScheme.onSurfaceVariant) }
    GenUiRegistry.register("avatar") { e ->
        val size = when (e.string("size")) { "sm", "xs" -> 28.dp; "lg" -> 52.dp; "xl", "2xl", "3xl" -> 72.dp; else -> 40.dp }
        val src = e.string("src")
        if (src != null) GenImage(src, Modifier.size(size).clip(CircleShape), contentDescription = e.string("name"))
        else Box(Modifier.size(size).clip(CircleShape).background(tokenColor("accent")?.copy(alpha = .2f) ?: MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Text(e.string("name").orEmpty().split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }, style = MaterialTheme.typography.titleSmall)
        }
    }
    GenUiRegistry.register("codeblock") { e ->
        val clipboard = LocalClipboardManager.current
        val code = e.string("code") ?: plain(e.children)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.string("title") ?: e.string("language") ?: "code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy") }
                }
                Text(code, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
            }
        }
    }
    GenUiRegistry.register("markdown") { e -> com.localfirst.assistant.ui.NativeResponseContent(e.string("content").orEmpty()) }
    GenUiRegistry.register("empty") { e ->
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GenIcon(e.string("icon") ?: "info", 32.dp)
            e.string("title")?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            e.string("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        }
    }
    GenUiRegistry.register("skeleton") { e ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat((e.number("lines") ?: 3.0).toInt().coerceIn(1, 8)) { i -> Box(Modifier.fillMaxWidth(if (i % 3 == 2) .6f else 1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) }
        }
    }
    GenUiRegistry.register("table") { e -> TableView(e) }
    GenUiRegistry.register("tr", "th", "td") { e -> GenText(UiElement("text", e.key, emptyMap(), e.children), MaterialTheme.typography.bodyMedium) }
    GenUiRegistry.register("event", "step") { e -> GenBlocks(e.children) }
}
