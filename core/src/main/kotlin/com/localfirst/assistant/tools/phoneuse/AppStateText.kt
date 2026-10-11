package com.localfirst.assistant.tools.phoneuse

/**
 * Renders a snapshot the way Codex's get_app_state does: a short header, then one line per element,
 * tab-indented by depth: `index role label, Value: …, (states), Secondary Actions: …, [l,t,r,b]`.
 */
object AppStateText {
    const val MAX_CHARS = 14_000
    private const val MAX_LABEL = 160

    fun render(snapshot: AppSnapshot, maxChars: Int = MAX_CHARS): String {
        val header = buildList {
            add("App: ${snapshot.app.label} (${snapshot.app.packageName})")
            snapshot.windowTitle?.takeIf { it.isNotBlank() && it != snapshot.app.label }?.let { add("Window: ${clean(it)}") }
            val display = if (snapshot.displayWidth != snapshot.width || snapshot.displayHeight != snapshot.height) {
                " (Android display ${snapshot.displayWidth}x${snapshot.displayHeight})"
            } else ""
            add("Coordinates: ${snapshot.width}x${snapshot.height} screenshot pixels, origin top-left$display")
            add(
                if (snapshot.screenshot != null) "Screenshot: attached after this result, in the same coordinates"
                else "Screenshot: not included" + (snapshot.screenshotNote?.let { " ($it)" } ?: ""),
            )
            snapshot.keyboard?.let { add("Keyboard: showing ($it)") }
            if (snapshot.otherWindows.isNotEmpty()) add("Also on screen: " + snapshot.otherWindows.joinToString("; ") { clean(it) })
        }
        val lines = mutableListOf<String>()
        fun visit(element: AppElement, depth: Int) {
            lines += "\t".repeat(depth) + line(element)
            element.children.forEach { visit(it, depth + 1) }
        }
        snapshot.elements.forEach { visit(it, 0) }
        if (lines.isEmpty()) lines += "(The screen exposes no accessible elements. Use the screenshot and x/y coordinates.)"

        val out = StringBuilder(header.joinToString("\n")).append("\n\n")
        var shown = 0
        for (line in lines) {
            if (out.length + line.length + 1 > maxChars) break
            out.append(line).append('\n')
            shown++
        }
        val hidden = lines.size - shown
        if (hidden > 0) out.append("… $hidden more elements not shown. Scroll, or act on what is visible.\n")
        else if (snapshot.truncated) out.append("… some elements were left out. Scroll to see more.\n")
        return out.toString().trimEnd()
    }

    fun line(element: AppElement): String = buildString {
        append(element.index).append(' ').append(element.role)
        element.label?.let { append(' ').append(clean(it)) }
        element.value?.let { append(", Value: ").append(clean(it)) }
        if (element.states.isNotEmpty()) append(" (").append(element.states.joinToString(", ")).append(')')
        if (element.secondaryActions.isNotEmpty()) append(", Secondary Actions: ").append(element.secondaryActions.joinToString(", "))
        element.bounds?.let { append(" [").append(it.left).append(',').append(it.top).append(',').append(it.right).append(',').append(it.bottom).append(']') }
    }

    private fun clean(text: String): String {
        val single = text.replace(Regex("\\s+"), " ").trim()
        return if (single.length > MAX_LABEL) single.take(MAX_LABEL - 1) + "…" else single
    }

    /** The listing list_apps returns. */
    fun apps(apps: List<PhoneApp>, max: Int = 200): String {
        if (apps.isEmpty()) return "No launchable apps were found."
        val recent = apps.filter { it.recent }
        val rest = apps.filterNot { it.recent }.sortedBy { it.label.lowercase() }
        return buildString {
            if (recent.isNotEmpty()) {
                append("Recently used:\n")
                recent.take(max).forEach { append("- ${it.label} (${it.packageName})\n") }
                append('\n')
            }
            append("Installed:\n")
            rest.take((max - recent.size).coerceAtLeast(0)).forEach { append("- ${it.label} (${it.packageName})\n") }
            val hidden = apps.size - max
            if (hidden > 0) append("… $hidden more\n")
        }.trimEnd()
    }
}
