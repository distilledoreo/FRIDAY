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
