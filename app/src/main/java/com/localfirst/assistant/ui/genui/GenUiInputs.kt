package com.localfirst.assistant.ui.genui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.localfirst.assistant.genui.UiAction
import com.localfirst.assistant.genui.UiElement
import com.localfirst.assistant.genui.UiFormat
import com.localfirst.assistant.genui.script.Values
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** Named fields of the enclosing <form>, and how to submit it. */
internal class GenForm(val values: SnapshotStateMap<String, Any?>, val labels: MutableMap<String, String>, val submit: () -> Unit)

internal val LocalGenForm = compositionLocalOf<GenForm?> { null }

/**
 * A field's value. Controlled when the response passes `value` (or `checked`) and handles onChange:
 * the shown value follows the runtime's state. Otherwise the field keeps its own value. Either way
 * changes go to onChange and to the enclosing form under the field's name.
 */
@Composable
private fun <T> rememberField(e: UiElement, initial: T, read: (Any?) -> T?): Pair<T, (T) -> Unit> {
    val context = LocalGenUi.current
    val form = LocalGenForm.current
    val controlled = read(e.props["value"] ?: e.props["checked"])
    var local by remember(e.key) { mutableStateOf(controlled ?: read(e.props["defaultValue"]) ?: initial) }
    LaunchedEffect(controlled) { if (controlled != null) local = controlled }
    val name = e.string("name")
    if (name != null && form != null) SideEffect {
        if (form.values[name] != local) form.values[name] = local
        e.string("label")?.let { form.labels[name] = it }
    }
    return local to { value: T ->
        local = value
        if (name != null) form?.values?.set(name, value)
        e.callback("onChange")?.let { context.dispatch(it, listOf(value)) }
    }
}

private fun asNumber(v: Any?): Double? = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
private fun asText(v: Any?): String? = when (v) { null -> null; is String -> v; is Number -> UiFormat.number(v.toDouble()); else -> v.toString() }
private fun asBool(v: Any?): Boolean? = when (v) { is Boolean -> v; is String -> v == "true"; is Number -> v.toDouble() != 0.0; else -> null }

/** Options as (label, value) pairs: strings, or objects with label/value. */
private fun options(e: UiElement): List<Pair<String, String>> = (e.props["options"] as? List<*>).orEmpty().mapNotNull { o ->
    when (o) {
        is Map<*, *> -> { val value = asText(o["value"] ?: o["label"]) ?: return@mapNotNull null; (asText(o["label"]) ?: value) to value }
        null -> null
        else -> asText(o)?.let { it to it }
    }
}

@Composable
private fun FieldLabel(e: UiElement, trailing: String? = null) {
    val label = e.string("label") ?: return
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = tokenColor("accent") ?: MaterialTheme.colorScheme.primary) }
    }
}

private fun formatValue(e: UiElement, v: Double): String {
    val text = when (e.string("format")) {
        "currency" -> "$" + "%,.2f".format(v).removeSuffix(".00")
        "percent" -> UiFormat.number(v) + "%"
        else -> if (v == Math.floor(v)) "%,d".format(v.toLong()) else UiFormat.number(v)
    }
    return text + (e.string("unit")?.let { " $it" } ?: "")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
internal fun registerInputs() {
    GenUiRegistry.register("button") { e ->
        val context = LocalGenUi.current
        val form = LocalGenForm.current
        val onClick: () -> Unit = {
            when {
                e.bool("submit") == true && form != null -> form.submit()
                e.callback("onClick") != null -> context.dispatch(e.callback("onClick")!!, emptyList())
                e.string("href") != null -> context.act(UiAction.OpenUrl(e.string("href")!!))
                form != null -> form.submit()
            }
        }
        val label: @Composable () -> Unit = {
            GenIcon(e.string("icon"), 18.dp, androidx.compose.material3.LocalContentColor.current)
            if (e.string("icon") != null && e.children.isNotEmpty()) Spacer(Modifier.width(6.dp))
            val (text, inline) = inlineText(e.children, androidx.compose.material3.LocalContentColor.current)
            Text(text, inlineContent = inline, maxLines = 2)
            if (e.string("iconRight") != null) { Spacer(Modifier.width(6.dp)); GenIcon(e.string("iconRight"), 18.dp, androidx.compose.material3.LocalContentColor.current) }
        }
        val modifier = if (e.bool("block") == true) Modifier.fillMaxWidth() else Modifier
        val enabled = e.bool("disabled") != true
        val (toneBg, toneFg) = tone(e.string("tone"))
        when (e.string("variant")) {
            "secondary" -> Button(onClick, modifier, enabled, shape = MaterialTheme.shapes.small, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)) { label() }
            "outline" -> OutlinedButton(onClick, modifier, enabled, shape = MaterialTheme.shapes.small) { label() }
            "ghost", "link" -> TextButton(onClick, modifier, enabled, shape = MaterialTheme.shapes.small) { label() }
            "danger" -> Button(onClick, modifier, enabled, shape = MaterialTheme.shapes.small, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { label() }
            else -> if (e.string("tone") != null && e.string("tone") != "accent") Button(onClick, modifier, enabled, shape = MaterialTheme.shapes.small, colors = ButtonDefaults.buttonColors(containerColor = toneBg, contentColor = toneFg)) { label() }
                else Button(onClick, modifier, enabled, shape = MaterialTheme.shapes.small) { label() }
        }
    }

    GenUiRegistry.register("slider") { e ->
        val min = e.number("min") ?: 0.0
        val max = (e.number("max") ?: 100.0).let { if (it <= min) min + 1 else it }
        val step = e.number("step")?.takeIf { it > 0 } ?: if (max - min <= 200) 1.0 else (max - min) / 100
        val (value, set) = rememberField(e, min, ::asNumber)
        Column(Modifier.fillMaxWidth()) {
            FieldLabel(e, if (e.bool("showValue") != false) formatValue(e, value) else null)
            val steps = ((max - min) / step).roundToInt() - 1
            Slider(
                value = value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
                onValueChange = { raw -> set((min + ((raw - min) / step).roundToInt() * step).coerceIn(min, max)) },
                valueRange = min.toFloat()..max.toFloat(),
                // Tick marks only for a few coarse steps; finer sliders still snap to step.
                steps = if (steps in 1..10) steps else 0,
                enabled = e.bool("disabled") != true,
            )
            e.string("helper")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    GenUiRegistry.register("stepper") { e ->
        val min = e.number("min") ?: Double.NEGATIVE_INFINITY
        val max = e.number("max") ?: Double.POSITIVE_INFINITY
        val step = e.number("step") ?: 1.0
        val (value, set) = rememberField(e, if (min.isFinite()) min else 0.0, ::asNumber)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(e.string("label") ?: "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            OutlinedIconButton(onClick = { set((value - step).coerceIn(min, max)) }, enabled = value - step >= min, modifier = Modifier.size(40.dp)) { Text("−") }
            Text(formatValue(e, value), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 14.dp))
            OutlinedIconButton(onClick = { set((value + step).coerceIn(min, max)) }, enabled = value + step <= max, modifier = Modifier.size(40.dp)) { Text("+") }
        }
    }

    GenUiRegistry.register("toggle") { e ->
        val (on, set) = rememberField(e, false, ::asBool)
        Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { set(!on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.string("label") ?: "", style = MaterialTheme.typography.bodyLarge)
                e.string("helper")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Switch(checked = on, onCheckedChange = { set(it) }, enabled = e.bool("disabled") != true)
        }
    }

    GenUiRegistry.register("checkbox") { e ->
        val (on, set) = rememberField(e, false, ::asBool)
        Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { set(!on) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = on, onCheckedChange = { set(it) }, enabled = e.bool("disabled") != true)
            Text(e.string("label") ?: "", style = MaterialTheme.typography.bodyLarge)
        }
    }

    GenUiRegistry.register("radiogroup") { e ->
        val opts = options(e)
        val (value, set) = rememberField(e, opts.firstOrNull()?.second ?: "", ::asText)
        Column(Modifier.fillMaxWidth()) {
            FieldLabel(e)
            if (e.string("direction") == "row") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                opts.forEach { (label, v) -> Row(Modifier.clickable(role = Role.RadioButton) { set(v) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(value == v, { set(v) }); Text(label) } }
            } else opts.forEach { (label, v) ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { set(v) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(value == v, { set(v) }); Text(label, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }

    GenUiRegistry.register("select") { e ->
        val opts = options(e)
        val (value, set) = rememberField(e, "", ::asText)
        var expanded by remember(e.key) { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded, { expanded = it }, Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = opts.firstOrNull { it.second == value }?.first ?: value,
                onValueChange = {},
                readOnly = true,
                label = e.string("label")?.let { { Text(it) } },
                placeholder = e.string("placeholder")?.let { { Text(it) } },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded, { expanded = false }) {
                opts.forEach { (label, v) -> DropdownMenuItem(text = { Text(label) }, onClick = { set(v); expanded = false }) }
            }
        }
    }

    GenUiRegistry.register("segmented") { e ->
        val opts = options(e)
        val (value, set) = rememberField(e, opts.firstOrNull()?.second ?: "", ::asText)
        Column(Modifier.fillMaxWidth()) {
            FieldLabel(e)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                opts.forEachIndexed { i, (label, v) ->
                    SegmentedButton(selected = value == v, onClick = { set(v) }, shape = SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(label, maxLines = 1) }
                }
            }
        }
    }

    GenUiRegistry.register("chips") { e ->
        val opts = options(e)
        val multiple = e.bool("multiple") == true
        val (value, set) = rememberField<Any?>(e, if (multiple) emptyList<String>() else null) { v -> v }
        val selected: List<String> = when (value) { is List<*> -> value.mapNotNull(::asText); null -> emptyList(); else -> listOfNotNull(asText(value)) }
        Column(Modifier.fillMaxWidth()) {
            FieldLabel(e)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                opts.forEach { (label, v) ->
                    val on = v in selected
                    FilterChip(selected = on, onClick = {
                        if (multiple) set(if (on) selected - v else selected + v) else set(if (on) null else v)
                    }, label = { Text(label) })
                }
            }
        }
    }

    GenUiRegistry.register("chip") { e ->
        val context = LocalGenUi.current
        val (text, inline) = inlineText(e.children, MaterialTheme.colorScheme.primary)
        FilterChip(selected = e.bool("selected") == true, onClick = { e.callback("onClick")?.let { context.dispatch(it, emptyList()) } },
            label = { Text(text, inlineContent = inline) }, leadingIcon = e.string("icon")?.let { { GenIcon(it, 18.dp) } })
    }

    GenUiRegistry.register("input") { e ->
        val (value, set) = rememberField(e, "", ::asText)
        val type = e.string("type") ?: "text"
        OutlinedTextField(
            value = value,
            onValueChange = { set(if (type == "number") it.filter { c -> c.isDigit() || c == '.' || c == '-' } else it) },
            label = e.string("label")?.let { { Text(it) } },
            placeholder = e.string("placeholder")?.let { { Text(it) } },
            prefix = e.string("prefix")?.let { { Text(it) } },
            suffix = e.string("suffix")?.let { { Text(it) } },
            supportingText = e.string("helper")?.let { { Text(it) } },
            singleLine = true,
            enabled = e.bool("disabled") != true,
            keyboardOptions = KeyboardOptions(keyboardType = when (type) { "number" -> KeyboardType.Decimal; "email" -> KeyboardType.Email; "tel" -> KeyboardType.Phone; "url" -> KeyboardType.Uri; else -> KeyboardType.Text }),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    GenUiRegistry.register("textarea") { e ->
        val (value, set) = rememberField(e, "", ::asText)
        OutlinedTextField(value, { set(it) }, Modifier.fillMaxWidth(), label = e.string("label")?.let { { Text(it) } },
            placeholder = e.string("placeholder")?.let { { Text(it) } }, minLines = (e.number("rows") ?: 3.0).toInt().coerceIn(2, 12), enabled = e.bool("disabled") != true)
    }

    GenUiRegistry.register("date") { e ->
        val (value, set) = rememberField(e, "", ::asText)
        var open by remember(e.key) { mutableStateOf(false) }
        OutlinedButton(onClick = { open = true }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Text(listOfNotNull(e.string("label"), value.ifEmpty { e.string("placeholder") ?: "Pick a date" }).joinToString(": "))
        }
        if (open) {
            val initial = runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
            val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
            DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { set(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("OK") }
            }, dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }) { DatePicker(picker) }
        }
    }

    GenUiRegistry.register("time") { e ->
        val (value, set) = rememberField(e, "", ::asText)
        var open by remember(e.key) { mutableStateOf(false) }
        OutlinedButton(onClick = { open = true }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Text(listOfNotNull(e.string("label"), value.ifEmpty { "Pick a time" }).joinToString(": "))
        }
        if (open) {
            val parts = value.split(':').mapNotNull { it.toIntOrNull() }
            val picker = rememberTimePickerState(parts.getOrElse(0) { 9 }, parts.getOrElse(1) { 0 }, is24Hour = false)
            Dialog(onDismissRequest = { open = false }) {
                androidx.compose.material3.Surface(shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.End) {
                        TimePicker(picker)
                        TextButton(onClick = { set("%02d:%02d".format(picker.hour, picker.minute)); open = false }) { Text("OK") }
                    }
                }
            }
        }
    }

    GenUiRegistry.register("rating") { e ->
        val max = (e.number("max") ?: 5.0).toInt().coerceIn(1, 10)
        val (value, set) = rememberField(e, 0.0, ::asNumber)
        val readOnly = e.bool("readOnly") == true
        Column {
            FieldLabel(e)
            Row {
                for (i in 1..max) {
                    Text(if (i <= value) "★" else "☆", style = MaterialTheme.typography.headlineSmall, color = tokenColor("amber") ?: MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 2.dp).let { if (readOnly) it else it.clickable { set(i.toDouble()) } })
                }
            }
        }
    }

    GenUiRegistry.register("form") { e ->
        val context = LocalGenUi.current
        val values = remember(e.key) { mutableStateMapOf<String, Any?>() }
        val labels = remember(e.key) { mutableMapOf<String, String>() }
        val form = remember(e.key, e.callback("onSubmit")) {
            GenForm(values, labels) {
                val data = values.toMap()
                val callback = e.callback("onSubmit")
                if (callback != null) context.dispatch(callback, listOf(data))
                else {
                    // Without a handler, the form's values become the user's reply, as in ChatGPT.
                    val lines = data.entries.joinToString("\n") { (k, v) -> "- ${labels[k] ?: k}: ${Values.toStr(Values.fromKotlin(v)).ifEmpty { "—" }}" }
                    context.act(UiAction.NewTurn(listOfNotNull(e.string("submitLabel"), lines).joinToString("\n")))
                }
            }
        }
        CompositionLocalProvider(LocalGenForm provides form) {
            Column(Modifier.layout(e).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GenUiTokens.spacing(e.props["gap"] ?: 3))) {
                GenBlocks(e.children, GenUiTokens.spacing(e.props["gap"] ?: 3))
                if (!hasButton(e.children)) {
                    Button(onClick = form.submit, shape = MaterialTheme.shapes.small) { Text(e.string("submitLabel") ?: "Send") }
                }
            }
        }
    }
}

/** Whether a form already has its own button anywhere inside it. */
private fun hasButton(nodes: List<com.localfirst.assistant.genui.UiNode>): Boolean =
    nodes.any { it is UiElement && (it.type == "button" || hasButton(it.children)) }
