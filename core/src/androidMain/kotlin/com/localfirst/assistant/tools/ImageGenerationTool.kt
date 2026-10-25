package com.localfirst.assistant.tools

import com.localfirst.assistant.json.JsonCodec
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Poll short requests while the GPU changes hands; never hold one long HTTP connection. */
class ImageGenerationTool(private val client: WorkspaceGateway, private val pause: suspend () -> Unit = { delay(1000) }) : Tool {
    override val name = "generate_image"
    override val description = "Generate or edit one image using local Qwen Image 2.1. Choose resolution/aspect ratio/steps and relevant uploaded reference_file_ids from the user's request. To change an image from this chat, including one you generated, pass its file id (the id in its assistant://artifact/<id> link) in reference_file_ids and describe the edit, so the rest of the picture stays the same. Defaults 768x768, 8 Euler steps. Dimensions must be multiples of 16, each 256–2000; maximum 2000x2000 at 8 steps, up to 12 steps for smaller images. At most 2 references; they are resized to 512px. A hard 15-minute render limit prevents runaway runs. User manual settings override your choices. Chat pauses while the GPU swaps, then resumes with this same transcript. Link the returned image artifact. Do not claim completion unless status is completed."
    override val inputSchema = JsonCodec.json.parseToJsonElement("""{"type":"object","properties":{"prompt":{"type":"string","maxLength":4000},"width":{"type":"integer","minimum":256,"maximum":2000},"height":{"type":"integer","minimum":256,"maximum":2000},"steps":{"type":"integer","minimum":4,"maximum":12},"seed":{"type":"integer","minimum":0,"maximum":2147483647},"reference_file_ids":{"type":"array","maxItems":2,"items":{"type":"string"}},"transparent":{"type":"boolean"}},"required":["prompt"],"additionalProperties":false}""").jsonObject
    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val id = UUID.randomUUID().toString().replace("-", "")
        // The renderer works in multiples of 32 (it rounds 1008 up to 1024): snap so the size asked for is the size made.
        val sized = listOf("width", "height").mapNotNull { side ->
            arguments[side]?.jsonPrimitive?.intOrNull?.let { side to JsonPrimitive(snapImageSide(it)) }
        }
        val body = JsonObject(arguments + sized + ("id" to JsonPrimitive(id)))
        try {
            // Same client-generated id makes an uncertain submission safe to repeat.
            try { client.toolRequest("/workspace/images", "POST", body.toString()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                try { client.toolRequest("/workspace/images/$id") }
                catch (_: Exception) { throw e }
            }
            // Up to 15 minutes of rendering plus the GPU handing back and forth.
            repeat(25 * 60) {
                val raw = client.toolRequest("/workspace/images/$id")
                val job = JsonCodec.json.parseToJsonElement(raw).jsonObject
                when (job["status"]?.jsonPrimitive?.content) {
                    "completed" -> return ToolExecutionResult(true, raw)
                    "failed", "cancelled" -> return ToolExecutionResult(false, raw)
                }
                pause()
            }
            return ToolExecutionResult(false, "Image job $id is still restoring chat. Check Images for its result; do not submit it again.")
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { client.toolRequest("/workspace/images/$id/cancel", "POST", "{}") } }
            throw e
        }
    }
}

/** The nearest multiple of 32 within the renderer's 256–2000 range (1984 is the largest). */
internal fun snapImageSide(value: Int): Int = (Math.round(value / 32.0) * 32).toInt().coerceIn(256, 1984)
