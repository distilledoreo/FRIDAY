package com.localfirst.assistant.tools

import com.localfirst.assistant.json.JsonCodec
import kotlinx.serialization.json.*

object CodeToolNames {
    const val START = "start_code_task"
    const val GET = "get_code_task"
    const val LIST = "list_code_projects"
    /** Tools whose successful result is a coding task shown as a card that opens Code. */
    val CARDS = setOf(START, GET)
}

private val REPO = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
private val TASK_ID = Regex("[a-f0-9]{12}")

/**
 * Hands coding work from chat to the Code workspace: a GitHub repository on the PC, a new branch per task.
 * The conversation itself, diffs and pull requests stay in Code; chat gets a card that opens it.
 */
fun codeTools(client: WorkspaceGateway): List<Tool> = listOf(
    object : Tool {
        override val name = CodeToolNames.START
        override val description = "Start a coding task in one of the user's GitHub repositories, like Codex or Claude Code: FRIDAY's coding " +
            "agent works on the user's PC in a fresh branch and worktree, then the user reviews the diff and opens a pull request from Code. " +
            "Use for requests to change code in a named repository (owner/name). Write the prompt as a complete brief for an engineer. " +
            "Requires confirmation. Nothing is pushed until the user asks."
        override val requiresConfirmation = true
        override val inputSchema = JsonCodec.json.parseToJsonElement("""{"type":"object","properties":{"repo":{"type":"string","description":"owner/name on GitHub"},"prompt":{"type":"string","minLength":1,"maxLength":20000},"base":{"type":"string","maxLength":200,"description":"Base branch; the repository's default when omitted"}},"required":["repo","prompt"],"additionalProperties":false}""").jsonObject
        override suspend fun confirmationPrompt(arguments: JsonObject) =
            "Start a coding task in ${arguments["repo"]?.jsonPrimitive?.contentOrNull.orEmpty()}?\n\n${arguments["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()}\n\n" +
                "FRIDAY works on a new branch on your PC. Nothing is pushed until you create the pull request in Code."
        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            val repo = arguments["repo"]?.jsonPrimitive?.contentOrNull?.trim()?.removePrefix("https://github.com/")?.removeSuffix(".git")?.trim('/').orEmpty()
            require(REPO.matches(repo) && ".." !in repo) { "Use the repository as owner/name" }
            val prompt = arguments.getValue("prompt").jsonPrimitive.content.trim()
            require(prompt.isNotEmpty() && prompt.length <= 20000)
            val project = JsonCodec.json.parseToJsonElement(client.toolRequest("/workspace/code/projects", "POST",
                buildJsonObject { put("repo", repo) }.toString())).jsonObject
            val body = buildJsonObject {
                put("project", project.getValue("id").jsonPrimitive.content); put("prompt", prompt)
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
    listOf("id", "title", "repo", "branch", "base", "state", "pr_url").forEach { key -> view[key]?.let { put(key, it) } }
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
}
