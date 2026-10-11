@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.localfirst.assistant.ui.code

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localfirst.assistant.ui.FridayCard
import com.localfirst.assistant.ui.FridayDialogWindow
import com.localfirst.assistant.ui.FridaySheet
import com.localfirst.assistant.ui.FridayVisibleEffect
import com.localfirst.assistant.ui.LineIcons
import com.localfirst.assistant.ui.MoreActions
import com.localfirst.assistant.ui.NativeResponseContent
import com.localfirst.assistant.ui.PcPermissionCard
import com.localfirst.assistant.ui.PcQuestionCard
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import kotlinx.coroutines.delay

/** The Code workspace, over the chat: projects and tasks, then one task's conversation and changes. */
@Composable
internal fun CodeScreen(code: CodeWorkspace) {
    val state by code.state.collectAsState()
    if (!state.open) return
    Dialog(onDismissRequest = { if (state.taskId != null) code.closeTask() else code.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FridayDialogWindow()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.taskId != null) CodeTaskScreen(state, code) else CodeHome(state, code)
        }
    }
}

@Composable
internal fun CodeHome(state: CodeState, code: CodeWorkspace) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var modelChoice by rememberSaveable { mutableStateOf("auto") }
    var picking by remember { mutableStateOf(false) }
    var branching by remember { mutableStateOf(false) }
    val active = state.tasks.any { it.state == "busy" || it.state == "waiting" }
    FridayVisibleEffect(active) { if (active) while (true) { delay(4000); code.refresh() } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.imePadding(), topBar = {
        TopAppBar(title = { Text("Code") },
            navigationIcon = { IconButton(onClick = code::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat") } },
            actions = { IconButton(onClick = code::refresh) { Icon(LineIcons.Refresh, "Refresh", Modifier.size(22.dp)) } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Notices(state, code) }
            item {
                val project = state.project
                if (project == null && state.loaded) ConnectRepository { picking = true }
                else TaskComposer(prompt, { prompt = it }, project, state.base ?: project?.defaultBranch, state.busy,
                    onRepo = { picking = true }, onBranch = { branching = true },
                    modelChoice = modelChoice, onModel = { modelChoice = it },
                    onSend = { code.startTask(prompt, modelChoice) { prompt = "" } })
            }
            if (state.tasks.isNotEmpty()) item { Label("Tasks") }
            else if (state.loaded && state.projects.isNotEmpty()) item {
                Text("Tasks you start appear here. Each one gets its own branch, so several can run at once.",
                    Modifier.padding(horizontal = 4.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.tasks, key = { it.id }) { task -> TaskRow(task) { code.openTask(task.id) } }
        }
    }
    if (picking) RepositorySheet(state, code) { picking = false }
    if (branching) state.project?.let { project ->
        FridaySheet(onDismiss = { branching = false }) { _, _, close ->
            Text("Base branch", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                val names = state.branches.ifEmpty { listOf(project.defaultBranch) }
                items(names, key = { it }) { name ->
                    val selected = name == (state.base ?: project.defaultBranch)
                    ListItem(headlineContent = { Text(name, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = if (name == project.defaultBranch) ({ Text("Default branch") }) else null,
                        leadingContent = { RadioButton(selected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { code.selectBase(name); close() })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Notices(state: CodeState, code: CodeWorkspace) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.working?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(4.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.error?.let { message ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    IconButton(onClick = code::dismissError) { Icon(LineIcons.Close, "Dismiss", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
        }
    }
}

@Composable
private fun ConnectRepository(onAdd: () -> Unit) {
    FridayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(LineIcons.Code, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Code with FRIDAY", style = MaterialTheme.typography.titleLarge)
            Text("Pick one of your GitHub repositories. FRIDAY clones it on your PC, works on each task in its own branch, " +
                "and shows you the diff. When you're happy, it opens the pull request.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAdd, shape = MaterialTheme.shapes.small) { Text("Add a repository") }
        }
    }
}

@Composable
private fun TaskComposer(prompt: String, onPrompt: (String) -> Unit, project: CodeProject?, branch: String?, busy: Boolean,
                         onRepo: () -> Unit, onBranch: () -> Unit, modelChoice: String, onModel: (String) -> Unit, onSend: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 10.dp)) {
            TextField(prompt, onPrompt, placeholder = { Text("Describe a coding task") }, minLines = 3, maxLines = 10,
                modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = modelChoice == "auto", onClick = { onModel("auto") }, enabled = !busy,
                    label = { Text("Automatic") })
                FilterChip(selected = modelChoice == "local", onClick = { onModel("local") }, enabled = !busy,
                    label = { Text("Local model") })
            }
            Text(if (modelChoice == "local") "Uses your PC's local model for this task and follow-ups."
                else "Uses ranked free cloud models, with local fallback.",
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 8.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ComposerChip(project?.name ?: "Repository", LineIcons.Code, onRepo, Modifier.weight(1f, fill = false))
                    if (project != null) ComposerChip(branch ?: project.defaultBranch, LineIcons.Branch, onBranch, Modifier.weight(1f, fill = false))
                }
                val enabled = prompt.isNotBlank() && project != null && !busy
                FilledIconButton(onClick = onSend, enabled = enabled, modifier = Modifier.size(40.dp).semantics { contentDescription = "Start task" }) {
                    Icon(LineIcons.Send, null, Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ComposerChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier.heightIn(min = 36.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TaskRow(task: CodeTask, onOpen: () -> Unit) {
    FridayCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.title.ifBlank { "Coding task" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StateDot(task)
                    Text("${codeStateLabel(task)} · ${task.repo.substringAfter('/')} · ${ago(task.created)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (task.stats.additions + task.stats.deletions > 0) LineCounts(task.stats.additions, task.stats.deletions)
        }
    }
}

@Composable
private fun StateDot(task: CodeTask) {
    val palette = LocalFridayPalette.current
    val color = when (task.state) {
        "busy" -> MaterialTheme.colorScheme.primary
        "waiting" -> palette.warning
        "error", "interrupted" -> MaterialTheme.colorScheme.error
        "idle" -> palette.success
        else -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
}

@Composable
internal fun LineCounts(additions: Int, deletions: Int) {
    val palette = LocalFridayPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (additions > 0 || deletions == 0) Text("+$additions", style = MaterialTheme.typography.labelLarge, color = palette.success, fontFamily = FontFamily.Monospace)
        if (deletions > 0) Text("−$deletions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace)
    }
}

/** Choose the project for new tasks, or add one from GitHub. */
@Composable
private fun RepositorySheet(state: CodeState, code: CodeWorkspace, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(state.projects.isEmpty()) }
    var query by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<CodeProject?>(null) }
    LaunchedEffect(adding) { if (adding && state.repos == null) code.loadRepos() }
    FridaySheet(onDismiss = onDismiss) { _, _, close ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (adding) "Add a repository" else "Repositories", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium)
            if (!adding) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(state.projects, key = { it.id }) { project ->
                        ListItem(headlineContent = { Text(project.repo, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(listOfNotNull(project.defaultBranch, "commands run freely".takeIf { project.trusted },
                                "${project.openTasks} open".takeIf { project.openTasks > 0 }).joinToString(" · ")) },
                            leadingContent = { RadioButton(project.id == state.project?.id, onClick = null) },
                            trailingContent = { MoreActions(listOf(
                                (if (project.trusted) "Ask before commands" else "Let commands run freely") to { code.setTrusted(project.id, !project.trusted) },
                                "Remove" to { removing = project })) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { code.selectProject(project.id); close() })
                    }
                }
                TextButton(onClick = { adding = true }) { Icon(LineIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add a repository") }
                Text("Edits in a task's branch run without asking. Commands follow your Computer access settings unless you let a project run them freely; pushing always waits for you.",
                    Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedTextField(query, { query = it.take(200) }, placeholder = { Text("Search, or owner/name") }, singleLine = true,
                    leadingIcon = { Icon(LineIcons.Search, null, Modifier.size(20.dp)) }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
                val typed = query.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')
                val manual = typed.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
                val repos = state.repos
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    val matches = repos.orEmpty().filter { query.isBlank() || it.repo.contains(typed, ignoreCase = true) || it.description.contains(query.trim(), ignoreCase = true) }
                    if (manual && matches.none { it.repo.equals(typed, ignoreCase = true) }) item {
                        ListItem(headlineContent = { Text("Add $typed") }, supportingContent = { Text("Any repository your GitHub sign-in on the PC can read") },
                            leadingContent = { Icon(LineIcons.Add, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !state.busy) { code.addRepo(typed); close() })
                    }
                    if (repos == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                    items(matches, key = { it.repo }) { repo ->
                        val added = state.projects.any { it.repo.equals(repo.repo, ignoreCase = true) }
                        ListItem(headlineContent = { Text(repo.repo, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = repo.description.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                            trailingContent = { Text(if (added) "Added" else if (repo.private) "Private" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !state.busy) {
                                val existing = state.projects.firstOrNull { it.repo.equals(repo.repo, ignoreCase = true) }
                                if (existing != null) code.selectProject(existing.id) else code.addRepo(repo.repo)
                                close()
                            })
                    }
                }
                if (state.projects.isNotEmpty()) TextButton(onClick = { adding = false }) { Text("Back to your repositories") }
            }
        }
    }
    removing?.let { project ->
        AlertDialog(onDismissRequest = { removing = null }, title = { FridayDialogWindow(); Text("Remove ${project.repo}?") },
            text = { Text("FRIDAY forgets this project. The clone stays on your PC, and nothing changes on GitHub.") },
            confirmButton = { TextButton(onClick = { code.removeProject(project.id); removing = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

@Composable
internal fun CodeTaskScreen(state: CodeState, code: CodeWorkspace) {
    val id = state.taskId ?: return
    BackHandler(onBack = code::closeTask)
    val view = state.view?.takeIf { it.task.id == id }
    val task = view?.task ?: state.tasks.firstOrNull { it.id == id }
    val active = view == null || view.busy || view.waiting
    FridayVisibleEffect(id, active) { while (true) { delay(if (active) 2000 else 20000); code.refreshTask() } }
    var tab by rememberSaveable(id) { mutableIntStateOf(0) }
    var proposing by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LaunchedEffect(tab, view?.changes?.stats) { if (tab == 1 && state.diff == null && view != null) code.loadDiff() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.imePadding(), topBar = {
        TopAppBar(title = {
            Column {
                Text(task?.title?.ifBlank { null } ?: "Coding task", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                task?.let { Text("${it.repo} · ${it.branch}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }, navigationIcon = { IconButton(onClick = code::closeTask) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to tasks") } },
            actions = {
                MoreActions(buildList<Pair<String, () -> Unit>> {
                    add("Refresh" to code::refreshTask)
                    view?.pr?.let { pr -> add("Open pull request" to { uri.openUri(pr.url) }) }
                    if (view != null && !view.busy) add("Push branch" to code::push)
                    add("Discard task" to { discarding = true })
                })
            })
    }, bottomBar = {
        if (tab == 0 && view != null) TaskBottomBar(view, state.busy, onFollowUp = code::followUp, onStop = code::stop,
            onPropose = { proposing = true }, onOpenPr = { view.pr?.let { uri.openUri(it.url) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (view == null || view.busy || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider(color = Color.Transparent, thickness = 4.dp)
            Box(Modifier.padding(horizontal = 16.dp)) { Notices(state, code) }
            val changes = view?.changes?.stats
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                val quiet = MaterialTheme.colorScheme.onSurfaceVariant
                Tab(tab == 0, onClick = { tab = 0 }, unselectedContentColor = quiet, text = { Text("Chat") })
                Tab(tab == 1, onClick = { tab = 1 }, unselectedContentColor = quiet, text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Changes")
                        if (changes != null && changes.additions + changes.deletions > 0) LineCounts(changes.additions, changes.deletions)
                    }
                })
            }
            when {
                view == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (state.error == null) CircularProgressIndicator() }
                tab == 0 -> TaskConversation(view, state.busy, code)
                else -> ChangesView(view, state.diff)
            }
        }
    }
    if (proposing && view != null) PullRequestDialog(view.task.title, onDismiss = { proposing = false }) { title, draft ->
        proposing = false; code.createPullRequest(title, draft)
    }
    if (discarding) AlertDialog(onDismissRequest = { discarding = false }, title = { FridayDialogWindow(); Text("Discard this task?") },
        text = { Text("FRIDAY stops, and the task's local branch and worktree are deleted. Anything already pushed, and any pull request, stays on GitHub.") },
        confirmButton = { TextButton(onClick = { discarding = false; code.discard() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { discarding = false }) { Text("Cancel") } })
}

/** The coding agent's conversation: the request, its replies, its steps and anything waiting on the user. */
@Composable
private fun TaskConversation(view: CodeTaskView, busy: Boolean, code: CodeWorkspace) {
    val list = rememberLazyListState()
    val blocks = remember(view.items) { conversationBlocks(view.items) }
    LaunchedEffect(blocks.size, view.waiting) { if (blocks.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "model-timing") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (view.task.modelChoice == "local") "Local model" else "Automatic model selection",
                    style = MaterialTheme.typography.labelLarge)
                view.elapsedSeconds?.let { seconds ->
                    val total = seconds.toLong().coerceAtLeast(0)
                    Text("Elapsed: ${total / 60}m ${total % 60}s · includes tools and waits",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (view.models.isNotEmpty()) Text(view.models.joinToString(" → "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        items(blocks, key = { it.key }) { block ->
            when (block) {
                is Block.User -> UserBubble(block.text)
                is Block.Reply -> SelectionContainer { NativeResponseContent(block.text) }
                is Block.Problem -> Text(block.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                is Block.Steps -> StepsCard(block.steps, running = view.busy && block == blocks.lastOrNull())
            }
        }
        items(view.permissions, key = { "permission-" + it.optString("id") }) { PcPermissionCard(it, !busy, code::replyPermission) }
        items(view.questions, key = { "question-" + it.optString("id") }) { PcQuestionCard(it, !busy, code::answerQuestion) }
        if (view.busy && !view.waiting) item(key = "working") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Working" + view.model.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!view.busy && view.task.state in setOf("error", "interrupted", "cancelled")) item(key = "ended") {
            Text(when (view.task.state) { "cancelled" -> "Stopped. Send a message to continue."; "interrupted" -> "Interrupted when the PC restarted. Send a message to continue."
                else -> "FRIDAY couldn't finish this turn. Send a message to try again." }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private sealed interface Block {
    val key: String
    data class User(override val key: String, val text: String) : Block
    data class Reply(override val key: String, val text: String) : Block
    data class Problem(override val key: String, val text: String) : Block
    data class Steps(override val key: String, val steps: List<CodeItem>) : Block
}

/** Consecutive tool steps read as one block, like a coding agent's "ran 6 commands". */
private fun conversationBlocks(items: List<CodeItem>): List<Block> {
    val out = mutableListOf<Block>()
    val steps = mutableListOf<CodeItem>()
    fun flush() { if (steps.isNotEmpty()) { out += Block.Steps("steps-" + steps.first().id, steps.toList()); steps.clear() } }
    for (item in items) when {
        item.kind == "tool" -> steps += item
        item.kind == "error" -> { flush(); out += Block.Problem(item.id, item.text) }
        item.role == "user" -> { flush(); out += Block.User(item.id, taskRequest(item.text)) }
        else -> { flush(); out += Block.Reply(item.id, item.text) }
    }
    flush()
    return out
}

/** The first message carries the repository and branch for the agent; the user only needs their request. */
internal fun taskRequest(text: String): String =
    if (text.startsWith("Repository: ") && "\n\nTask:\n" in text) text.substringAfter("\n\nTask:\n") else text

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.widthIn(max = 320.dp)) {
            SelectionContainer { Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun StepsCard(steps: List<CodeItem>, running: Boolean) {
    var expanded by rememberSaveable(steps.first().id) { mutableStateOf(false) }
    val shown = if (expanded || steps.size <= 3) steps else steps.takeLast(2)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            if (steps.size > 3) Text(if (expanded) "Hide steps" else "${steps.size} steps · show all",
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            shown.forEach { step -> key(step.id) { StepRow(step, running && step == steps.last()) } }
        }
    }
}

@Composable
private fun StepRow(step: CodeItem, running: Boolean) {
    var open by rememberSaveable(step.id) { mutableStateOf(false) }
    val failed = step.status == "error"
    Column(Modifier.fillMaxWidth().clickable(enabled = step.output.isNotBlank()) { open = !open }.padding(horizontal = 14.dp, vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running || step.status == "running" || step.status == "pending") CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            else Box(Modifier.size(6.dp).clip(CircleShape).background(if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline))
            Text(step.title.ifBlank { step.tool }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        if (open) SelectionContainer {
            Text(step.output, Modifier.padding(start = 16.dp, top = 6.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp), fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun TaskBottomBar(view: CodeTaskView, busy: Boolean, onFollowUp: (String) -> Unit, onStop: () -> Unit, onPropose: () -> Unit, onOpenPr: () -> Unit) {
    var message by rememberSaveable(view.task.id) { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val pr = view.pr
            if (pr != null) PullRequestStrip(pr, onOpenPr)
            else if (!view.busy && view.changes.stats.changed) Button(onClick = onPropose, enabled = !busy, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Text("Create pull request")
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
                    TextField(message, { message = it.take(20000) }, placeholder = { Text(if (view.busy) "FRIDAY is working…" else if (pr != null) "Ask for changes to the PR" else "Ask for changes") },
                        enabled = !view.busy && !view.waiting, maxLines = 5, modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent))
                    if (view.busy && !view.waiting) FilledTonalButton(onClick = onStop, enabled = !busy, shape = CircleShape) { Text("Stop") }
                    else FilledIconButton(onClick = { val text = message.trim(); if (text.isNotEmpty()) { onFollowUp(text); message = "" } },
                        enabled = !busy && !view.waiting && message.isNotBlank(), modifier = Modifier.size(40.dp).semantics { contentDescription = "Send" }) {
                        Icon(LineIcons.Send, null, Modifier.size(20.dp))
                    }
                }
            }
            if (pr != null && !view.busy) Text("Follow-ups land on the same branch. Push to update the pull request.", Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PullRequestStrip(pr: CodePr, onOpen: () -> Unit) {
    val palette = LocalFridayPalette.current
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(LineIcons.Branch, null, Modifier.size(20.dp), tint = if (pr.merged) MaterialTheme.colorScheme.primary else palette.success)
            Column(Modifier.weight(1f)) {
                Text(pr.label + " #" + pr.url.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                val checks = when {
                    pr.checks == 0 -> null
                    pr.failed > 0 -> "${pr.failed} of ${pr.checks} checks failed"
                    pr.pending > 0 -> "${pr.pending} of ${pr.checks} checks running"
                    else -> "All ${pr.checks} checks passed"
                }
                listOfNotNull(checks, pr.review?.replace('_', ' ')).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (pr.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PullRequestDialog(initial: String, onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial) }
    var draft by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { FridayDialogWindow(); Text("Create pull request") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it.take(200) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().clickable { draft = !draft }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(draft, onCheckedChange = { draft = it }); Text("Open as a draft")
            }
            Text("FRIDAY commits anything left over, pushes the branch and uses its summary as the description.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = { onCreate(title, draft) }, enabled = title.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Files changed against the base, each opening to its colored unified diff. */
@Composable
private fun ChangesView(view: CodeTaskView, diff: CodeChanges?) {
    val changes = diff ?: view.changes
    var open by rememberSaveable(view.task.id) { mutableStateOf(setOf<String>()) }
    val stats = changes.stats
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        item(key = "summary") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (changes.files.isEmpty()) Text(if (view.busy) "No changes yet. They show up here as FRIDAY edits files." else "No changes against ${view.task.base}.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(buildList {
                    add("${changes.files.size} ${if (changes.files.size == 1) "file" else "files"}")
                    if (stats.commits > 0) add("${stats.commits} ${if (stats.commits == 1) "commit" else "commits"}")
                    if (stats.uncommitted > 0) add("${stats.uncommitted} uncommitted")
                }.joinToString(" · ") + " against ${view.task.base}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        changes.files.forEach { file ->
            val expanded = file.path in open
            item(key = "file-" + file.path) {
                FileHeader(file, expanded) { open = if (expanded) open - file.path else open + file.path }
            }
            if (expanded) diffLines(file, diff == null)
        }
    }
}

@Composable
private fun FileHeader(file: CodeFile, expanded: Boolean, onToggle: () -> Unit) {
    val palette = LocalFridayPalette.current
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = .5.dp)
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val (letter, color) = when (file.status) { "added" -> "A" to palette.success; "deleted" -> "D" to MaterialTheme.colorScheme.error; else -> "M" to palette.warning }
            Text(letter, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = color)
            Column(Modifier.weight(1f)) {
                Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                file.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (file.binary) Text("binary", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LineCounts(file.additions, file.deletions)
            Text(if (expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val MAX_DIFF_LINES = 1500

private fun LazyListScope.diffLines(file: CodeFile, loading: Boolean) {
    val patch = file.patch
    when {
        loading || patch == null -> item(key = "loading-" + file.path) { Text("Loading diff…", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
        file.binary -> item(key = "binary-" + file.path) { Text("Binary file — open it on the PC or GitHub.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
        else -> {
            val lines = UnifiedDiff.parse(patch)
            items(lines.size.coerceAtMost(MAX_DIFF_LINES), key = { "line-${file.path}-$it" }) { DiffRow(lines[it]) }
            if (lines.size > MAX_DIFF_LINES || file.truncated) item(key = "more-" + file.path) {
                Text("This diff is long; the rest is on the PC and in the pull request.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine) {
    val palette = LocalFridayPalette.current
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp)
    val (background, marker) = when (line.kind) {
        DiffLine.Kind.ADDED -> palette.success.copy(alpha = .16f) to "+"
        DiffLine.Kind.REMOVED -> MaterialTheme.colorScheme.error.copy(alpha = .14f) to "−"
        DiffLine.Kind.HUNK -> MaterialTheme.colorScheme.surfaceContainerHigh to ""
        else -> Color.Transparent to " "
    }
    if (line.kind == DiffLine.Kind.HUNK || line.kind == DiffLine.Kind.NOTE) {
        Text(line.text.ifBlank { "⋯" }, Modifier.fillMaxWidth().background(background).padding(horizontal = 16.dp, vertical = 4.dp), style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    Row(Modifier.fillMaxWidth().background(background).padding(end = 12.dp)) {
        Text((line.new ?: line.old)?.toString().orEmpty(), Modifier.width(44.dp).padding(end = 6.dp), style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Text(marker, Modifier.width(12.dp), style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(line.text, Modifier.weight(1f), style = mono)
    }
}

internal fun ago(seconds: Double, now: Long = System.currentTimeMillis()): String {
    if (seconds <= 0) return ""
    val minutes = ((now / 1000.0 - seconds) / 60).toLong().coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
        else -> java.time.Instant.ofEpochSecond(seconds.toLong()).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
    }
}
