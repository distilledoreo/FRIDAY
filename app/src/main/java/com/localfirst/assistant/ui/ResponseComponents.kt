package com.localfirst.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.presentation.UiBlock
import com.localfirst.assistant.presentation.UiBlocks
import com.localfirst.assistant.ui.theme.LocalFridayPalette

/** Draws one of FRIDAY's components. [onChoose] sends a choice as the user's reply; null disables choices. */
@Composable
internal fun FridayComponent(block: UiBlock, key: String, onChoose: ((String) -> Unit)?) {
    when (block) {
        is UiBlock.Compare -> CompareCards(block)
        is UiBlock.Steps -> StepsCard(block, key)
        is UiBlock.Checklist -> ChecklistCard(block, key)
        is UiBlock.Choices -> ChoiceChips(block, onChoose)
        is UiBlock.Facts -> FactTiles(block)
        is UiBlock.Timeline -> TimelineCard(block)
        is UiBlock.Weather -> WeatherCard(block)
        is UiBlock.Links -> LinkCards(block)
        is UiBlock.Calculator -> CalculatorCard(block, key, onChoose)
        is UiBlock.Chart -> ChartCard(block)
        is UiBlock.Form -> FormCard(block, key, onChoose)
    }
}

/** A component that couldn't be read, shown as quiet text so nothing is lost. */
@Composable
internal fun ComponentFallback(raw: String) {
    Text(raw, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** While a component streams in. */
@Composable
internal fun ComponentPending() {
    ComponentCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalFridayPalette.current.accent)
            Spacer(Modifier.width(12.dp))
            Text("Putting this together…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComponentCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dark = LocalFridayPalette.current.dark
    Surface(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = if (dark) Color(0xFF1F1F22) else Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (dark) .35f else .5f)),
    ) { Column(content = content) }
}

@Composable
private fun CardTitle(title: String?) {
    title?.let { Text(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall) }
}

@Composable
private fun CompareCards(block: UiBlock.Compare) {
    val uri = LocalUriHandler.current
    val accent = LocalFridayPalette.current.accent
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        Row(Modifier.horizontalScroll(rememberScrollState()).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            block.items.forEach { option ->
                val link = option.url
                ComponentCard(Modifier.width(220.dp).fillMaxHeight().then(if (link != null) Modifier.clip(RoundedCornerShape(16.dp)).clickable { uri.openUri(link) } else Modifier)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        option.badge?.let {
                            Text(it, Modifier.clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = .16f)).padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall, color = com.localfirst.assistant.ui.theme.readableAccent(accent, MaterialTheme.colorScheme.surface))
                        }
                        Text(option.title, style = MaterialTheme.typography.titleMedium)
                        option.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        option.points.forEach { point ->
                            Row { Text("•", Modifier.width(14.dp), color = accent); Text(point, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepsCard(block: UiBlock.Steps, key: String) {
    val accent = LocalFridayPalette.current.accent
    var done by rememberSaveable(key) { mutableStateOf(setOf<Int>()) }
    ComponentCard {
        CardTitle(block.title)
        block.items.forEachIndexed { i, step ->
            val finished = i in done
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { done = if (finished) done - i else done + i }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(if (finished) accent else accent.copy(alpha = .14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (finished) Icon(Icons.Filled.Check, "Done", Modifier.size(16.dp), tint = Color.White)
                    else Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = com.localfirst.assistant.ui.theme.readableAccent(accent, MaterialTheme.colorScheme.surface))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = MaterialTheme.typography.bodyLarge, textDecoration = if (finished) TextDecoration.LineThrough else null,
                        color = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    step.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ChecklistCard(block: UiBlock.Checklist, key: String) {
    var checked by rememberSaveable(key) { mutableStateOf(block.items.mapIndexedNotNull { i, (_, c) -> i.takeIf { c } }.toSet()) }
    ComponentCard {
        CardTitle(block.title)
        block.items.forEachIndexed { i, (text, _) ->
            val on = i in checked
            Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { checked = if (on) checked - i else checked + i }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { checked = if (on) checked - i else checked + i }, colors = CheckboxDefaults.colors(checkedColor = LocalFridayPalette.current.accent))
                Text(text, style = MaterialTheme.typography.bodyLarge, textDecoration = if (on) TextDecoration.LineThrough else null,
                    color = if (on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(block: UiBlock.Choices, onChoose: ((String) -> Unit)?) {
    val accent = LocalFridayPalette.current.accent
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.prompt?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.options.forEach { option ->
                Surface(
                    onClick = { onChoose?.invoke(option) }, enabled = onChoose != null, shape = RoundedCornerShape(20.dp),
                    color = accent.copy(alpha = if (onChoose != null) .14f else .06f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .35f)),
                ) {
                    Text(option, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyMedium,
                        color = if (onChoose != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FactTiles(block: UiBlock.Facts) {
    val dark = LocalFridayPalette.current.dark
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        block.items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                row.forEach { fact ->
                    Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(14.dp), color = if (dark) Color(0xFF232326) else Color(0xFFF0EEEA)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(fact.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(fact.value, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            fact.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TimelineCard(block: UiBlock.Timeline) {
    val accent = LocalFridayPalette.current.accent
    ComponentCard {
        CardTitle(block.title)
        block.items.forEachIndexed { i, moment ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Text(moment.time, Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.padding(top = 6.dp, end = 14.dp).size(8.dp).clip(CircleShape).background(listOf(accent, Color(0xFF6F9BDE), Color(0xFF6DBE8B), Color(0xFFD9B26A))[i % 4]))
                Column(Modifier.weight(1f)) {
                    Text(moment.title, style = MaterialTheme.typography.bodyLarge)
                    moment.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun WeatherCard(block: UiBlock.Weather) {
    ComponentCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(block.place, style = MaterialTheme.typography.titleMedium)
                block.condition?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            block.temperature?.let { Text(it, style = MaterialTheme.typography.displaySmall) }
        }
        if (block.days.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                block.days.take(5).forEach { day ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day.day, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(day.high ?: "–", style = MaterialTheme.typography.bodyLarge)
                        day.low?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkCards(block: UiBlock.Links) {
    val uri = LocalUriHandler.current
    ComponentCard {
        CardTitle(block.title)
        block.items.forEach { link ->
            Row(Modifier.fillMaxWidth().clickable { uri.openUri(link.url) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(link.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(link.note ?: link.url.removePrefix("https://").substringBefore('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** A live calculator: sliders adjust the variables, the result recomputes on the phone. */
@Composable
private fun CalculatorCard(block: UiBlock.Calculator, key: String, onChoose: ((String) -> Unit)?) {
    val accent = LocalFridayPalette.current.accent
    var values by rememberSaveable(key) { mutableStateOf(block.vars.map { it.default }) }
    val inputs = remember(values) { block.vars.mapIndexed { i, v -> v.name to values[i] }.toMap() }
    val result = remember(block.formula, inputs) { UiBlocks.FormulaEval.check(block.formula, inputs) }
    ComponentCard {
        CardTitle(block.title)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(block.formula, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            block.vars.forEachIndexed { i, v ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(v.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(UiBlocks.formatNumber(values[i]), style = MaterialTheme.typography.bodyMedium, color = com.localfirst.assistant.ui.theme.readableAccent(accent, MaterialTheme.colorScheme.surface))
                }
                Slider(
                    value = values[i].toFloat().coerceIn(v.min.toFloat(), v.max.toFloat()),
                    onValueChange = { raw ->
                        val stepped = (kotlin.math.round((raw - v.min.toFloat()) / v.step.toFloat()) * v.step.toFloat() + v.min.toFloat()).toDouble().coerceIn(v.min, v.max)
                        values = values.toMutableList().apply { this[i] = stepped }
                    },
                    valueRange = v.min.toFloat()..v.max.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(block.result ?: "Result", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(
                    result?.let { UiBlocks.formatNumber(it) } ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (result == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (onChoose != null && result != null) {
                TextButton(
                    shape = MaterialTheme.shapes.small,
                    onClick = { onChoose("${block.result ?: "Result"}: ${UiBlocks.formatNumber(result)}") },
                ) { Text("Send result") }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

private val chartColors
    @Composable get() = listOf(
        LocalFridayPalette.current.accent,
        androidx.compose.ui.graphics.Color(0xFF6F9BDE),
        androidx.compose.ui.graphics.Color(0xFF6DBE8B),
    )

/** A small bar or line chart drawn from the model's numbers; no interaction beyond reading it. */
@Composable
private fun ChartCard(block: UiBlock.Chart) {
    val colors = chartColors
    val peak = block.series.flatMap { it.values }.map { kotlin.math.abs(it) }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val hasNegative = block.series.any { s -> s.values.any { it < 0 } }
    val labels = block.labels.ifEmpty { block.series.first().values.indices.map { "${it + 1}" } }
    ComponentCard {
        CardTitle(block.title)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                block.series.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(colors[i % colors.size]) }
                        Spacer(Modifier.width(6.dp))
                        Text(s.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                val barGroup = size.width / labels.size
                // Zero sits mid-chart when negatives exist, at the bottom otherwise.
                fun y(v: Double) = if (hasNegative) (size.height * (1 - (v / peak * 0.5 + 0.5) * 0.9)).toFloat()
                    else (size.height * (1 - v / peak * 0.9)).toFloat()
                val base = y(0.0)
                if (block.kind == "line") {
                    block.series.forEachIndexed { si, s ->
                        val color = colors[si % colors.size]
                        val points = s.values.mapIndexed { i, v -> androidx.compose.ui.geometry.Offset(barGroup * (i + 0.5f), y(v)) }
                        for (i in 0 until points.size - 1) drawLine(color, points[i], points[i + 1], strokeWidth = 5f)
                        points.forEach { drawCircle(color, 9f, it) }
                    }
                } else {
                    val count = block.series.size
                    block.series.first().values.indices.forEach { i ->
                        block.series.forEachIndexed { si, s ->
                            val width = barGroup / count * 0.7f
                            val left = (barGroup * i + barGroup / count * si + barGroup / count * 0.15f).toFloat()
                            val top = y(s.values[i])
                            drawRect(
                                color = colors[si % colors.size],
                                topLeft = androidx.compose.ui.geometry.Offset(left, minOf(top, base)),
                                size = androidx.compose.ui.geometry.Size(width, kotlin.math.abs(top - base).coerceAtLeast(2f)),
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                labels.forEach { label ->
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

/** Short inputs the user fills in and sends back as one reply. */
@Composable
private fun FormCard(block: UiBlock.Form, key: String, onChoose: ((String) -> Unit)?) {
    var texts by rememberSaveable(key) { mutableStateOf(List(block.fields.size) { "" }) }
    var picked by rememberSaveable(key + "-choice") { mutableStateOf(List(block.fields.size) { -1 }) }
    val accent = LocalFridayPalette.current.accent
    fun answer(i: Int): String? {
        val field = block.fields[i]
        return when (field.kind) {
            "choice" -> picked[i].takeIf { it >= 0 }?.let { field.options[it] }
            "number" -> texts[i].trim().takeIf { it.toDoubleOrNull() != null }
            else -> texts[i].trim().takeIf(String::isNotEmpty)
        }
    }
    val ready = onChoose != null && block.fields.indices.all { answer(it) != null }
    ComponentCard {
        CardTitle(block.title)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            block.prompt?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            block.fields.forEachIndexed { i, field ->
                when (field.kind) {
                    "choice" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            field.options.forEachIndexed { oi, option ->
                                val selected = picked[i] == oi
                                Surface(
                                    onClick = { picked = picked.toMutableList().apply { this[i] = oi } }, shape = RoundedCornerShape(16.dp),
                                    color = if (selected) accent.copy(alpha = .2f) else accent.copy(alpha = .07f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = if (selected) .7f else .3f)),
                                ) {
                                    Text(option, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    else -> OutlinedTextField(
                        value = texts[i],
                        onValueChange = { texts = texts.toMutableList().apply { this[i] = it.take(500) } },
                        label = { Text(field.label) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = if (field.kind == "number") androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Button(
                onClick = {
                    val lines = block.fields.mapIndexed { i, field -> "${field.label}: ${answer(i)}" }
                    onChoose?.invoke(listOfNotNull(block.prompt, lines.joinToString("\n")).joinToString("\n"))
                },
                enabled = ready,
                shape = MaterialTheme.shapes.small,
            ) { Text(block.submit ?: "Send") }
        }
    }
}
