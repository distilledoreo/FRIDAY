package com.localfirst.assistant.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppearanceSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("friday_appearance", Context.MODE_PRIVATE)
    private fun load() = AppearanceSettings.from(preferences.getString("theme", null), preferences.getString("accent", null), preferences.getString("proactivity", null), preferences.getBoolean("reduced_motion", false), preferences.getString("display_name", null), preferences.getBoolean("interactive_answers", true), preferences.getString("thinking", null))
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mutable.value = load() }
    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
    fun save(settings: AppearanceSettings) {
        val accent = AppearanceSettings.accent(settings.accentHex) ?: return
        preferences.edit().putString("theme", settings.theme.name).putString("accent", accent)
            .putString("proactivity", settings.proactivity.name).putBoolean("reduced_motion", settings.reducedMotion).putString("display_name", settings.displayName.trim().take(60)).putBoolean("interactive_answers", settings.interactiveAnswers).putString("thinking", settings.thinking.name).apply()
        mutable.value = settings.copy(accentHex = accent)
    }
    fun close() = preferences.unregisterOnSharedPreferenceChangeListener(listener)
}
