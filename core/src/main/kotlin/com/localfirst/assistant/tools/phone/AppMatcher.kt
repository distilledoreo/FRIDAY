package com.localfirst.assistant.tools.phone

data class InstalledApp(val label: String, val packageName: String)

/** Picks the installed app a spoken or typed name refers to. */
object AppMatcher {
    /**
     * Exact name first, then apps whose name starts with the query or has a
     * word equal to it ("maps" matches both "Google Maps" and "Maps Go"), then
     * apps whose name contains it. A step with one hit wins; a step with
     * several throws [PhoneActionException] listing them, as does no match.
     */
    fun best(query: String, apps: List<InstalledApp>): InstalledApp {
        val wanted = normalize(query.removeSuffix(" app").removeSuffix(" App"))
        if (wanted.isEmpty()) throw PhoneActionException("Say which app to open.")
        val keyed = apps.distinctBy { it.packageName }.map { normalize(it.label) to it }
        fun words(app: InstalledApp) = app.label.split(' ', '-', '_').map(::normalize)

        keyed.filter { it.first == wanted }.map { it.second }.let { exact ->
            if (exact.size == 1) return exact.single()
            if (exact.size > 1) throw ambiguous(query, exact)
        }
        val rules = listOf<(Pair<String, InstalledApp>) -> Boolean>(
            { (label, app) -> label.startsWith(wanted) || wanted in words(app) },
            { (label, _) -> label.contains(wanted) },
        )
        for (rule in rules) {
            val hits = keyed.filter(rule).map { it.second }
            if (hits.size == 1) return hits.single()
            if (hits.size > 1) throw ambiguous(query, hits)
        }
        throw PhoneActionException("No installed app is called \"$query\".")
    }

    private fun ambiguous(query: String, apps: List<InstalledApp>) = PhoneActionException(
        "Several apps match \"$query\": ${apps.take(6).joinToString { it.label }}. Ask the user which one.",
    )

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
}
