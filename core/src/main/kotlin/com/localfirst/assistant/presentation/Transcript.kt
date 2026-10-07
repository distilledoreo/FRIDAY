package com.localfirst.assistant.presentation

import com.localfirst.assistant.conversation.Attachment
import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.tools.SourceLink
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What the chat screen shows, derived from conversation messages. UI-framework
 * free so it can be unit-tested. Indexes point back into the message list.
 */
sealed interface TranscriptItem {
    val key: String

    data class User(
        val index: Int,
        val text: String,
        val attachments: List<Attachment> = emptyList(),
    ) : TranscriptItem {
        override val key = "u$index"
    }

    data class Assistant(
        val index: Int,
        val text: String,
        /** Still receiving text. */
        val streaming: Boolean,
        /** The latest answer in the chat, which can be regenerated. */
        val isLatest: Boolean,
    ) : TranscriptItem {
        override val key = "a$index"
    }

    /** Consecutive tool calls with their results, shown as one card. [index] is the first call's position. */
    data class ToolActivity(val index: Int, val steps: List<ToolStep>) : TranscriptItem {
        override val key = "t$index"
    }

    /** The model is working and nothing visible has arrived yet. */
    data object Thinking : TranscriptItem {
        override val key = "thinking"
    }
}

data class ToolStep(
    val callId: String,
    val name: String,
    val state: ToolStepState,
    /** Short sentence for the card, e.g. "Searched the web for “SearXNG”". */
    val label: String,
    /** Failure reason when [state] is [ToolStepState.FAILED]. */
    val detail: String? = null,
    val sources: List<SourceLink> = emptyList(),
)

enum class ToolStepState { RUNNING, DONE, FAILED }

object Transcript {
    fun items(messages: List<Message>, busy: Boolean): List<TranscriptItem> {
        val out = mutableListOf<TranscriptItem>()
        val pendingSteps = mutableListOf<ToolStep>()
        var pendingStart = -1
        fun flushSteps() {
            if (pendingSteps.isNotEmpty()) {
                out += TranscriptItem.ToolActivity(pendingStart, pendingSteps.toList())
                pendingSteps.clear()
            }
        }

        val lastAssistantIndex = messages.indexOfLast { it is Message.Assistant && it.content.isNotBlank() }
        messages.forEachIndexed { index, message ->
            when (message) {
                is Message.System -> Unit
                is Message.User -> {
                    flushSteps()
                    out += TranscriptItem.User(index, message.content, message.attachments)
                }
                is Message.Assistant -> {
                    if (message.content.isBlank()) return@forEachIndexed
                    flushSteps()
                    val last = index == messages.lastIndex
                    out += TranscriptItem.Assistant(
                        index = index,
                        text = message.content,
                        streaming = busy && last,
                        isLatest = index == lastAssistantIndex && messages.drop(index + 1).none { it is Message.User },
                    )
                }
                is Message.ToolCall -> {
                    if (pendingSteps.isEmpty()) pendingStart = index
                    pendingSteps += step(message, resultFor(messages, index))
                }
                is Message.ToolResult -> Unit
            }
        }
        flushSteps()

        if (busy && needsThinkingIndicator(out.lastOrNull())) out += TranscriptItem.Thinking
        return out
    }

    /**
     * The first result after the call at [callIndex] with the same id. Ids are
     * matched by position because servers that omit ids get "call_0" each round.
     */
    private fun resultFor(messages: List<Message>, callIndex: Int): Message.ToolResult? {
        val id = (messages[callIndex] as Message.ToolCall).id
        for (i in callIndex + 1 until messages.size) {
            val message = messages[i]
            if (message is Message.User) return null
            if (message is Message.ToolResult && message.toolCallId == id) return message
        }
        return null
    }

    private fun needsThinkingIndicator(last: TranscriptItem?): Boolean = when (last) {
        null, is TranscriptItem.User -> true
        is TranscriptItem.ToolActivity -> last.steps.none { it.state == ToolStepState.RUNNING }
        is TranscriptItem.Assistant -> !last.streaming
        TranscriptItem.Thinking -> false
    }

    internal fun step(call: Message.ToolCall, result: Message.ToolResult?): ToolStep {
        val args = try {
            JsonCodec.json.parseToJsonElement(call.argumentsJson) as? JsonObject
        } catch (e: Exception) {
            null
        }
        val state = when {
            result == null -> ToolStepState.RUNNING
            result.success -> ToolStepState.DONE
            else -> ToolStepState.FAILED
        }
        val label = ToolLabels.label(call.name, args ?: JsonObject(emptyMap()), state)
        return ToolStep(
            callId = call.id,
            name = call.name,
            state = state,
            label = label,
            detail = result?.takeIf { !it.success }?.content,
            sources = result?.sources.orEmpty(),
        )
    }
}

/** Drawer sections, newest first, like "Today" and "Previous 7 days". */
object ConversationGroups {
    const val TODAY = "Today"
    const val YESTERDAY = "Yesterday"
    const val LAST_WEEK = "Previous 7 days"
    const val LAST_MONTH = "Previous 30 days"
    const val OLDER = "Older"

    /**
     * [startOfToday] is local midnight in epoch millis; passing it in keeps
     * this independent of the device clock and time zone.
     */
    fun label(updatedAt: Long, startOfToday: Long): String {
        val day = 24L * 60 * 60 * 1000
        return when {
            updatedAt >= startOfToday -> TODAY
            updatedAt >= startOfToday - day -> YESTERDAY
            updatedAt >= startOfToday - 7 * day -> LAST_WEEK
            updatedAt >= startOfToday - 30 * day -> LAST_MONTH
            else -> OLDER
        }
    }
}

/** Card text for each tool, in running / done / failed form. Unknown tools get a generic label. */
internal object ToolLabels {
    fun label(name: String, args: JsonObject, state: ToolStepState): String {
        fun str(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun int(key: String) = (args[key] as? JsonPrimitive)?.intOrNull
        fun quoted(key: String) = str(key)?.let { "“$it”" }
        fun pick(running: String, done: String, failed: String) = when (state) {
            ToolStepState.RUNNING -> "$running…"
            ToolStepState.DONE -> done
            ToolStepState.FAILED -> failed
        }

        return when (name) {
            "web_search" -> {
                val subject = quoted("query")?.let { " for $it" }.orEmpty()
                pick("Searching the web$subject", "Searched the web$subject", "Web search failed$subject")
            }
            "set_media_volume" -> {
                val amount = int("level")?.let { " to $it%" }.orEmpty()
                pick("Setting media volume$amount", "Set media volume$amount", "Couldn't set media volume$amount")
            }
            "open_app" -> {
                val app = str("name") ?: "the app"
                pick("Opening $app", "Opened $app", "Couldn't open $app")
            }
            "open_url" -> {
                val site = str("url")?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() } ?: "the page"
                pick("Opening $site", "Opened $site", "Couldn't open $site")
            }
            "open_maps" -> {
                val place = str("query") ?: "the place"
                if ((args["navigate"] as? JsonPrimitive)?.contentOrNull == "true") {
                    pick("Starting navigation to $place", "Started navigation to $place", "Couldn't start navigation to $place")
                } else {
                    pick("Showing $place on the map", "Showed $place on the map", "Couldn't show $place on the map")
                }
            }
            "media_control" -> {
                val done = when (str("action")) {
                    "play" -> "Resumed playback"
                    "pause" -> "Paused playback"
                    "next" -> "Skipped to the next track"
                    "previous" -> "Went back a track"
                    else -> "Toggled playback"
                }
                pick("Controlling playback", done, "Couldn't control playback")
            }
            "play_music" -> {
                val what = quoted("query") ?: "music"
                pick("Asking Spotify to play $what", "Playing $what on Spotify", "Couldn't play $what")
            }
            "now_playing" -> pick("Checking what's playing", "Checked what's playing", "Couldn't check what's playing")
            "set_alarm" -> {
                val time = clockTime(int("hour"), int("minute"))?.let { " for $it" }.orEmpty()
                pick("Setting an alarm$time", "Set an alarm$time", "Couldn't set the alarm$time")
            }
            "set_timer" -> {
                val seconds = (int("hours") ?: 0) * 3600 + (int("minutes") ?: 0) * 60 + (int("seconds") ?: 0)
                val length = duration(seconds)?.let { " $it" }.orEmpty()
                pick("Starting a$length timer", "Started a$length timer", "Couldn't start the$length timer")
            }
            "flashlight" -> {
                val on = (args["on"] as? JsonPrimitive)?.contentOrNull != "false"
                val word = if (on) "on" else "off"
                pick("Turning the flashlight $word", "Turned the flashlight $word", "Couldn't turn the flashlight $word")
            }
            "battery_status" -> pick("Checking the battery", "Checked the battery", "Couldn't check the battery")
            "search_contacts" -> {
                val who = quoted("query")?.let { " for $it" }.orEmpty()
                pick("Looking up contacts$who", "Looked up contacts$who", "Couldn't look up contacts$who")
            }
            "place_call" -> {
                val who = str("to") ?: "the contact"
                pick("Calling $who", "Called $who", "Didn't call $who")
            }
            "send_text" -> {
                val who = str("to") ?: "the contact"
                pick("Texting $who", "Texted $who", "Didn't text $who")
            }
            "add_calendar_event" -> {
                val what = quoted("title") ?: "the event"
                pick("Adding $what to your calendar", "Added $what to your calendar", "Couldn't add $what to your calendar")
            }
            "upcoming_events" -> pick("Checking your calendar", "Checked your calendar", "Couldn't read your calendar")
            else -> pick("Running $name", "Used $name", "$name failed")
        }
    }

    private fun clockTime(hour: Int?, minute: Int?): String? {
        if (hour == null || hour !in 0..23) return null
        val m = (minute ?: 0).coerceIn(0, 59)
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "$h12:${m.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
    }

    private fun duration(seconds: Int): String? = when {
        seconds <= 0 -> null
        seconds < 60 -> "$seconds sec"
        seconds % 3600 == 0 -> "${seconds / 3600} hr"
        seconds % 60 == 0 && seconds < 3600 -> "${seconds / 60} min"
        seconds >= 3600 -> "${seconds / 3600} hr ${seconds % 3600 / 60} min"
        else -> "${seconds / 60} min ${seconds % 60} sec"
    }
}
