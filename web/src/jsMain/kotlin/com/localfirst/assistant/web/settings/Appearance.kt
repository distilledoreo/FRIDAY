package com.localfirst.assistant.web.settings

import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/** Browser-backed appearance preferences, persisted in localStorage. */
class AppearanceStore {
    private fun load() = AppearanceSettings.from(
        get("theme"), get("accent"), get("proactivity"),
        get("reduced_motion") == "true", get("display_name"),
        get("interactive_answers") != "false",
    )
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    fun save(settings: AppearanceSettings) {
        val accent = AppearanceSettings.accent(settings.accentHex) ?: return
        put("theme", settings.theme.name); put("accent", accent)
        put("proactivity", settings.proactivity.name); put("reduced_motion", settings.reducedMotion.toString())
        put("display_name", settings.displayName.trim().take(60)); put("interactive_answers", settings.interactiveAnswers.toString())
        mutable.value = settings.copy(accentHex = accent)
    }

    private fun get(key: String): String? = runCatching { localStorage.getItem("friday_appearance:$key") }.getOrNull()
    private fun put(key: String, value: String) { runCatching { localStorage.setItem("friday_appearance:$key", value) } }
}
