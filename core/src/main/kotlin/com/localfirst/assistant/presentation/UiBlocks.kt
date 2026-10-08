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
 * code: the only interactions are ticking items locally and sending a choice as the user's reply.
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
            else -> null
        }
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
    }

    private fun safeUrl(url: String) = url.startsWith("https://") && ' ' !in url

    private fun JsonObject.text(key: String, max: Int): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.take(max)

    private fun JsonObject.strings(key: String, max: Int, length: Int): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.take(length) }.take(max)

    private fun <T> JsonObject.list(key: String, max: Int, item: (JsonObject) -> T?): List<T> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(item) }.take(max)

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
