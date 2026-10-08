package com.localfirst.assistant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.automirrored.filled.List
import com.localfirst.assistant.conversation.AssistantProject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.conversation.ConversationSummary
import com.localfirst.assistant.presentation.ConversationGroups
import java.util.Calendar

@Composable
internal fun ChatDrawer(
    width: androidx.compose.ui.unit.Dp = 320.dp,
    conversations: List<ConversationSummary>,
    currentId: String?,
    onNewChat: () -> Unit,
    onIncognito: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit,
    query: String = "",
    onQuery: (String) -> Unit = {},
    projects: List<AssistantProject> = emptyList(),
    activeProjectId: String? = null,
    onProject: (String) -> Unit,
    onProjects: () -> Unit,
    onTasks: () -> Unit,
    onActivity: () -> Unit,
    onFiles: () -> Unit,
    onImages: () -> Unit,
) {
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }
    val startOfToday = remember(conversations) { startOfToday() }
    val visibleChats = if (query.isNotBlank()) conversations else conversations.filter { chat ->
        chat.projectId == null || projects.none { it.id == chat.projectId }
    }
    val grouped = remember(visibleChats, startOfToday) {
        visibleChats.groupBy { ConversationGroups.label(it.updatedAt, startOfToday) }
    }

    ModalDrawerSheet(modifier = Modifier.fillMaxHeight().width(width)) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Spacer(Modifier.height(64.dp))
            OutlinedTextField(value = query, onValueChange = onQuery, placeholder = { Text("Search chats") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                if (query.isBlank()) {
                    item { NavigationDrawerItem(shape = MaterialTheme.shapes.small, label = { Text("New chat") }, icon = { Icon(Icons.Filled.Create, null) }, selected = false, onClick = onNewChat) }
                    item { NavigationDrawerItem(shape = MaterialTheme.shapes.small, label = { Text("Incognito · won’t be saved") }, icon = { Icon(Icons.Filled.Create, null) }, selected = false, onClick = onIncognito) }
                    item { NavigationDrawerItem(shape = MaterialTheme.shapes.small, label = { Text("FRIDAY") }, icon = { Icon(AppIcons.Computer, null) }, selected = false, onClick = onActivity) }
                    item { NavigationDrawerItem(shape = MaterialTheme.shapes.small, label = { Text("Library") }, icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, selected = false, onClick = onFiles) }
                    item { NavigationDrawerItem(shape = MaterialTheme.shapes.small, label = { Text("Images") }, icon = { Icon(Icons.Filled.Create, null) }, selected = false, onClick = onImages) }


                    item { TextButton(shape = MaterialTheme.shapes.small, onClick = onProjects, modifier = Modifier.padding(top = 12.dp)) { Text("Projects") } }
                    items(projects, key = { "project:${it.id}" }) { project ->
                        NavigationDrawerItem(shape = MaterialTheme.shapes.small, label = { Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            icon = { Icon(AppIcons.Folder, null) }, selected = activeProjectId == project.id,
                            onClick = { onProject(project.id) })
                    }
                    if (projects.isEmpty()) item { TextButton(shape = MaterialTheme.shapes.small, onClick = onProjects) { Text("Create a project") } }
                }
                if (visibleChats.isEmpty()) {
                    item {
                        Text(
                            text = if (query.isBlank()) "Your chats will appear here." else "No matching chats.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                grouped.forEach { (label, items) ->
                    item(key = "h$label") {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
                        )
                    }
                    items(items, key = { it.id }) { summary ->
                        ConversationRow(
                            summary = summary,
                            selected = summary.id == currentId,
                            onOpen = { onOpen(summary.id) },
                            onRename = { renaming = summary },
                            onDelete = { deleting = summary },
                        )
                    }
                }
            }
            HorizontalDivider()
            NavigationDrawerItem(shape = MaterialTheme.shapes.small,
                label = { Text("Settings") },
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                selected = false,
                onClick = onOpenSettings,
                modifier = Modifier.padding(12.dp),
            )
        }
    }

    renaming?.let { summary ->
        var title by remember(summary.id) { mutableStateOf(summary.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { FridayDialogWindow(); Text("Rename chat") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small,
                    enabled = title.isNotBlank(),
                    onClick = {
                        onRename(summary.id, title)
                        renaming = null
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { summary ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { FridayDialogWindow(); Text("Delete chat?") },
            text = { Text("“${summary.title}” will be deleted from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small,
                    onClick = {
                        onDelete(summary.id)
                        deleting = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(
    summary: ConversationSummary,
    selected: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = summary.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Chat options")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
