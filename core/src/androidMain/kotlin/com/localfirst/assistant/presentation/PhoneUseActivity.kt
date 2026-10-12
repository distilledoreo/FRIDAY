package com.localfirst.assistant.presentation

import com.localfirst.assistant.tools.phoneuse.PhoneUseToolNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * How a run of phone-use steps is summarized in the chat, following Codex's computer-activity cell:
 * "Using phone · 6 actions" while running, "Used phone · 6 actions · 1 failed" after. While active the
 * preview shows the running step; afterwards it favors failures and screenshots without reordering
 * steps, showing three rows (two when there are more than three, plus "N more").
 */
data class PhoneUseSummary(
    val active: Boolean,
    val header: String,
    val failures: Int,
    /** Indices into the steps, in their original order. */
    val preview: List<Int>,
    val hidden: Int,
    /** The newest screenshot in the run, for the card's thumbnail. */
    val latestImage: String?,
)

object PhoneUseActivity {
    fun isPhoneUse(step: ToolStep) = step.name in PhoneUseToolNames.ALL

    fun summarize(steps: List<ToolStep>): PhoneUseSummary {
        val active = steps.indexOfLast { it.state == ToolStepState.RUNNING }.takeIf { it >= 0 }
        val failures = steps.count { it.state == ToolStepState.FAILED }
        val count = steps.size
        val unit = if (count == 1) "action" else "actions"
        val app = steps.asReversed().firstNotNullOfOrNull { it.app }
        val header = buildString {
            append(if (active != null) "Using " else "Used ")
            append(if (app != null) app else "phone")
            append(" · $count $unit")
            if (failures > 0) append(" · $failures failed")
        }
        val preview = if (active != null) listOf(active) else {
            steps.indices.sortedByDescending { i ->
                val step = steps[i]
                // Failures first, then steps with a screenshot, then the most recent.
                (if (step.state == ToolStepState.FAILED) 2_000_000 else 0) + (if (step.image != null) 1_000_000 else 0) + i
            }.take(if (count > 3) 2 else 3).sorted()
        }
        return PhoneUseSummary(
            active = active != null,
            header = header,
            failures = failures,
            preview = preview,
            hidden = if (active != null) 0 else count - preview.size,
            latestImage = steps.lastOrNull { it.image != null }?.image,
        )
    }
}

/** Card text for phone-use steps. Finished actions use the first line of their own result. */
internal object PhoneUseLabels {
    fun label(name: String, args: JsonObject, state: ToolStepState, result: String?): String {
        fun str(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val app = str("app") ?: "the app"
        val outcome = result?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(140)
        if (name == PhoneUseToolNames.SCRIPT) {
            // Scripts carry a title for the user, like Codex's computer-use calls.
            val title = str("title") ?: "Running a phone script"
            return when (state) {
                ToolStepState.RUNNING -> "$title…"
                ToolStepState.DONE -> title
                ToolStepState.FAILED -> "Failed: $title — " + (result?.lineSequence()?.firstOrNull { it.isNotBlank() }?.removePrefix("The script stopped: ")?.take(100) ?: "")
            }
        }
        return when (state) {
            ToolStepState.RUNNING -> when (name) {
                PhoneUseToolNames.LIST_APPS -> "Listing apps…"
                PhoneUseToolNames.GET_APP_STATE -> "Looking at $app…"
                PhoneUseToolNames.CLICK -> "Tapping in $app…"
                PhoneUseToolNames.TYPE_TEXT -> "Typing in $app…"
                PhoneUseToolNames.PRESS_KEY -> "Pressing ${str("key") ?: "a key"}…"
                PhoneUseToolNames.SCROLL -> "Scrolling ${str("direction") ?: ""} in $app…".replace("  ", " ")
                PhoneUseToolNames.DRAG -> "Dragging in $app…"
                PhoneUseToolNames.SET_VALUE -> "Setting a value in $app…"
                PhoneUseToolNames.SECONDARY_ACTION -> "${str("action") ?: "Acting"} in $app…"
                else -> "Using $app…"
            }
            ToolStepState.DONE -> when (name) {
                PhoneUseToolNames.LIST_APPS -> "Listed apps"
                PhoneUseToolNames.GET_APP_STATE -> {
                    val window = result?.lineSequence()?.firstOrNull { it.startsWith("Window: ") }?.removePrefix("Window: ")
                    "Looked at $app" + (window?.let { " · $it" } ?: "")
                }
                else -> outcome?.removeSuffix(".") ?: "Used $app"
            }
            ToolStepState.FAILED -> "Failed: " + (outcome ?: name)
        }
    }

    /** The app a step worked in, for the activity header. */
    fun app(args: JsonObject): String? = (args["app"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
