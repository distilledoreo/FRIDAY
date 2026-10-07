package com.localfirst.assistant.workspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ImageSettings(
    val automatic: Boolean = true, val width: Int = 768, val height: Int = 768,
    val steps: Int = 8, val seed: Int? = null, val transparent: Boolean = false,
    val references: List<String> = emptyList(),
) {
    fun validate(): String? = when {
        !automatic && (width !in 256..2000 || height !in 256..2000 || width % 16 != 0 || height % 16 != 0) -> "Use dimensions from 256 to 2000 in multiples of 16."
        !automatic && (steps !in 4..12 || width.toLong() * height * steps > 2000L * 2000 * 8) -> "Use 4–12 steps; at 2000×2000 the maximum is 8."
        !automatic && seed != null && seed < 0 -> "Seed must be a non-negative integer."
        references.size > 2 -> "Select at most two reference images."
        else -> null
    }
}

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
