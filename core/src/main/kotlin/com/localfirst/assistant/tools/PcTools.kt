package com.localfirst.assistant.tools

import com.localfirst.assistant.json.JsonCodec
import kotlinx.serialization.json.*

/** Models can request/read PC work. Human permission replies are intentionally absent. */
fun pcTools(client: WorkspaceGateway): List<Tool> = listOf(
    object : Tool {
        override val name = "run_on_pc"
        override val description = "Run a task on the user's actual PC through FRIDAY's native OpenCode, with real shell and desktop access governed by Computer access settings. Free cloud models see the requested task and relevant command/file/screen results; local Qwen is the text-only fallback. Requires confirmation. The task runs asynchronously and shows as a card in this chat with live status; commands needing approval appear as cards here and in FRIDAY, and the phone is notified. Use get_pc_task to read progress and results. Use this for computer work instead of the isolated research agent. Never approve permissions yourself."
        override val requiresConfirmation = true
        override val inputSchema = JsonCodec.json.parseToJsonElement("""{"type":"object","properties":{"prompt":{"type":"string","minLength":1,"maxLength":20000},"title":{"type":"string","maxLength":200}},"required":["prompt"],"additionalProperties":false}""").jsonObject
        override suspend fun confirmationPrompt(arguments: JsonObject) =
            "Start this task on your PC?\n\n${arguments["prompt"]?.jsonPrimitive?.content.orEmpty()}\n\nFree cloud models may see relevant PC output and screenshots. Your Computer access settings control command and desktop approvals."
        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            val prompt = arguments.getValue("prompt").jsonPrimitive.content.trim()
            require(prompt.isNotEmpty() && prompt.length <= 20000)
            val body = buildJsonObject {
                put("prompt", prompt); put("source", "chat")
                arguments["title"]?.jsonPrimitive?.contentOrNull?.let { require(it.length <= 200); put("title", it) }
            }
            return ToolExecutionResult(true, client.toolRequest("/workspace/pc/sessions", "POST", body.toString()))
        }
    },
    object : Tool {
        override val name = "get_pc_task"
        override val description = "After confirmation, read a FRIDAY PC task's actual progress and results into this model conversation. Private command/file output may be included and saved in normal history. The user answers permissions and questions on cards in this chat or in FRIDAY; this tool cannot approve or continue an action."
        override val requiresConfirmation = true
        override val inputSchema = JsonCodec.json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"string","maxLength":100}},"required":["id"],"additionalProperties":false}""").jsonObject
        override suspend fun confirmationPrompt(arguments: JsonObject) = "Read this PC task's results into the selected model and this conversation?\n\n${arguments["id"]?.jsonPrimitive?.content.orEmpty()}"
        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            val id = arguments.getValue("id").jsonPrimitive.content
            require(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid PC task id" }
            return ToolExecutionResult(true, client.toolRequest("/workspace/pc/sessions/$id"))
        }
    },
)
