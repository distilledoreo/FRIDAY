package com.localfirst.assistant.ui.code

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.localfirst.assistant.ui.wire.JSONArray
import com.localfirst.assistant.ui.wire.JSONObject

data class CodeState(
    val open: Boolean = false,
    val projects: List<CodeProject> = emptyList(),
    val projectId: String? = null,
    /** Base branch for new tasks; null means the project's default branch. */
    val base: String? = null,
    val branches: List<String> = emptyList(),
    val tasks: List<CodeTask> = emptyList(),
    /** The user's GitHub repositories, once the add-repository sheet asked for them. */
    val repos: List<CodeRepo>? = null,
    val taskId: String? = null,
    val view: CodeTaskView? = null,
    /** The open task's full diff, loaded with the Changes tab. */
    val diff: CodeChanges? = null,
    val loaded: Boolean = false,
    /** A request that changes something is in flight; buttons wait for it. */
    val busy: Boolean = false,
    /** A clone or task start, which can take a while. */
    val working: String? = null,
    val error: String? = null,
) {
    val project: CodeProject? get() = projects.firstOrNull { it.id == projectId } ?: projects.firstOrNull()
}

/**
 * The Code workspace: GitHub repositories on the user's PC, each task on its own branch and worktree.
 * Kept apart from the chat view model so the main chat stays as it is; it shares only the PC connection.
 */
class CodeWorkspace(
    parentScope: CoroutineScope,
    private val request: suspend (path: String, method: String, body: JSONObject?) -> String,
    private val remembered: Remembered = Remembered.None,
) {
    /** Where the last chosen project is kept between launches. */
    interface Remembered {
        var project: String?
        object None : Remembered { override var project: String? = null }
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + kotlinx.coroutines.SupervisorJob(parentScope.coroutineContext[Job]))
    private val _state = MutableStateFlow(CodeState(projectId = remembered.project))
    val state: StateFlow<CodeState> = _state

    private var refreshJob: Job? = null
    private var taskJob: Job? = null

    fun open(taskId: String? = null) {
        _state.update { it.copy(open = true, taskId = taskId ?: it.taskId, error = null) }
        refresh()
        taskId?.let { openTask(it) }
    }

    fun reset() { scope.coroutineContext[Job]?.children?.forEach { it.cancel() }; refreshJob?.cancel(); taskJob?.cancel(); _state.value = CodeState() }

    fun close() { _state.update { it.copy(open = false, taskId = null, view = null, diff = null, repos = null) } }

    fun dismissError() { _state.update { it.copy(error = null) } }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            try {
                val projects = rows(JSONArray(request("/workspace/code/projects", "GET", null))).map(CodeProject::from)
                val tasks = rows(JSONArray(request("/workspace/code/tasks", "GET", null))).map(CodeTask::from)
                _state.update { s ->
                    val selected = s.projectId?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id
                    s.copy(projects = projects, tasks = tasks, projectId = selected, loaded = true, error = null)
                }
                if (_state.value.branches.isEmpty()) _state.value.project?.let { loadBranches(it.id) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loaded = true, error = friendly(e, "Code projects couldn't be loaded. Check the computer connection.")) } }
        }
    }

    fun selectProject(id: String) {
        remembered.project = id
        _state.update { it.copy(projectId = id, base = null, branches = emptyList()) }
        loadBranches(id)
    }

    fun selectBase(branch: String?) { _state.update { it.copy(base = branch?.takeIf { b -> b != it.project?.defaultBranch }) } }

    private fun loadBranches(projectId: String) = scope.launch {
        runCatching { JSONObject(request("/workspace/code/projects/$projectId/branches", "GET", null)) }.getOrNull()?.let { result ->
            val names = result.optJSONArray("branches")?.let { a -> (0 until a.length()).map(a::optString) }.orEmpty()
            _state.update { if (it.projectId == projectId) it.copy(branches = names) else it }
        }
    }

    fun loadRepos() = scope.launch {
        try {
            val repos = rows(JSONArray(request("/workspace/code/repos", "GET", null))).map(CodeRepo::from)
            _state.update { it.copy(repos = repos) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(repos = emptyList(), error = friendly(e, "Your GitHub repositories couldn't be listed. Is gh signed in on the PC?")) } }
    }

    fun addRepo(repo: String) = act("Cloning ${repo.trim()}…") {
        val project = CodeProject.from(JSONObject(request("/workspace/code/projects", "POST", JSONObject().put("repo", repo.trim()))))
        remembered.project = project.id
        _state.update { s -> s.copy(projects = listOf(project) + s.projects.filter { it.id != project.id }, projectId = project.id, base = null, branches = emptyList(), repos = null) }
        loadBranches(project.id)
    }

    fun setTrusted(id: String, trusted: Boolean) = act {
        val project = CodeProject.from(JSONObject(request("/workspace/code/projects/$id/trust", "PUT", JSONObject().put("trusted", trusted))))
        _state.update { s -> s.copy(projects = s.projects.map { if (it.id == id) project else it }) }
    }

    fun removeProject(id: String) = act {
        request("/workspace/code/projects/$id", "DELETE", null)
        _state.update { s -> s.copy(projects = s.projects.filter { it.id != id }, projectId = s.projectId.takeIf { it != id }, branches = emptyList()) }
        refresh()
    }

    /** Starts a task in the selected project and opens it. */
    fun startTask(prompt: String, modelChoice: String = "auto", onStarted: () -> Unit = {}) {
        val project = _state.value.project ?: return
        val text = prompt.trim().take(20000).ifEmpty { return }
        act("Creating a branch and starting FRIDAY…") {
            val body = JSONObject().put("project", project.id).put("prompt", text).put("model_choice", modelChoice)
            _state.value.base?.let { body.put("base", it) }
            val task = CodeTask.from(JSONObject(request("/workspace/code/tasks", "POST", body)))
            _state.update { s -> s.copy(tasks = listOf(task) + s.tasks.filter { it.id != task.id }) }
            onStarted()
            openTask(task.id)
        }
    }

    fun openTask(id: String) {
        _state.update { it.copy(open = true, taskId = id, view = it.view?.takeIf { v -> v.task.id == id }, diff = null) }
        refreshTask()
    }

    fun closeTask() {
        _state.update { it.copy(taskId = null, view = null, diff = null) }
        refresh()
    }

    fun refreshTask() {
        val id = _state.value.taskId ?: return
        if (taskJob?.isActive == true) return
        taskJob = scope.launch {
            try {
                val view = CodeTaskView.from(JSONObject(request("/workspace/code/tasks/$id", "GET", null)))
                _state.update { s ->
                    if (s.taskId != id) s
                    else s.copy(view = view, tasks = s.tasks.map { if (it.id == id) view.task else it }, error = null,
                        // A diff loaded earlier is stale once the stats move.
                        diff = s.diff?.takeIf { it.stats.additions == view.changes.stats.additions && it.stats.deletions == view.changes.stats.deletions && it.files.size == view.changes.files.size })
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = friendly(e, "This task couldn't be loaded.")) } }
        }
    }

    fun loadDiff() {
        val id = _state.value.taskId ?: return
        scope.launch {
            try {
                val diff = CodeChanges.from(JSONObject(request("/workspace/code/tasks/$id/diff", "GET", null)))
                _state.update { if (it.taskId == id) it.copy(diff = diff) else it }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = friendly(e, "The changes couldn't be loaded.")) } }
        }
    }

    fun followUp(text: String) = taskAction("messages", JSONObject().put("prompt", text.trim().take(20000)))
    fun stop() = taskAction("abort")
    fun push() = taskAction("push", working = "Pushing the branch…")
    fun createPullRequest(title: String, draft: Boolean) =
        taskAction("pr", JSONObject().put("title", title.trim().take(200)).put("draft", draft), working = "Opening the pull request…")

    fun discard() {
        val id = _state.value.taskId ?: return
        act {
            request("/workspace/code/tasks/$id", "DELETE", null)
            _state.update { s -> s.copy(tasks = s.tasks.filter { it.id != id }) }
            closeTask()
        }
    }

    /** Approvals go through the PC agent's own routes, like any PC task. */
    fun replyPermission(id: String, reply: String, remember: Boolean) = act(after = ::refreshTask) {
        request("/workspace/pc/permissions/$id", "POST", JSONObject().put("reply", reply).put("remember", remember))
    }

    fun answerQuestion(id: String, answers: JSONArray?) = act(after = ::refreshTask) {
        if (answers == null) request("/workspace/pc/questions/$id/reject", "POST", null)
        else request("/workspace/pc/questions/$id", "POST", JSONObject().put("answers", answers))
    }

    private fun taskAction(path: String, body: JSONObject? = null, working: String? = null) {
        val id = _state.value.taskId ?: return
        act(working, after = ::refreshTask) { request("/workspace/code/tasks/$id/$path", "POST", body) }
    }

    private fun act(working: String? = null, after: () -> Unit = {}, block: suspend () -> Unit) {
        if (_state.value.busy) return
        scope.launch {
            _state.update { it.copy(busy = true, working = working, error = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = friendly(e, "That didn't work. Check the task's current state and try again.")) } }
            finally { _state.update { it.copy(busy = false, working = null) }; after() }
        }
    }

    companion object {
        /** The server's own explanation when it gave one ("FRIDAY is still working on this task"). */
        fun friendly(e: Exception, fallback: String): String {
            val message = e.message.orEmpty()
            val json = message.substringAfter(": ", "").takeIf { it.startsWith("{") }
            val detail = json?.let { runCatching { JSONObject(it).opt("detail") }.getOrNull() }
            return when (detail) {
                is String -> detail
                is JSONArray -> fallback
                else -> fallback
            }
        }
    }
}
