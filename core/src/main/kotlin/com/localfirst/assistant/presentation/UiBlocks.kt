package com.localfirst.assistant.presentation

import com.localfirst.assistant.json.JsonCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * FRIDAY's native components. The model may put one JSON object in a ```friday fenced block;
 * only these types and fields are drawn, anything else falls back to text. Components never run
 * code: the only interactions are ticking items locally, moving sliders, typing into fields and
 * sending a choice or a form as the user's reply. Calculator formulas use a small arithmetic
 * evaluator below, not a scripting language.
 */
sealed interface UiBlock {
    data class Option(val title: String, val subtitle: String?, val badge: String?, val points: List<String>, val url: String?)
    data class Compare(val title: String?, val items: List<Option>) : UiBlock
    data class Step(val title: String, val detail: String?)
    data class Steps(val title: String?, val items: List<Step>) : UiBlock
    data class Checklist(val title: String?, val items: List<Pair<String, Boolean>>) : UiBlock
    data class Choices(val prompt: String?, val options: List<String>) : UiBlock
    data class Fact(val label: String, val value: String, val note: String?)
    data class Facts(val title: String?, val items: List<Fact>) : UiBlock
    data class Moment(val time: String, val title: String, val detail: String?)
    data class Timeline(val title: String?, val items: List<Moment>) : UiBlock
    data class Day(val day: String, val high: String?, val low: String?, val condition: String?)
    data class Weather(val place: String, val temperature: String?, val condition: String?, val days: List<Day>) : UiBlock
    data class Link(val title: String, val url: String, val note: String?)
    data class Links(val title: String?, val items: List<Link>) : UiBlock
    data class CalcVar(val name: String, val label: String, val min: Double, val max: Double, val step: Double, val default: Double)
    data class Calculator(val title: String?, val vars: List<CalcVar>, val formula: String, val result: String?) : UiBlock
    data class Series(val name: String, val values: List<Double>)
    data class Chart(val title: String?, val kind: String, val labels: List<String>, val series: List<Series>) : UiBlock
    data class Field(val id: String, val label: String, val kind: String, val options: List<String>)
    data class Form(val title: String?, val prompt: String?, val fields: List<Field>, val submit: String?) : UiBlock
}

/** A reply split into text and components, in order. */
sealed interface UiSegment {
    data class Text(val markdown: String) : UiSegment
    data class Block(val block: UiBlock) : UiSegment
    /** A friday block that isn't usable: shown as plain text. */
    data class Invalid(val raw: String) : UiSegment
    /** A friday block still streaming in. */
    data object Pending : UiSegment
}

object UiBlocks {
    private const val FENCE = "```friday"

    fun contains(markdown: String) = markdown.contains(FENCE)

    fun segments(markdown: String): List<UiSegment> {
        if (!contains(markdown)) return listOf(UiSegment.Text(markdown))
        val out = mutableListOf<UiSegment>()
        var rest = markdown
        while (true) {
            val start = rest.indexOf(FENCE)
            if (start < 0) { if (rest.isNotBlank()) out += UiSegment.Text(rest); break }
            if (rest.substring(0, start).isNotBlank()) out += UiSegment.Text(rest.substring(0, start))
            val bodyStart = rest.indexOf('\n', start).takeIf { it >= 0 } ?: run { out += UiSegment.Pending; return out }
            val end = rest.indexOf("```", bodyStart + 1)
            if (end < 0) { out += UiSegment.Pending; break }
            val raw = rest.substring(bodyStart + 1, end).trim()
            out += parse(raw)?.let { UiSegment.Block(it) } ?: UiSegment.Invalid(raw)
            rest = rest.substring(end + 3)
        }
        return out
    }

    /** The reply as plain text: components become sentences, an unfinished one is left out. */
    fun plainText(markdown: String): String = segments(markdown).joinToString("\n\n") { segment ->
        when (segment) {
            is UiSegment.Text -> segment.markdown.trim()
            is UiSegment.Block -> describe(segment.block)
            is UiSegment.Invalid -> ""
            UiSegment.Pending -> ""
        }
    }.replace(Regex("\n{3,}"), "\n\n").trim()

    fun parse(raw: String): UiBlock? {
        val obj = runCatching { JsonCodec.json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val title = obj.text("title", 120)
        return when (obj.text("type", 20)?.lowercase()) {
            "compare", "cards" -> obj.list("items", 4) { o ->
                val t = o.text("title", 80) ?: return@list null
                UiBlock.Option(t, o.text("subtitle", 120), o.text("badge", 30), o.strings("points", 5, 140), o.text("url", 500)?.takeIf(::safeUrl))
            }.takeIf { it.size >= 2 }?.let { UiBlock.Compare(title, it) }
            "steps" -> obj.list("items", 10) { o -> o.text("title", 160)?.let { UiBlock.Step(it, o.text("detail", 240)) } }
                .ifEmpty { obj.strings("items", 10, 200).map { UiBlock.Step(it, null) } }
                .takeIf { it.isNotEmpty() }?.let { UiBlock.Steps(title, it) }
            "checklist" -> obj.list("items", 15) { o -> o.text("text", 160)?.let { it to ((o["checked"] as? JsonPrimitive)?.contentOrNull == "true") } }
                .ifEmpty { obj.strings("items", 15, 160).map { it to false } }
                .takeIf { it.isNotEmpty() }?.let { UiBlock.Checklist(title, it) }
            "choices" -> obj.strings("options", 6, 60).takeIf { it.size >= 2 }?.let { UiBlock.Choices(obj.text("prompt", 160), it) }
            "facts", "stats" -> obj.list("items", 6) { o ->
                val label = o.text("label", 40) ?: return@list null
                val value = o.text("value", 30) ?: return@list null
                UiBlock.Fact(label, value, o.text("note", 80))
            }.takeIf { it.isNotEmpty() }?.let { UiBlock.Facts(title, it) }
            "timeline", "schedule" -> obj.list("items", 10) { o ->
                val time = o.text("time", 30) ?: return@list null
                val t = o.text("title", 100) ?: return@list null
                UiBlock.Moment(time, t, o.text("detail", 140))
            }.takeIf { it.isNotEmpty() }?.let { UiBlock.Timeline(title, it) }
            "weather" -> obj.text("place", 60)?.let { place ->
                UiBlock.Weather(place, obj.text("temperature", 12), obj.text("condition", 40), obj.list("days", 7) { o ->
                    o.text("day", 12)?.let { UiBlock.Day(it, o.text("high", 8), o.text("low", 8), o.text("condition", 30)) }
                })
            }
            "links", "sources" -> obj.list("items", 6) { o ->
                val t = o.text("title", 100) ?: return@list null
                val url = o.text("url", 500)?.takeIf(::safeUrl) ?: return@list null
                UiBlock.Link(t, url, o.text("note", 140))
            }.takeIf { it.isNotEmpty() }?.let { UiBlock.Links(title, it) }
            "calculator", "calc" -> parseCalculator(title, obj)
            "chart", "graph" -> parseChart(title, obj)
            "form", "inputs" -> parseForm(title, obj)
            else -> null
        }
    }

    private val varName = Regex("[a-z][a-z0-9_]{0,15}")
    private val formulaChars = Regex("[0-9a-z_+\\-*/^%().,\\s]*")

    private fun parseCalculator(title: String?, obj: JsonObject): UiBlock.Calculator? {
        val formula = obj.text("formula", 200)?.takeIf { it.matches(formulaChars) && it.any(Char::isDigit) } ?: return null
        // Every variable the formula names must be declared; undeclared names reject the block.
        val names = Regex("[a-z][a-z0-9_]*").findAll(formula).map { it.value }.toSet() - FormulaEval.functions
        val vars = obj.list("vars", 6) { o ->
            val name = o.text("name", 16)?.lowercase()?.takeIf { it.matches(varName) } ?: return@list null
            val min = o.number("min") ?: 0.0
            val max = o.number("max") ?: 100.0
            if (!min.isFinite() || !max.isFinite() || min >= max) return@list null
            val step = o.number("step")?.takeIf { it.isFinite() && it > 0 } ?: ((max - min) / 100.0)
            val default = o.number("default")?.takeIf { it.isFinite() }?.coerceIn(min, max) ?: min
            UiBlock.CalcVar(name, o.text("label", 40) ?: name, min, max, step, default)
        }
        if (vars.isEmpty() || vars.map { it.name }.toSet() != names) return null
        if (FormulaEval.check(formula, vars.associate { it.name to it.default }) == null) return null
        return UiBlock.Calculator(title, vars, formula, obj.text("result", 40))
    }

    private fun parseChart(title: String?, obj: JsonObject): UiBlock.Chart? {
        val kind = obj.text("kind", 10)?.lowercase().takeIf { it == "bar" || it == "line" } ?: "bar"
        val labels = obj.strings("labels", 12, 20)
        val series = obj.list("series", 3) { o ->
            val name = o.text("name", 30) ?: return@list null
            val values = o.numbers("values", 24).takeIf { it.isNotEmpty() } ?: return@list null
            UiBlock.Series(name, values)
        }.takeIf { it.isNotEmpty() } ?: return null
        if (labels.isNotEmpty() && series.any { it.values.size != labels.size }) return null
        return UiBlock.Chart(title, kind, labels, series)
    }

    private val fieldId = Regex("[a-z][a-z0-9_]{0,15}")

    private fun parseForm(title: String?, obj: JsonObject): UiBlock.Form? {
        val fields = obj.list("fields", 8) { o ->
            val id = o.text("id", 16)?.lowercase()?.takeIf { it.matches(fieldId) } ?: return@list null
            val label = o.text("label", 40) ?: return@list null
            val kind = o.text("kind", 10)?.lowercase().takeIf { it == "text" || it == "number" || it == "choice" } ?: "text"
            val options = if (kind == "choice") o.strings("options", 6, 60).takeIf { it.size >= 2 } ?: return@list null else emptyList()
            UiBlock.Field(id, label, kind, options)
        }.takeIf { it.isNotEmpty() } ?: return null
        if (fields.map { it.id }.toSet().size != fields.size) return null
        return UiBlock.Form(title, obj.text("prompt", 160), fields, obj.text("submit", 30))
    }

    fun describe(block: UiBlock): String = when (block) {
        is UiBlock.Compare -> listOfNotNull(block.title) .plus(block.items.map { o ->
            listOfNotNull(o.title + (o.badge?.let { " ($it)" } ?: ""), o.subtitle, o.points.joinToString("; ").ifBlank { null }).joinToString(": ")
        }).joinToString("\n")
        is UiBlock.Steps -> listOfNotNull(block.title).plus(block.items.mapIndexed { i, s -> "${i + 1}. ${s.title}" + (s.detail?.let { " — $it" } ?: "") }).joinToString("\n")
        is UiBlock.Checklist -> listOfNotNull(block.title).plus(block.items.map { (text, done) -> (if (done) "Done: " else "To do: ") + text }).joinToString("\n")
        is UiBlock.Choices -> (block.prompt ?: "Options") + ": " + block.options.joinToString(", ")
        is UiBlock.Facts -> listOfNotNull(block.title).plus(block.items.map { "${it.label}: ${it.value}" + (it.note?.let { n -> " ($n)" } ?: "") }).joinToString("\n")
        is UiBlock.Timeline -> listOfNotNull(block.title).plus(block.items.map { "${it.time}: ${it.title}" + (it.detail?.let { d -> ", $d" } ?: "") }).joinToString("\n")
        is UiBlock.Weather -> listOfNotNull(
            listOfNotNull(block.place, block.temperature, block.condition).joinToString(", "),
            block.days.joinToString("; ") { d -> listOfNotNull(d.day, d.condition, d.high?.let { "high $it" }, d.low?.let { "low $it" }).joinToString(" ") }.ifBlank { null },
        ).joinToString("\n")
        is UiBlock.Links -> listOfNotNull(block.title).plus(block.items.map { "${it.title}: ${it.url}" }).joinToString("\n")
        is UiBlock.Calculator -> listOfNotNull(block.title, "Formula: ${block.formula}",
            FormulaEval.check(block.formula, block.vars.associate { it.name to it.default })?.let { "${block.result ?: "Result"}: ${formatNumber(it)}" },
            block.vars.joinToString("; ") { "${it.label} (${formatNumber(it.min)}–${formatNumber(it.max)})" }.ifBlank { null }).joinToString("\n")
        is UiBlock.Chart -> listOfNotNull(block.title).plus(block.series.map { s ->
            val points = if (block.labels.isNotEmpty()) s.values.mapIndexed { i, v -> "${block.labels[i]} $v" } else s.values.map { "$it" }
            "${s.name}: " + points.joinToString(", ")
        }).joinToString("\n")
        is UiBlock.Form -> listOfNotNull(block.title, block.prompt, "Fields: " + block.fields.map { it.label }.joinToString(", ")).joinToString("\n")
    }

    private fun safeUrl(url: String) = url.startsWith("https://") && ' ' !in url

    /** Short display for computed numbers: 42 not 42.0, at most 6 decimals. */
    fun formatNumber(value: Double): String {
        if (!value.isFinite()) return "—"
        val rounded = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
        return if (rounded == kotlin.math.floor(rounded) && kotlin.math.abs(rounded) < 1e15) rounded.toLong().toString() else rounded.toString()
    }

    private fun JsonObject.text(key: String, max: Int): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.take(max)

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun JsonObject.numbers(key: String, max: Int): List<Double> =
        ((this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
            .filter { it.isFinite() }.take(max))

    private fun JsonObject.strings(key: String, max: Int, length: Int): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.take(length) }.take(max)

    private fun <T> JsonObject.list(key: String, max: Int, item: (JsonObject) -> T?): List<T> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(item) }.take(max)

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    /**
     * Arithmetic only: numbers, variables, plus minus times divide power percent
     * with parentheses, and a fixed function list. Anything else fails closed.
     * No member access, strings, or calls.
     */
    object FormulaEval {
        val functions = setOf("sqrt", "abs", "round", "floor", "ceil", "min", "max", "pow")
        private sealed interface Token {
            data class Number(val value: Double) : Token
            data class Name(val name: String) : Token
            data class Op(val op: Char) : Token
            data object LeftParen : Token
            data object RightParen : Token
            data object Comma : Token
        }

        /** The formula's value, or null when it isn't plain arithmetic. */
        fun check(formula: String, vars: Map<String, Double>): Double? = runCatching { evaluate(formula, vars) }.getOrNull()

        fun evaluate(formula: String, vars: Map<String, Double>): Double {
            require(formula.length <= 200 && formula.any(Char::isDigit)) { "Not a formula" }
            val tokens = tokenize(formula)
            require(tokens.isNotEmpty()) { "Not a formula" }
            val output = ArrayDeque<Token>()
            val ops = ArrayDeque<Token>()
            var previous: Token? = null
            for (token in tokens) {
                when (token) {
                    is Token.Number -> output.addLast(token)
                    is Token.Name -> if (token.name in functions) ops.addLast(token) else {
                        require(token.name in vars) { "Unknown variable" }
                        output.addLast(Token.Number(vars.getValue(token.name)))
                    }
                    Token.Comma -> { while (ops.isNotEmpty() && ops.last() != Token.LeftParen) output.addLast(ops.removeLast()); require(ops.isNotEmpty()) { "Misplaced comma" } }
                    Token.LeftParen -> ops.addLast(token)
                    Token.RightParen -> {
                        while (ops.isNotEmpty() && ops.last() != Token.LeftParen) output.addLast(ops.removeLast())
                        require(ops.isNotEmpty()) { "Mismatched parenthesis" }
                        ops.removeLast()
                        if (ops.isNotEmpty() && ops.last() is Token.Name) output.addLast(ops.removeLast())
                    }
                    is Token.Op -> {
                        // A minus after an operator, paren, or comma negates the next value.
                        val negating = token.op == '-' && (previous == null || previous is Token.Op || previous == Token.LeftParen || previous == Token.Comma)
                        if (negating) { ops.addLast(Token.Name("neg")); previous = token; continue }
                        while (ops.isNotEmpty() && ops.last() is Token.Op &&
                            (precedence((ops.last() as Token.Op).op) > precedence(token.op) ||
                                precedence((ops.last() as Token.Op).op) == precedence(token.op) && token.op != '^')) {
                            output.addLast(ops.removeLast())
                        }
                        ops.addLast(token)
                    }
                }
                previous = token
            }
            while (ops.isNotEmpty()) { val op = ops.removeLast(); require(op != Token.LeftParen) { "Mismatched parenthesis" }; output.addLast(op) }
            val stack = ArrayDeque<Double>()
            for (token in output) {
                when (token) {
                    is Token.Number -> stack.addLast(token.value)
                    is Token.Op -> {
                        require(stack.size >= 2) { "Bad expression" }
                        val b = stack.removeLast(); val a = stack.removeLast()
                        stack.addLast(apply(token.op, a, b))
                    }
                    is Token.Name -> when (token.name) {
                        "neg" -> { require(stack.isNotEmpty()) { "Bad expression" }; stack.addLast(-stack.removeLast()) }
                        "sqrt" -> stack.addLast(unary(stack) { v -> require(v >= 0) { "Bad sqrt" }; kotlin.math.sqrt(v) })
                        "abs" -> stack.addLast(kotlin.math.abs(unary(stack) { it }))
                        "round" -> stack.addLast(kotlin.math.round(unary(stack) { it }).toDouble())
                        "floor" -> stack.addLast(kotlin.math.floor(unary(stack) { it }))
                        "ceil" -> stack.addLast(kotlin.math.ceil(unary(stack) { it }))
                        "min", "max", "pow" -> {
                            require(stack.size >= 2) { "Bad expression" }
                            val b = stack.removeLast(); val a = stack.removeLast()
                            stack.addLast(when (token.name) { "min" -> minOf(a, b); "max" -> maxOf(a, b); else -> a.pow(b) })
                        }
                        else -> throw IllegalArgumentException("Unknown function")
                    }
                    else -> throw IllegalArgumentException("Bad expression")
                }
            }
            require(stack.size == 1) { "Bad expression" }
            return stack.single().also { require(it.isFinite()) { "Out of range" } }
        }

        private fun unary(stack: ArrayDeque<Double>, f: (Double) -> Double): Double {
            require(stack.isNotEmpty()) { "Bad expression" }
            return f(stack.removeLast()).also { require(it.isFinite()) { "Out of range" } }
        }

        private fun precedence(op: Char) = when (op) { '+', '-' -> 1; '*', '/', '%' -> 2; '^' -> 3; else -> 0 }

        private fun apply(op: Char, a: Double, b: Double): Double {
            if ((op == '/' || op == '%') && b == 0.0) throw IllegalArgumentException("Division by zero")
            val result = when (op) {
                '+' -> a + b; '-' -> a - b; '*' -> a * b
                '/' -> a / b; '%' -> a % b
                '^' -> a.pow(b)
                else -> throw IllegalArgumentException("Unknown operator")
            }
            require(result.isFinite()) { "Out of range" }
            return result
        }

        private fun Double.pow(other: Double) = Math.pow(this, other)

        private fun tokenize(formula: String): List<Token> {
            val out = mutableListOf<Token>()
            var i = 0
            while (i < formula.length) {
                val c = formula[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || c == '.' -> {
                        var j = i
                        while (j < formula.length && (formula[j].isDigit() || formula[j] == '.')) j++
                        if (j < formula.length && (formula[j] == 'e' || formula[j] == 'E')) {
                            var k = j + 1
                            if (k < formula.length && (formula[k] == '+' || formula[k] == '-')) k++
                            while (k < formula.length && formula[k].isDigit()) k++
                            if (k > j + 1) j = k
                        }
                        out += Token.Number(formula.substring(i, j).toDoubleOrNull() ?: throw IllegalArgumentException("Bad number"))
                        i = j
                    }
                    c.isLetter() || c == '_' -> {
                        var j = i
                        while (j < formula.length && (formula[j].isLetterOrDigit() || formula[j] == '_')) j++
                        out += Token.Name(formula.substring(i, j).lowercase())
                        i = j
                    }
                    c in "+-*/^%()" -> { out += if (c == '(') Token.LeftParen else if (c == ')') Token.RightParen else Token.Op(c); i++ }
                    c == ',' -> { out += Token.Comma; i++ }
                    else -> throw IllegalArgumentException("Bad character")
                }
            }
            return out
        }
    }
}
