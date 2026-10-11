package com.localfirst.assistant.genui

import com.localfirst.assistant.genui.script.Values

/**
 * Plain Markdown for an interface, made from the rendered tree: it is what copy, voice, Android Auto,
 * notifications and chat search use. Unlike ChatGPT's fallbackMarkdown it includes computed values
 * and the content of `{#each}` loops, because it reads the rendered result rather than the source.
 */
object UiFallback {
    fun markdown(nodes: List<UiNode>): String =
        blocks(nodes).joinToString("\n\n").replace(Regex("\n{3,}"), "\n\n").trim()

    /** The reply as plain text for speech: Markdown markers removed by the caller's speech cleaner. */
    fun markdown(source: String, complete: Boolean = true): String = markdown(UiRuntime.renderOnce(source, complete).nodes)

    private fun blocks(nodes: List<UiNode>): List<String> = nodes.flatMap(::block).filter { it.isNotBlank() }

    private fun inline(nodes: List<UiNode>): String = nodes.joinToString("") { node ->
        when (node) {
            is UiText -> node.text
            is UiRaw -> ""
            is UiElement -> when (node.type) {
                "bold" -> "**${inline(node.children)}**"
                "italic" -> "_${inline(node.children)}_"
                "strike" -> "~~${inline(node.children)}~~"
                "code", "kbd" -> "`${inline(node.children)}`"
                "link" -> node.string("href")?.let { "[${inline(node.children)}]($it)" } ?: inline(node.children)
                "icon" -> ""
                else -> block(node).joinToString(" ")
            }
        }
    }.replace(Regex("[ \\t]+"), " ")

    private fun label(e: UiElement) = e.string("label") ?: e.string("title") ?: e.string("name")

    private fun value(v: Any?): String = when (v) {
        null -> ""
        is Double -> UiFormat.number(v)
        is List<*> -> v.joinToString(", ") { value(it) }
        is Map<*, *> -> (v["label"] ?: v["name"] ?: v["value"])?.let { value(it) } ?: ""
        else -> v.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(v: Any?): List<Any?> = (v as? List<Any?>).orEmpty()

    private fun block(node: UiNode): List<String> {
        if (node is UiText) return listOf(node.text)
        if (node is UiRaw) return listOf("_Interactive app: ${node.props["title"] ?: "app"}_")
        val e = node as UiElement
        val kids = e.children
        return when (e.type) {
            "title" -> listOf("#".repeat(when (e.string("size")) { "3xl", "2xl" -> 1; "xl" -> 2; "lg", null -> 3; else -> 4 }) + " " + inline(kids).trim())
            "text", "caption", "badge", "chip", "th", "td" -> listOf(inline(kids).trim())
            "bold", "italic", "strike", "code", "link", "underline", "mark", "color", "kbd" -> listOf(inline(listOf(e)))
            "markdown" -> listOf(e.string("content").orEmpty())
            "codeblock" -> listOf("```${e.string("language").orEmpty()}\n${e.string("code") ?: inline(kids)}\n```")
            "divider" -> listOf("---")
            "spacer", "icon", "skeleton", "button" -> emptyList()
            "quote" -> listOf(blocks(kids).joinToString("\n").lines().joinToString("\n") { "> $it" })
            "callout" -> listOf((listOfNotNull(e.string("title")?.let { "**$it**" }) + blocks(kids)).joinToString(" ").let { "> $it" })
            "list" -> {
                val ordered = e.bool("ordered") == true
                kids.filterIsInstance<UiElement>().mapIndexed { i, item -> (if (ordered) "${i + 1}. " else "- ") + blocks(item.children).joinToString(" ") }.let { listOf(it.joinToString("\n")) }
            }
            "card", "section", "collapsible", "tab" -> listOf((listOfNotNull(e.string("title")?.let { "**$it**" }, e.string("subtitle")) + blocks(kids)).joinToString("\n\n"))
            "slider", "stepper", "input", "textarea", "date", "time", "select", "segmented", "radiogroup", "chips", "rating" ->
                listOfNotNull(label(e)?.let { "$it: ${value(e.props["value"] ?: e.props["defaultValue"])}" + (e.string("unit")?.let { u -> " $u" } ?: "") })
            "toggle", "checkbox" -> listOfNotNull(label(e)?.let { "$it: ${if (e.bool("checked") ?: e.bool("value") == true) "on" else "off"}" })
            "check" -> listOf("- [${if (e.bool("checked") == true) "x" else " "}] ${e.string("label") ?: inline(kids)}")
            "checklist" -> listOf(blocks(kids).joinToString("\n"))
            "stat" -> listOf("**${e.string("label").orEmpty()}:** ${value(e.props["value"])}" + (e.props["delta"]?.let { " (${value(it)})" } ?: ""))
            "facts" -> listOf(rows(e.props["items"]).joinToString("\n") { row -> (row as? Map<*, *>)?.let { "- ${value(it["label"])}: ${value(it["value"])}" } ?: "- ${value(row)}" })
            "progress", "ring" -> listOf("${e.string("label") ?: "Progress"}: ${UiFormat.number(100 * (e.number("value") ?: 0.0) / (e.number("max") ?: 100.0))}%")
            "timeline" -> listOf(kids.filterIsInstance<UiElement>().joinToString("\n") { ev -> "- ${listOfNotNull(ev.string("time"), ev.string("title")).joinToString(" — ")}" + inline(ev.children).let { if (it.isBlank()) "" else ": $it" } })
            "steps" -> listOf(kids.filterIsInstance<UiElement>().mapIndexed { i, s -> "${i + 1}. ${s.string("title").orEmpty()}" + inline(s.children).let { if (it.isBlank()) "" else " — $it" } }.joinToString("\n"))
            "table" -> listOf(table(e))
            "chart" -> listOf(chart(e))
            "weather" -> listOf(listOfNotNull(e.string("place"), e.props["temperature"]?.let(::value), e.string("condition")).joinToString(", ") +
                rows(e.props["days"]).joinToString("") { d -> (d as? Map<*, *>)?.let { "\n- ${value(it["day"])}: ${value(it["condition"])} ${value(it["high"])}/${value(it["low"])}" } ?: "" })
            "image" -> listOfNotNull(e.string("src")?.let { "![${e.string("alt").orEmpty()}]($it)" })
            "AsyncImage" -> listOfNotNull((e.string("alt") ?: e.string("query"))?.let { "_Image: ${it}_" })
            "AsyncImageGroup" -> emptyList()
            "Entity", "Cite" -> listOfNotNull((e.string("name") ?: e.string("title"))?.let { name -> e.string("url")?.let { "[$name]($it)" } ?: name })
            "product" -> listOf(listOfNotNull(e.string("name")?.let { "**$it**" }, e.props["price"]?.let(::value), e.string("merchant")).joinToString(" — "))
            "place" -> listOf(listOfNotNull(e.string("name")?.let { "**$it**" }, e.string("address")).joinToString(" — "))
            "map" -> listOfNotNull(e.string("query")?.let { "Map: $it" })
            "timer" -> listOf("${e.string("label") ?: "Timer"}: ${UiFormat.duration((e.number("seconds") ?: 0.0).toLong())}")
            "countdown" -> listOf("${e.string("label") ?: "Countdown"} to ${e.string("to").orEmpty()}")
            "video" -> listOfNotNull(e.string("url")?.let { "[${e.string("title") ?: "Video"}]($it)" })
            "avatar" -> listOfNotNull(e.string("name"))
            else -> blocks(kids).let { if (e.type in setOf("row", "tr")) listOf(it.joinToString(" · ")) else it }
        }
    }

    private fun table(e: UiElement): String {
        val columns = rows(e.props["columns"]).map { c -> (c as? Map<*, *>)?.let { value(it["label"] ?: it["key"]) } ?: value(c) }
        val keys = rows(e.props["columns"]).map { c -> (c as? Map<*, *>)?.get("key")?.toString() ?: value(c) }
        val data = rows(e.props["rows"]).map { row ->
            when (row) {
                is List<*> -> row.map(::value)
                is Map<*, *> -> keys.map { value(row[it]) }
                else -> listOf(value(row))
            }
        }
        if (columns.isEmpty() && data.isEmpty()) {
            // <tr>/<th>/<td> children.
            val lines = e.children.filterIsInstance<UiElement>().flatMap { if (it.type == "tr") listOf(it) else it.children.filterIsInstance<UiElement>().filter { r -> r.type == "tr" } }
                .map { tr -> tr.children.filterIsInstance<UiElement>().map { cell -> inline(cell.children).trim() } }
            if (lines.isEmpty()) return ""
            return (listOf(lines.first()) + listOf(lines.first().map { "---" }) + lines.drop(1)).joinToString("\n") { "| " + it.joinToString(" | ") + " |" }
        }
        val header = columns.ifEmpty { data.firstOrNull()?.indices?.map { "" } ?: emptyList() }
        return (listOf(header, header.map { "---" }) + data).joinToString("\n") { "| " + it.joinToString(" | ") + " |" }
    }

    private fun chart(e: UiElement): String {
        val title = e.string("title")?.let { "$it: " }.orEmpty()
        val labels = rows(e.props["labels"])
        val values = rows(e.props["values"])
        if (labels.isNotEmpty() && values.isNotEmpty()) return title + labels.zip(values).joinToString(", ") { (l, v) -> "${value(l)} ${value(v)}" }
        val x = e.string("x")
        val ys = when (val y = e.props["y"]) { is List<*> -> y.map { it.toString() }; null -> emptyList(); else -> listOf(y.toString()) }
        val data = rows(e.props["data"]).filterIsInstance<Map<*, *>>()
        if (x != null && ys.isNotEmpty()) return title + data.joinToString("; ") { row -> value(row[x]) + " " + ys.joinToString(", ") { k -> (if (ys.size > 1) "$k " else "") + value(row[k]) } }
        return title.removeSuffix(": ")
    }
}

/** Number and duration formatting shared by the fallback and renderers. */
object UiFormat {
    fun number(v: Double): String = if (v.isFinite() && v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else Values.numberToString(Math.round(v * 100) / 100.0)

    fun duration(seconds: Long): String {
        val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
