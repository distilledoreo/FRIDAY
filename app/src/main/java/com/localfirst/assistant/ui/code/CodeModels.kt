package com.localfirst.assistant.ui.code

import org.json.JSONArray
import org.json.JSONObject

/** A GitHub repository FRIDAY has cloned on the PC for coding tasks. */
data class CodeProject(val id: String, val repo: String, val defaultBranch: String, val trusted: Boolean, val openTasks: Int) {
    val name: String get() = repo.substringAfter('/')
    companion object {
        fun from(j: JSONObject) = CodeProject(j.getString("id"), j.getString("repo"), j.optString("default_branch", "main"),
            j.optInt("trusted") == 1 || j.optBoolean("trusted"), j.optInt("open_tasks"))
    }
}

/** A repository the user can add, from their GitHub account. */
data class CodeRepo(val repo: String, val description: String, val private: Boolean, val defaultBranch: String) {
    companion object {
        fun from(j: JSONObject) = CodeRepo(j.getString("repo"), j.optString("description"), j.optBoolean("private"), j.optString("default_branch", "main"))
    }
}

data class CodeStats(val files: Int = 0, val additions: Int = 0, val deletions: Int = 0, val commits: Int = 0, val uncommitted: Int = 0) {
    val changed: Boolean get() = files > 0 || commits > 0
    companion object {
        fun from(j: JSONObject?) = if (j == null) CodeStats() else CodeStats(
            j.optJSONArray("files")?.length() ?: j.optInt("files"), j.optInt("additions"), j.optInt("deletions"), j.optInt("commits"), j.optInt("uncommitted"))
    }
}

/** One coding task: a branch in its own worktree, worked on by FRIDAY's coding agent. */
data class CodeTask(
    val id: String, val project: String, val repo: String, val title: String, val branch: String, val base: String,
    /** busy, waiting, idle, error, cancelled, interrupted or discarded. */
    val state: String, val prUrl: String?, val created: Double, val stats: CodeStats,
    val modelChoice: String = "auto",
) {
    companion object {
        fun from(j: JSONObject) = CodeTask(j.getString("id"), j.optString("project"), j.optString("repo"), j.optString("title"),
            j.optString("branch"), j.optString("base"), j.optString("state", "idle"), j.optString("pr_url").takeIf { it.isNotBlank() && it != "null" },
            j.optDouble("created", 0.0), CodeStats.from(j.optJSONObject("stats")), j.optString("model_choice", "auto"))
    }
}

/** A changed file; [patch] is present when the full diff was loaded. */
data class CodeFile(val path: String, val status: String, val additions: Int, val deletions: Int, val binary: Boolean,
                    val untracked: Boolean, val patch: String?, val truncated: Boolean) {
    companion object {
        fun from(j: JSONObject) = CodeFile(j.getString("path"), j.optString("status", "modified"), j.optInt("additions"), j.optInt("deletions"),
            j.optBoolean("binary"), j.optBoolean("untracked"), if (j.has("patch")) j.optString("patch") else null, j.optBoolean("truncated"))
    }
}

data class CodeChanges(val files: List<CodeFile>, val stats: CodeStats) {
    companion object {
        fun from(j: JSONObject) = CodeChanges(rows(j.optJSONArray("files")).map(CodeFile::from), CodeStats.from(j))
    }
}

data class CodePr(val url: String, val state: String, val draft: Boolean, val merged: Boolean, val review: String?,
                  val checks: Int, val failed: Int, val pending: Int) {
    val label: String get() = when {
        merged -> "Merged"
        state == "closed" -> "Closed"
        draft -> "Draft pull request"
        else -> "Pull request open"
    }
    companion object {
        fun from(j: JSONObject?): CodePr? {
            j ?: return null
            val checks = j.optJSONObject("checks")
            return CodePr(j.optString("url"), j.optString("state"), j.optBoolean("draft"), j.optBoolean("merged"),
                j.optString("review").takeIf { it.isNotBlank() && it != "null" }, checks?.optInt("total") ?: 0, checks?.optInt("failed") ?: 0, checks?.optInt("pending") ?: 0)
        }
    }
}

/** A message or step in the agent's transcript. */
data class CodeItem(val id: String, val role: String, val kind: String, val text: String, val tool: String, val status: String, val title: String, val output: String) {
    companion object {
        fun from(j: JSONObject) = CodeItem(j.optString("id"), j.optString("role"), j.optString("kind"), j.optString("text"), j.optString("tool"),
            j.optString("status"), j.optString("title"), j.optString("output"))
    }
}

data class CodeTaskView(
    val task: CodeTask, val busy: Boolean, val model: String, val items: List<CodeItem>,
    /** Pending approvals and questions, in the PC agent's JSON so the shared cards can show them. */
    val permissions: List<JSONObject>, val questions: List<JSONObject>,
    val changes: CodeChanges, val pr: CodePr?,
    val elapsedSeconds: Double? = null, val models: List<String> = emptyList(),
) {
    val waiting: Boolean get() = permissions.isNotEmpty() || questions.isNotEmpty()
    companion object {
        fun from(j: JSONObject): CodeTaskView {
            val session = j.optJSONObject("session")
            val timing = session?.optJSONObject("timing")
            val modelHistory = timing?.optJSONArray("models")
            val task = CodeTask.from(j).let { t -> session?.optString("state")?.takeIf { it.isNotBlank() }?.let { t.copy(state = it) } ?: t }
            return CodeTaskView(task, session?.optBoolean("busy") == true, session?.optString("model").orEmpty(),
                rows(session?.optJSONArray("items")).map(CodeItem::from), rows(session?.optJSONArray("permissions")), rows(session?.optJSONArray("questions")),
                j.optJSONObject("changes")?.let(CodeChanges::from) ?: CodeChanges(emptyList(), CodeStats()), CodePr.from(j.optJSONObject("pr")),
                timing?.optDouble("elapsed_seconds")?.takeIf { it.isFinite() },
                modelHistory?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())
        }
    }
}

internal fun rows(array: JSONArray?): List<JSONObject> = array?.let { a -> (0 until a.length()).mapNotNull(a::optJSONObject) }.orEmpty()

/** How a task's state reads in lists and headers. */
fun codeStateLabel(task: CodeTask): String = when {
    task.state == "waiting" -> "Needs you"
    task.state == "busy" -> "Working"
    task.prUrl != null -> "Pull request"
    task.state == "idle" -> if (task.stats.changed) "Ready to review" else "Finished"
    task.state == "error" -> "Couldn't finish"
    task.state == "cancelled" -> "Stopped"
    task.state == "interrupted" -> "Interrupted"
    task.state == "discarded" -> "Discarded"
    else -> task.state.replaceFirstChar(Char::uppercase)
}

/** One rendered line of a unified diff. */
data class DiffLine(val kind: Kind, val text: String, val old: Int?, val new: Int?) {
    enum class Kind { HUNK, ADDED, REMOVED, CONTEXT, NOTE }
}

object UnifiedDiff {
    private val hunk = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$""")

    /** The hunks of one file's patch, with line numbers; file headers are dropped. */
    fun parse(patch: String): List<DiffLine> {
        val out = mutableListOf<DiffLine>()
        var old = 0; var new = 0; var inHunk = false
        for (line in patch.lineSequence()) {
            val header = hunk.find(line)
            when {
                header != null -> {
                    old = header.groupValues[1].toInt(); new = header.groupValues[2].toInt(); inHunk = true
                    out += DiffLine(DiffLine.Kind.HUNK, header.groupValues[3].trim(), null, null)
                }
                !inHunk -> if (line.startsWith("Binary files")) out += DiffLine(DiffLine.Kind.NOTE, "Binary file changed", null, null)
                line.startsWith("+") -> out += DiffLine(DiffLine.Kind.ADDED, line.substring(1), null, new++)
                line.startsWith("-") -> out += DiffLine(DiffLine.Kind.REMOVED, line.substring(1), old++, null)
                line.startsWith("\\") -> out += DiffLine(DiffLine.Kind.NOTE, line.removePrefix("\\ ").trim(), null, null)
                line.startsWith(" ") -> out += DiffLine(DiffLine.Kind.CONTEXT, line.substring(1), old++, new++)
                line.startsWith("diff --git") -> inHunk = false
            }
        }
        return out
    }
}
