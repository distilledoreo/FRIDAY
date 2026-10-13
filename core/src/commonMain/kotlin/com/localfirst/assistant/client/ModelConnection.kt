package com.localfirst.assistant.client

/** Per-device connection settings, never part of synchronized chat preferences. */
data class ModelConnection(val address: String = "", val model: String = "", val apiKey: String = "", val timeout: String = "90") {
    fun validate(): String? = when {
        !Regex("https?://[^/?#\\s]+(?:/[^?#\\s]*)?").matches(address.trim()) || '@' in address.trim().removePrefix("https://").removePrefix("http://").substringBefore('/') -> "Enter an HTTP or HTTPS model address."
        model.trim().isEmpty() -> "Enter the model name."
        timeout.toIntOrNull() !in 5..600 -> "Timeout must be between 5 and 600 seconds."
        else -> null
    }
}
