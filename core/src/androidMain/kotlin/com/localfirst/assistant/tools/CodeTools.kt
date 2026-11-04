package com.localfirst.assistant.tools

import com.localfirst.assistant.json.JsonCodec
import kotlinx.serialization.json.*



private val REPO = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
private val TASK_ID = Regex("[a-f0-9]{12}")

/**
 * Hands coding work from chat to the Code workspace: a GitHub repository on the PC, a new branch per task.
 * The conversation itself, diffs and pull requests stay in Code; chat gets a card that opens it.
 */
fun codeTools(client: WorkspaceGateway): List<Tool> = listOf(
    object : Tool {
        override val name = CodeToolNames.START
        override val description = "Start a coding task in a GitHub repository or existing local Git folder, like Codex or Claude Code: FRIDAY's coding " +
            "agent works on the user's PC in a fresh branch and worktree, then the user reviews the diff and opens a pull request from Code. " +
            "Use for requests to change code in a named repository (owner/name). Write the prompt as a complete brief for an engineer. " +
            "Only delegate when the user requests coding work or asks you to orchestrate it. Default to the local model; use auto if the user requests automatic cloud selection. " +
            "Requires confirmation. Nothing is pushed until the user asks."
        override val requiresConfirmation = true
        override val inputSchema = JsonCodec.json.parseToJsonElement("""{"type":"object","properties":{"project":{"type":"string","description":"Saved project id from list_code_projects; choose project, repo or path"},"repo":{"type":"string","description":"owner/name on GitHub; choose repo or path"},"path":{"type":"string","description":"Absolute existing Git repository folder on the PC"},"mode":{"type":"string","enum":["code","plan"],"description":"plan disables shell commands, edits and delegation until approved in Code"},"prompt":{"type":"string","minLength":1,"maxLength":20000},"base":{"type":"string","maxLength":200,"description":"Base branch; the repository's default when omitted"},"model_choice":{"type":"string","enum":["local","auto"],"description":"local by default; auto uses the automatic cloud chain with local fallback"}},"required":["prompt"],"additionalProperties":false}""").jsonObject
        override suspend fun confirmationPrompt(arguments: JsonObject) =
            "Start a coding task in ${arguments["path"]?.jsonPrimitive?.contentOrNull ?: arguments["repo"]?.jsonPrimitive?.contentOrNull.orEmpty()}?\n\n${arguments["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()}\n\n" +
                "FRIDAY works on a new branch on your PC. Nothing is pushed until you create the pull request in Code."
        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            val repo = arguments["repo"]?.jsonPrimitive?.contentOrNull?.trim()?.removePrefix("https://github.com/")?.removeSuffix(".git")?.trim('/').orEmpty()
            val path = arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val savedProject = arguments["project"]?.jsonPrimitive?.contentOrNull.orEmpty()
            require(listOf(path, repo, savedProject).count { it.isNotBlank() } == 1) { "Choose a saved project, repo or PC repository path" }
            require(savedProject.isBlank() || TASK_ID.matches(savedProject)) { "Invalid saved project id" }
            require(path.isBlank() || (path.startsWith('/') && path.length <= 4096)) { "Use an absolute PC repository path" }
            require(repo.isBlank() || (REPO.matches(repo) && ".." !in repo)) { "Use the repository as owner/name" }
            val prompt = arguments.getValue("prompt").jsonPrimitive.content.trim()
            require(prompt.isNotEmpty() && prompt.length <= 20000)
            val choice = arguments["model_choice"]?.jsonPrimitive?.contentOrNull ?: "local"
            require(choice in setOf("local", "auto")) { "Invalid coding model choice" }
            val mode = arguments["mode"]?.jsonPrimitive?.contentOrNull ?: "code"
            require(mode in setOf("code", "plan")) { "Invalid coding task mode" }
            val project = if (savedProject.isNotBlank()) buildJsonObject { put("id", savedProject) } else JsonCodec.json.parseToJsonElement(client.toolRequest("/workspace/code/projects", "POST",
                buildJsonObject { if (path.isNotBlank()) put("path", path) else put("repo", repo) }.toString())).jsonObject
            val body = buildJsonObject {
                put("mode", mode)
                put("project", project.getValue("id").jsonPrimitive.content); put("prompt", prompt); put("model_choice", choice)
                arguments["base"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 200 }?.let { put("base", it) }
            }
            return ToolExecutionResult(true, client.toolRequest("/workspace/code/tasks", "POST", body.toString()))
        }
    },
    object : Tool {
        override val name = CodeToolNames.GET
        override val description = "After confirmation, read a coding task's status, its agent's latest summary, changed files and pull request into this conversation."
        override val requiresConfirmation = true
        override val inputSchema = JsonCodec.json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"string","maxLength":12}},"required":["id"],"additionalProperties":false}""").jsonObject
        override suspend fun confirmationPrompt(arguments: JsonObject) = "Read this coding task's results into the conversation?"
        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            val id = arguments.getValue("id").jsonPrimitive.content
            require(TASK_ID.matches(id)) { "Invalid coding task id" }
            val view = JsonCodec.json.parseToJsonElement(client.toolRequest("/workspace/code/tasks/$id")).jsonObject
            return ToolExecutionResult(true, condense(view).toString())
        }
    },
    object : Tool {
        override val name = CodeToolNames.FOLLOW_UP
        override val description = "Send a follow-up brief to an existing coding task when the user requests it or explicitly asks you to orchestrate that task. Use delivery=queue to wait for the current turn, or delivery=steer to stop and change direction only when the user explicitly requests it. Preserves model choice and permissions; never bypass approvals."
        override val requiresConfirmation = true
        override val inputSchema = JsonCodec.json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"string","pattern":"^[a-f0-9]{12}$"},"prompt":{"type":"string","minLength":1,"maxLength":20000},"delivery":{"type":"string","enum":["send","queue","steer"]}},"required":["id","prompt"],"additionalProperties":false}""").jsonObject
        override suspend fun confirmationPrompt(arguments: JsonObject) = "${arguments["delivery"]?.jsonPrimitive?.contentOrNull ?: "send"} this brief to coding task ${arguments["id"]?.jsonPrimitive?.contentOrNull}? Steer stops the current turn.\n\n${arguments["prompt"]?.jsonPrimitive?.contentOrNull}"
        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            val id = arguments.getValue("id").jsonPrimitive.content
            require(TASK_ID.matches(id)) { "Invalid coding task id" }
            val prompt = arguments.getValue("prompt").jsonPrimitive.content.trim()
            require(prompt.isNotEmpty() && prompt.length <= 20000) { "Provide a coding brief of up to 20000 characters" }
            val delivery = arguments["delivery"]?.jsonPrimitive?.contentOrNull ?: "send"
            require(delivery in setOf("send", "queue", "steer")) { "Choose send, queue or steer" }
            val action = when (delivery) { "queue" -> "queue"; "steer" -> "steer"; else -> "messages" }
            return ToolExecutionResult(true, client.toolRequest("/workspace/code/tasks/$id/$action", "POST", buildJsonObject { put("prompt", prompt) }.toString()))
        }
    },
    object : Tool {
        override val name = CodeToolNames.STOP
        override val description = "Stop an existing coding task when the user requests stopping it, or explicitly authorizes stopping as part of orchestration. Keeps the task's branch and changes; does not discard work."
        override val requiresConfirmation = true
        override val inputSchema = JsonCodec.json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"string","pattern":"^[a-f0-9]{12}$"}},"required":["id"],"additionalProperties":false}""").jsonObject
        override suspend fun confirmationPrompt(arguments: JsonObject) = "Stop coding task ${arguments["id"]?.jsonPrimitive?.contentOrNull}? Its changes will be kept."
        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            val id = arguments.getValue("id").jsonPrimitive.content
            require(TASK_ID.matches(id)) { "Invalid coding task id" }
            return ToolExecutionResult(true, client.toolRequest("/workspace/code/tasks/$id/abort", "POST", "{}"))
        }
    },
    object : Tool {
        override val name = CodeToolNames.LIST
        override val description = "List the GitHub repositories set up for coding and their recent coding tasks. Starts nothing."
        override val requiresConfirmation = false
        override val inputSchema = JsonCodec.json.parseToJsonElement("""{"type":"object","properties":{},"additionalProperties":false}""").jsonObject
        override suspend fun confirmationPrompt(arguments: JsonObject) = description
        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            val projects = JsonCodec.json.parseToJsonElement(client.toolRequest("/workspace/code/projects")).jsonArray
            val tasks = JsonCodec.json.parseToJsonElement(client.toolRequest("/workspace/code/tasks")).jsonArray
            val text = buildString {
                if (projects.isEmpty()) append("No repositories are set up for coding yet. start_code_task adds one.")
                else append("Repositories: ").append(projects.joinToString { it.jsonObject["repo"]?.jsonPrimitive?.contentOrNull.orEmpty() })
                tasks.take(15).forEach { task ->
                    val t = task.jsonObject
                    fun s(key: String) = t[key]?.jsonPrimitive?.contentOrNull.orEmpty()
                    append("\n- ").append(s("id")).append(": ").append(s("title")).append(" (").append(s("repo")).append(", ").append(s("state"))
                    s("pr_url").takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
                    append(')')
                }
            }
            return ToolExecutionResult(true, text)
        }
    },
)

/** The parts of a task view a model needs: status, the agent's last reply, files and pull request. */
internal fun condense(view: JsonObject): JsonObject = buildJsonObject {
    listOf("id", "title", "repo", "branch", "base", "state", "pr_url", "model_choice", "mode", "kind").forEach { key -> view[key]?.let { put(key, it) } }
    val session = view["session"] as? JsonObject
    session?.get("state")?.let { put("state", it) }
    val items = (session?.get("items") as? JsonArray).orEmpty().map { it.jsonObject }
    items.lastOrNull { it["kind"]?.jsonPrimitive?.contentOrNull == "text" && it["role"]?.jsonPrimitive?.contentOrNull == "assistant" }
        ?.get("text")?.jsonPrimitive?.contentOrNull?.let { put("summary", it.takeLast(6000)) }
    val pending = ((session?.get("permissions") as? JsonArray)?.size ?: 0) + ((session?.get("questions") as? JsonArray)?.size ?: 0)
    if (pending > 0) put("waiting_for_user", pending)
    (view["changes"] as? JsonObject)?.let { changes ->
        listOf("additions", "deletions", "commits", "uncommitted").forEach { key -> changes[key]?.let { put(key, it) } }
        put("files", JsonArray((changes["files"] as? JsonArray).orEmpty().take(60).map { file ->
            val f = file.jsonObject
            JsonPrimitive("${f["status"]?.jsonPrimitive?.contentOrNull} ${f["path"]?.jsonPrimitive?.contentOrNull} +${f["additions"]} -${f["deletions"]}")
        }))
    }
    (view["pr"] as? JsonObject)?.let { put("pull_request", it) }
    (view["verification"] as? JsonObject)?.let { verification ->
        put("verification", buildJsonObject {
            listOf("state", "reason", "scope").forEach { key -> verification[key]?.let { put(key, it) } }
            verification["required"]?.let { put("required", it) }
            put("receipts", JsonArray((verification["receipts"] as? JsonArray).orEmpty().take(5).map { receipt ->
                buildJsonObject { listOf("command", "cwd", "result", "current", "tests", "exit").forEach { key -> receipt.jsonObject[key]?.let { put(key, it) } } }
            }))
        })
    }
}
