package com.localfirst.assistant.settings

import android.content.Context

data class ServerSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /** PC search service root. Blank leaves web_search registered but unable to run. */
    val searchBaseUrl: String = DEFAULT_SEARCH_BASE_URL,
    /** Bearer token for the search service. Blank sends no Authorization header. */
    val searchApiKey: String = "",
    /** In voice mode, start talking to interrupt the assistant. */
    val voiceBargeIn: Boolean = true,
    /** Listen for "Hey FRIDAY" with a foreground mic service and open voice chat on match. Uses more battery. */
    val wakeWord: Boolean = false,
    /** [VOICE_COMPUTER]: Parakeet + Kokoro on the search service's computer. [VOICE_PHONE]: Android speech. */
    val voiceEngine: String = VOICE_COMPUTER,
    /** Kokoro voice for the computer engine. */
    val voiceName: String = DEFAULT_VOICE,
    /** Bluetooth devices (by name) whose own mic is skipped: voice plays through them and the phone listens. */
    val voicePhoneMicDevices: Set<String> = emptySet(),
    /** Show replies in Android Auto as messages, and accept replies from the car. */
    val androidAuto: Boolean = true,
    /** The PC's MAC address for Wake-on-LAN; blank disables waking. */
    val pcMac: String = "",
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
        val search = searchBaseUrl.trim()
        if (search.isNotEmpty() && !search.startsWith("http://") && !search.startsWith("https://")) {
            return "Search service address must start with http:// or https://."
        }
        return null
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:11434/v1"
        const val DEFAULT_SEARCH_BASE_URL = "http://10.0.2.2:8765"
        const val DEFAULT_TIMEOUT_SECONDS = 90
        const val MIN_TIMEOUT_SECONDS = 5
        const val MAX_TIMEOUT_SECONDS = 600
        const val VOICE_COMPUTER = "computer"
        const val VOICE_PHONE = "phone"
        const val DEFAULT_VOICE = "af_heart"
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
        searchBaseUrl = prefs.getString(KEY_SEARCH_URL, ServerSettings.DEFAULT_SEARCH_BASE_URL)
            ?: ServerSettings.DEFAULT_SEARCH_BASE_URL,
        searchApiKey = prefs.getString(KEY_SEARCH_API_KEY, "") ?: "",
        voiceBargeIn = prefs.getBoolean(KEY_VOICE_BARGE_IN, true),
        wakeWord = prefs.getBoolean(KEY_WAKE_WORD, false),
        voiceEngine = prefs.getString(KEY_VOICE_ENGINE, ServerSettings.VOICE_COMPUTER) ?: ServerSettings.VOICE_COMPUTER,
        voiceName = prefs.getString(KEY_VOICE_NAME, ServerSettings.DEFAULT_VOICE) ?: ServerSettings.DEFAULT_VOICE,
        voicePhoneMicDevices = prefs.getStringSet(KEY_VOICE_PHONE_MIC, null)?.toSet().orEmpty(),
        androidAuto = prefs.getBoolean(KEY_ANDROID_AUTO, true),
        pcMac = prefs.getString(KEY_PC_MAC, "") ?: "",
    )

    fun save(settings: ServerSettings) {
        prefs.edit()
            .putString(KEY_URL, settings.baseUrl.trim())
            .putString(KEY_MODEL, settings.model.trim())
            .putString(KEY_API_KEY, settings.apiKey.trim())
            .putInt(KEY_TIMEOUT, settings.timeoutSeconds)
            .putString(KEY_SEARCH_URL, settings.searchBaseUrl.trim())
            .putString(KEY_SEARCH_API_KEY, settings.searchApiKey.trim())
            .putBoolean(KEY_VOICE_BARGE_IN, settings.voiceBargeIn)
            .putBoolean(KEY_WAKE_WORD, settings.wakeWord)
            .putString(KEY_VOICE_ENGINE, settings.voiceEngine)
            .putString(KEY_VOICE_NAME, settings.voiceName)
            .putStringSet(KEY_VOICE_PHONE_MIC, settings.voicePhoneMicDevices.toSet())
            .putBoolean(KEY_ANDROID_AUTO, settings.androidAuto)
            .putString(KEY_PC_MAC, settings.pcMac.trim())
            .apply()
    }

    private companion object {
        const val PREFS = "server_settings"
        const val KEY_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
        const val KEY_TIMEOUT = "timeout_seconds"
        const val KEY_SEARCH_URL = "search_base_url"
        const val KEY_SEARCH_API_KEY = "search_api_key"
        const val KEY_VOICE_BARGE_IN = "voice_barge_in"
        const val KEY_WAKE_WORD = "wake_word"
        const val KEY_VOICE_ENGINE = "voice_engine"
        const val KEY_VOICE_NAME = "voice_name"
        const val KEY_VOICE_PHONE_MIC = "voice_phone_mic_devices"
        const val KEY_ANDROID_AUTO = "android_auto"
        const val KEY_PC_MAC = "pc_mac"
    }
}
