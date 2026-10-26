package com.localfirst.assistant.settings

import kotlinx.serialization.json.*

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("Follow system") }
enum class ProactivityLevel(val label: String, val suggestionLimit: Int) {
    CONSERVATIVE("Conservative", 2), THOUGHTFUL("Thoughtfully proactive", 4), HIGH("Highly proactive", 6)
}
/** How long the model reasons before a typed reply. [wire] is what clients send to the PC gateway. Voice never reasons. */
enum class ThinkingLevel(val label: String, val wire: String) {
    OFF("Off", "off"), LOW("Low", "low"), MEDIUM("Medium", "medium"), HIGH("High", "high");
    companion object { fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: HIGH }
}

/** Separate preferences: changing the design never rewrites account/server or chat data. */
data class AppearanceSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val accentHex: String = DEFAULT_ACCENT,
    val proactivity: ProactivityLevel = ProactivityLevel.THOUGHTFUL,
    val reducedMotion: Boolean = false,
    /** Optional; only used for the initial on the profile button. */
    val displayName: String = "",
    /** Replies may use live components (Intelligent UI). Off keeps answers as Markdown. */
    val interactiveAnswers: Boolean = true,
    val thinking: ThinkingLevel = ThinkingLevel.HIGH,
) {
    companion object {
        const val DEFAULT_ACCENT = "E89980"
        fun accent(value: String): String? = value.trim().removePrefix("#").uppercase().takeIf { it.matches(Regex("[0-9A-F]{6}")) }
        fun from(theme: String?, accent: String?, proactivity: String?, reduced: Boolean, name: String? = null, interactive: Boolean = true, thinking: String? = null) = AppearanceSettings(
            ThemeMode.entries.firstOrNull { it.name == theme } ?: ThemeMode.DARK,
            accent?.let(::accent) ?: DEFAULT_ACCENT,
            ProactivityLevel.entries.firstOrNull { it.name == proactivity } ?: ProactivityLevel.THOUGHTFUL,
            reduced,
            name?.trim()?.take(60).orEmpty(),
            interactive,
            ThinkingLevel.parse(thinking),
        )
    }
}


/** Portable keys are the complete allow-list; device credentials never enter shared records. */
val portablePreferenceKeys = setOf("theme", "accent", "proactivity", "reduced_motion", "display_name", "interactive_answers", "thinking")
fun AppearanceSettings.withPreference(key: String, value: JsonPrimitive): AppearanceSettings {
    require(key in portablePreferenceKeys) { "Unknown portable preference: $key" }
    return when(key) {
        "theme" -> copy(theme = ThemeMode.valueOf(value.content))
        "accent" -> copy(accentHex = requireNotNull(AppearanceSettings.accent(value.content)))
        "proactivity" -> copy(proactivity = ProactivityLevel.valueOf(value.content))
        "reduced_motion" -> copy(reducedMotion = value.boolean)
        "display_name" -> copy(displayName = value.content.trim().take(60))
        "interactive_answers" -> copy(interactiveAnswers = value.boolean)
        "thinking" -> copy(thinking = ThinkingLevel.valueOf(value.content))
        else -> this
    }
}
