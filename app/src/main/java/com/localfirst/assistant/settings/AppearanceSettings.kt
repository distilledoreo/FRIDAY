package com.localfirst.assistant.settings

import android.content.Context
import android.content.SharedPreferences
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
    /** Optional; only used for the initial on the profile button. */
    val displayName: String = "",
) {
    companion object {
        const val DEFAULT_ACCENT = "E89980"
        fun accent(value: String): String? = value.trim().removePrefix("#").uppercase().takeIf { it.matches(Regex("[0-9A-F]{6}")) }
        fun from(theme: String?, accent: String?, proactivity: String?, reduced: Boolean, name: String? = null) = AppearanceSettings(
            ThemeMode.entries.firstOrNull { it.name == theme } ?: ThemeMode.DARK,
            accent?.let(::accent) ?: DEFAULT_ACCENT,
            ProactivityLevel.entries.firstOrNull { it.name == proactivity } ?: ProactivityLevel.THOUGHTFUL,
            reduced,
            name?.trim()?.take(60).orEmpty(),
        )
    }
}

class AppearanceSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("friday_appearance", Context.MODE_PRIVATE)
    private fun load() = AppearanceSettings.from(preferences.getString("theme", null), preferences.getString("accent", null), preferences.getString("proactivity", null), preferences.getBoolean("reduced_motion", false), preferences.getString("display_name", null))
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mutable.value = load() }
    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
    fun save(settings: AppearanceSettings) {
        val accent = AppearanceSettings.accent(settings.accentHex) ?: return
        preferences.edit().putString("theme", settings.theme.name).putString("accent", accent)
            .putString("proactivity", settings.proactivity.name).putBoolean("reduced_motion", settings.reducedMotion).putString("display_name", settings.displayName.trim().take(60)).apply()
        mutable.value = settings.copy(accentHex = accent)
    }
    fun close() = preferences.unregisterOnSharedPreferenceChangeListener(listener)
}
