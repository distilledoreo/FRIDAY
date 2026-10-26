package com.localfirst.assistant.tools

import com.localfirst.assistant.json.JsonCodec
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Queue a video on the computer and answer at once: renders take minutes, and the Videos screen shows the result. */
class VideoGenerationTool(private val client: WorkspaceGateway) : Tool {
    override val name = "generate_video"
    override val description = "Queue a short video with sound using local LTX-2.5 and return at once; it renders for minutes and appears in the Videos screen. duration_seconds 2-26. aspect 16:9, 9:16, 1:1, 4:3 or 3:4. quality 0 (fastest, keeps chat running) to 4 (largest size the GPUs fit for that length; may pause chat for a long time). Use 0 unless the user asks for higher quality. Longer videos get smaller sizes. Describe motion, camera and sound in the prompt. Optional files from this chat, by the id in their assistant://artifact/<id> link: start_image (animate a photo), end_image, keyframes (images with at_seconds), source_video (continue a video), audio (keep this soundtrack, e.g. speech or music to match). A start image and a source video can't be combined. Tell the user the returned size and time; do not claim the video is finished."
    override val inputSchema = JsonCodec.json.parseToJsonElement("""{"type":"object","properties":{"prompt":{"type":"string","maxLength":4000},"duration_seconds":{"type":"integer","minimum":2,"maximum":26},"aspect":{"type":"string","enum":["16:9","9:16","1:1","4:3","3:4"]},"quality":{"type":"integer","minimum":0,"maximum":4},"seed":{"type":"integer","minimum":0,"maximum":2147483647},"negative_prompt":{"type":"string","maxLength":1000},"start_image":{"type":"string"},"end_image":{"type":"string"},"source_video":{"type":"string"},"audio":{"type":"string"},"keyframes":{"type":"array","maxItems":4,"items":{"type":"object","properties":{"file_id":{"type":"string"},"at_seconds":{"type":"number"}},"required":["file_id","at_seconds"],"additionalProperties":false}}},"required":["prompt"],"additionalProperties":false}""").jsonObject
    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val id = UUID.randomUUID().toString().replace("-", "")
        val known = setOf("prompt", "duration_seconds", "aspect", "quality", "seed", "negative_prompt")
        // Files from the chat become the request's inputs: frames, keyframes at their times, a clip to continue, a soundtrack.
        val inputs = buildJsonArray {
            for (kind in listOf("start_image", "end_image", "source_video", "audio")) {
                arguments[kind]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { add(buildJsonObject { put("kind", kind); put("file_id", it) }) }
            }
            (arguments["keyframes"] as? JsonArray)?.forEach { frame ->
                val o = frame as? JsonObject ?: return@forEach
                val file = o["file_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val at = o["at_seconds"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
                add(buildJsonObject { put("kind", "keyframe"); put("file_id", file); put("at_seconds", at) })
            }
        }
        val body = JsonObject(arguments.filterKeys { it in known } + ("id" to JsonPrimitive(id)) +
            (if (inputs.isEmpty()) emptyMap() else mapOf("inputs" to inputs)))
        val raw = try { client.toolRequest("/workspace/videos", "POST", body.toString()) }
            catch (e: CancellationException) { throw e }
            // The same id makes an uncertain submission safe to check instead of repeating.
            catch (e: Exception) { try { client.toolRequest("/workspace/videos/$id") } catch (_: Exception) { throw e } }
        val job = JsonCodec.json.parseToJsonElement(raw).jsonObject
        val plan = job["plan"] as? JsonObject
        fun int(key: String) = plan?.get(key)?.jsonPrimitive?.intOrNull
        val minutes = ((int("estimate_seconds") ?: 60) + 30) / 60
        val chat = if (plan?.get("pauses_chat")?.jsonPrimitive?.booleanOrNull == true) "Chat and voice pause while it renders on both GPUs, then resume." else "Chat keeps working meanwhile."
        val note = "Queued: ${int("width")}x${int("height")}, about ${minutes.coerceAtLeast(1)} min. It appears in Videos when done. $chat"
        return ToolExecutionResult(true, buildJsonObject { put("id", id); put("status", job["status"] ?: JsonPrimitive("queued")); plan?.let { put("plan", it) }; put("note", note) }.toString())
    }
}
