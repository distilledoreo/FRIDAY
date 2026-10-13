package com.localfirst.assistant.client

import kotlinx.serialization.json.*

/** Image mode's choices: automatic lets the model pick size and steps; manual values override it. */
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

    val summary get() = if (automatic) "Auto" else "${width}×${height}"

    /** The fields an image request must use, overriding the model's; null when the model chooses everything. */
    fun overrides(randomSeed: () -> Int = { kotlin.random.Random.nextInt(Int.MAX_VALUE) }): JsonObject? = when {
        !automatic -> buildJsonObject {
            put("width", width); put("height", height); put("steps", steps); put("transparent", transparent); put("seed", seed ?: randomSeed())
            putJsonArray("reference_file_ids") { references.forEach { add(it) } }
        }
        references.isNotEmpty() -> buildJsonObject { putJsonArray("reference_file_ids") { references.forEach { add(it) } } }
        else -> null
    }

    companion object {
        /** Image mode sends the description as an explicit image request. */
        fun request(description: String) = "Generate an image: $description"
    }
}

/** A fixed public-research plan from the draft, proposed for approval in FRIDAY and reported back for discussion. */
object DeepResearch {
    fun proposal(query: String): Result<Pair<String, List<String>>> {
        val text = query.trim()
        val grounding = com.localfirst.assistant.grounding.GroundingPrecheck
        return when {
            text.isEmpty() -> Result.failure(IllegalArgumentException("Write the research question first."))
            grounding.blocksWeb(text) || grounding.privateRequest(text) -> Result.failure(IllegalArgumentException("This looks private: public deep research needs a version without personal details."))
            text.length > 7900 -> Result.failure(IllegalArgumentException("This research prompt exceeds the task limit. Use a focused public question."))
            else -> Result.success("Research this thoroughly: $text" to listOf("Find primary authoritative sources", "Read the relevant passages and dates",
                "Check material claims, conflicting evidence and limits", "Write a structured report with citations and uncertainty"))
        }
    }
}
