package com.localfirst.assistant.tools

import com.localfirst.assistant.json.JsonCodec
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Queue a video on the computer and answer at once: renders take minutes, and the Videos screen shows the result. */
class VideoGenerationTool(private val client: WorkspaceGateway) : Tool {
    override val name = "generate_video"
    override val description = "Queue a short video with sound using local LTX-2.5 and return at once; it renders for minutes and appears in the Videos screen. duration_seconds 2-26. aspect 16:9, 9:16, 1:1, 4:3 or 3:4. quality 0 (fastest, keeps chat running) to 4 (largest size the GPUs fit for that length; may pause chat for a long time). Use 0 unless the user asks for higher quality. Longer videos get smaller sizes. Describe motion, camera and sound in the prompt. Tell the user the returned size and time; do not claim the video is finished."
    override val inputSchema = JsonCodec.json.parseToJsonElement("""{"type":"object","properties":{"prompt":{"type":"string","maxLength":4000},"duration_seconds":{"type":"integer","minimum":2,"maximum":26},"aspect":{"type":"string","enum":["16:9","9:16","1:1","4:3","3:4"]},"quality":{"type":"integer","minimum":0,"maximum":4},"seed":{"type":"integer","minimum":0,"maximum":2147483647},"negative_prompt":{"type":"string","maxLength":1000}},"required":["prompt"],"additionalProperties":false}""").jsonObject
    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val id = UUID.randomUUID().toString().replace("-", "")
        val known = setOf("prompt", "duration_seconds", "aspect", "quality", "seed", "negative_prompt")
        val body = JsonObject(arguments.filterKeys { it in known } + ("id" to JsonPrimitive(id)))
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
