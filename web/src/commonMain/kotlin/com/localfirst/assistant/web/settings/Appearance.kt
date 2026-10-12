package com.localfirst.assistant.web.settings


enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("Follow system") }
enum class ProactivityLevel(val label: String, val suggestionLimit: Int) {
    CONSERVATIVE("Conservative", 2), THOUGHTFUL("Thoughtfully proactive", 4), HIGH("Highly proactive", 6)
}

/** Separate preferences: changing the design never rewrites account/server or chat data. */
data class AppearanceSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val accentHex: String = DEFAULT_ACCENT,
    val proactivity: ProactivityLevel = ProactivityLevel.THOUGHTFUL,
    val reducedMotion: Boolean = false,
    val displayName: String = "",
    val interactiveAnswers: Boolean = true,
) {
    companion object {
        const val DEFAULT_ACCENT = "E89980"
        fun accent(value: String): String? = value.trim().removePrefix("#").uppercase().takeIf { it.matches(Regex("[0-9A-F]{6}")) }
        fun from(theme: String?, accent: String?, proactivity: String?, reduced: Boolean, name: String? = null, interactive: Boolean = true) = AppearanceSettings(
            ThemeMode.entries.firstOrNull { it.name == theme } ?: ThemeMode.DARK,
            accent?.let(::accent) ?: DEFAULT_ACCENT,
            ProactivityLevel.entries.firstOrNull { it.name == proactivity } ?: ProactivityLevel.THOUGHTFUL,
            reduced,
            name?.trim()?.take(60).orEmpty(),
            interactive,
        )
    }
}
