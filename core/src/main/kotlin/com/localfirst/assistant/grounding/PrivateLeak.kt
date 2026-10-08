package com.localfirst.assistant.grounding

/**
 * Finds private details in what the model wants to send to the public web: words that
 * came from recalled memories or private tool results (inbox, calendar, briefs) and that
 * the user didn't write themselves. A search built only from the user's own words and
 * everyday vocabulary goes out without asking; one that carries a name, place or other
 * detail from private context asks first.
 */
object PrivateLeak {
    /** Marks the start of recalled memories in the system prompt. */
    const val RECALL_MARKER = "\n\nRetrieved personal context."

    private val word = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’]*")
    private val contact = Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+|\\+?\\d[\\d\\s().-]{7,}\\d")

    /** The private words in [outgoing], or empty when it's safe to send without asking. */
    fun details(outgoing: String, privateTexts: List<String>, userText: String): List<String> {
        if (privateTexts.isEmpty() || outgoing.isBlank()) return emptyList()
        val own = words(userText).toSet()
        val private = privateTexts.flatMapTo(HashSet()) { words(it) }
        val found = LinkedHashSet<String>()
        for (match in contact.findAll(outgoing)) {
            val value = match.value.trim()
            if (privateTexts.any { value in it } && value !in userText) found += value
        }
        for (w in words(contact.replace(outgoing, " "))) {
            if (w.length >= 3 && w !in COMMON && w in private && w !in own) found += w
        }
        return found.toList()
    }

    /** The recalled memories in a system prompt, without the fixed explanation that introduces them. */
    fun recalled(systemPrompt: String): String? {
        val start = systemPrompt.indexOf(RECALL_MARKER).takeIf { it >= 0 } ?: return null
        val body = systemPrompt.indexOf('\n', start + RECALL_MARKER.length).takeIf { it >= 0 } ?: return null
        return systemPrompt.substring(body).trim().ifEmpty { null }
    }

    private fun words(text: String): List<String> =
        word.findAll(text.lowercase().replace('’', '\'')).map { it.value.trim('\'') }.filter { it.isNotEmpty() }.toList()

    /** Everyday words that say nothing private on their own, even when a memory contains them. */
    private val COMMON = """
        the and for are but not you all any can had her was one our out day get has him his how man new now old see two way who
        its did let put say she too use with that this from they have will your what when where which while there their them then
        than been were would could should about after again also back because before being best between both came come does done down
        each even every find first give good great here into just know last like long look made make many more most much must near
        need next only other over people right same some still such take tell than these thing things think those through time today
        tomorrow tonight under very want well went year years week weeks month months open close closed hours price prices cost
        cheap free near nearby best top latest news current recent update updates review reviews guide how-to vs versus compare
        weather forecast temperature rain snow sunny wind traffic score scores game games schedule result results live stream
        official website site page online buy sale deal deals local city town state country world service services company store
        stores shop restaurant restaurants food recipe recipes movie movies show shows song songs music book books app apps phone
        phones release date dates time times today's tonight's morning evening night weekend monday tuesday wednesday thursday
        friday saturday sunday january february march april may june july august september october november december 2024 2025 2026 2027
        user user's mentioned memory memories chat chats fact facts approved past said says prefers likes like loves enjoys wants
        work works working home family friend friends help information info about details detail question answer answers
    """.trim().split(Regex("\\s+")).toSet()
}
