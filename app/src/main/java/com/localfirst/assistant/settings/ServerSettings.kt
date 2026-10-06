package com.localfirst.assistant.settings

import android.content.Context

data class ServerSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {
    fun validate(): String? {
        val url = baseUrl.trim()
        if (url.isEmpty()) return "Enter the model server address."
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Server address must start with http:// or https://."
        }
        if (model.trim().isEmpty()) return "Enter the model name."
        if (timeoutSeconds !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
            return "Timeout must be between $MIN_TIMEOUT_SECONDS and $MAX_TIMEOUT_SECONDS seconds."
        }
        return null
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:11434/v1"
        const val DEFAULT_TIMEOUT_SECONDS = 90
        const val MIN_TIMEOUT_SECONDS = 5
        const val MAX_TIMEOUT_SECONDS = 600
    }
}

class ServerSettingsStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): ServerSettings = ServerSettings(
        baseUrl = prefs.getString(KEY_URL, ServerSettings.DEFAULT_BASE_URL)
            ?: ServerSettings.DEFAULT_BASE_URL,
        model = prefs.getString(KEY_MODEL, "") ?: "",
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        timeoutSeconds = prefs.getInt(KEY_TIMEOUT, ServerSettings.DEFAULT_TIMEOUT_SECONDS),
    )

    fun save(settings: ServerSettings) {
        prefs.edit()
            .putString(KEY_URL, settings.baseUrl.trim())
            .putString(KEY_MODEL, settings.model.trim())
            .putString(KEY_API_KEY, settings.apiKey.trim())
            .putInt(KEY_TIMEOUT, settings.timeoutSeconds)
            .apply()
    }

    private companion object {
        const val PREFS = "server_settings"
        const val KEY_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
        const val KEY_TIMEOUT = "timeout_seconds"
    }
}
