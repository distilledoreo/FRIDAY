package com.localfirst.assistant.workspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Shared with the browser; Android keeps them in its preferences. */
typealias ImageSettings = com.localfirst.assistant.client.ImageSettings

class ImageSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("image-settings", Context.MODE_PRIVATE)
    fun load(server: String): ImageSettings = ImageSettings(
        prefs.getBoolean("automatic", true), prefs.getInt("width", 768), prefs.getInt("height", 768),
        prefs.getInt("steps", 8), prefs.getString("seed", null)?.toIntOrNull(), prefs.getBoolean("transparent", false),
        if (prefs.getString("reference-server", null) == server) prefs.getStringSet("references", emptySet()).orEmpty().toList() else emptyList(),
    )
    fun save(settings: ImageSettings, server: String) {
        require(settings.validate() == null) { settings.validate().orEmpty() }
        prefs.edit().putBoolean("automatic", settings.automatic).putInt("width", settings.width).putInt("height", settings.height)
            .putInt("steps", settings.steps).putString("seed", settings.seed?.toString()).putBoolean("transparent", settings.transparent)
            .putStringSet("references", settings.references.toSet()).putString("reference-server", server).apply()
    }
    fun apply(body: JSONObject, server: String): JSONObject {
        val s = load(server)
        if (!s.automatic) {
            body.put("width", s.width).put("height", s.height).put("steps", s.steps).put("transparent", s.transparent)
            body.put("seed", s.seed ?: kotlin.random.Random.nextInt(Int.MAX_VALUE))
            body.put("reference_file_ids", JSONArray(s.references))
        } else if (s.references.isNotEmpty()) body.put("reference_file_ids", JSONArray(s.references))
        return body
    }
}
