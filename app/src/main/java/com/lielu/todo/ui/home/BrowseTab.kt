package com.lielu.todo.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lielu.todo.data.model.Project
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.data.todo.TodoState
import com.lielu.todo.reminder.ReminderScheduler
import kotlinx.coroutines.launch
import java.io.IOException

private enum class Creating { PROJECT, LABEL, FILTER }

@Composable
fun BrowseTab(
    repository: TodoRepository,
    state: TodoState,
    serverLabel: String,
    snackbar: SnackbarHostState,
    onOpenProject: (Long) -> Unit,
    onOpenLabel: (Long) -> Unit,
    onOpenFilter: (Long) -> Unit,
    onOpenFavorites: () -> Unit,
    reminderScheduler: ReminderScheduler,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf<Creating?>(null) }
    val openCount = state.tasks.groupingBy { it.projectId }.eachCount()
    val inbox = state.projects.firstOrNull { it.id == state.inboxId }
    val others = orderedProjects(state.projects.filter { it.id != state.inboxId })

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (inbox != null) {
            item {
                BrowseRow(
                    Icons.Filled.Inbox,
                    "Boîte de réception",
                    openCount[inbox.id],
                    onClick = { onOpenProject(inbox.id) },
                )
            }
        }
        item {
            val favorites = state.tasks.count { it.isFavorite && !it.done }
            BrowseRow(Icons.Filled.Star, "Favoris", favorites.takeIf { it > 0 }, onClick = onOpenFavorites)
        }
        item { BrowseHeader("Projets", onAdd = { creating = Creating.PROJECT }) }
        items(others, key = { "p-${it.first.id}" }) { (project, depth) ->
            BrowseRow(
                Icons.Filled.Folder,
                project.title,
                openCount[project.id],
                indent = depth,
                tint = hexColor(project.hexColor),
                onClick = { onOpenProject(project.id) },
            )
        }
        item { BrowseHeader("Étiquettes", onAdd = { creating = Creating.LABEL }) }
        items(state.labels, key = { "l-${it.id}" }) { label ->
            val count = state.tasks.count { t -> !t.done && t.labels.any { it.id == label.id } }
            BrowseRow(
                Icons.AutoMirrored.Filled.Label,
                label.title,
                count.takeIf { it > 0 },
                onClick = { onOpenLabel(label.id) },
            )
        }
        item { BrowseHeader("Filtres", onAdd = { creating = Creating.FILTER }) }
        items(state.filters, key = { "f-${it.id}" }) { filter ->
            val count = state.filterResults[filter.id].orEmpty().count { !it.done }
            BrowseRow(
                Icons.Filled.FilterList,
                filter.title,
                count.takeIf { it > 0 },
                onClick = { onOpenFilter(filter.id) },
            )
        }
        item { BrowseHeader("Compte", onAdd = null) }
        item {
            Text(
                serverLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            var daily by remember { mutableStateOf(reminderScheduler.dailyEnabled) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Résumé quotidien à 8 h",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                androidx.compose.material3.Switch(
                    checked = daily,
                    onCheckedChange = {
                        daily = it
                        reminderScheduler.dailyEnabled = it
                    },
                )
            }
        }
        item { BrowseRow(Icons.Filled.Settings, "Paramètres et sauvegarde", null, onClick = onSettings) }
        item { BrowseRow(Icons.AutoMirrored.Filled.Logout, "Se déconnecter", null, onClick = onLogout) }
    }

    creating?.let { kind ->
        if (kind == Creating.FILTER) {
            FilterDialog(
                onConfirm = { name, query ->
                    creating = null
                    scope.launch {
                        try {
                            repository.createFilter(name, query)
                        } catch (e: IOException) {
                            snackbar.showSnackbar(
                                "Création impossible (serveur injoignable ou requête refusée) : ${e.message}",
                            )
                        }
                    }
                },
                onDismiss = { creating = null },
            )
        } else {
            NameDialog(
                title = if (kind == Creating.PROJECT) "Nouveau projet" else "Nouvelle étiquette",
                onConfirm = { name ->
                    creating = null
                    // Hors ligne aussi : le projet ou l'étiquette existe tout de suite et part au prochain envoi.
                    if (kind == Creating.PROJECT) repository.createProject(name) else repository.createLabel(name)
                },
                onDismiss = { creating = null },
            )
        }
    }
}

/** Projets dans l'ordre de l'arborescence, avec leur profondeur (sous-projets sous leur parent). */
internal fun orderedProjects(projects: List<Project>): List<Pair<Project, Int>> {
    val byParent = projects.groupBy { it.parentId }
    val ids = projects.map { it.id }.toSet()
    val result = mutableListOf<Pair<Project, Int>>()

    fun add(
        project: Project,
        depth: Int,
    ) {
        result += project to depth
        byParent[project.id].orEmpty().sortedBy { it.position }.forEach { add(it, depth + 1) }
    }
    // Un projet dont le parent n'est pas dans la liste (archivé, inaccessible) est traité comme une racine.
    projects.filter { it.parentId == 0L || it.parentId !in ids }.sortedBy { it.position }.forEach { add(it, 0) }
    return result
}

@Composable
private fun BrowseHeader(
    title: String,
    onAdd: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (onAdd != null) {
            IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Ajouter : $title") }
        }
    }
}

@Composable
private fun BrowseRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    count: Int?,
    indent: Int = 0,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = onClick,
                ).padding(start = 16.dp + 20.dp * indent, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, modifier = Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
        if (count !=
            null
        ) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun FilterDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("done = false && priority >= 3") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau filtre") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nom") },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Requête Vikunja") },
                    supportingText = {
                        Text("Ex. : due_date < now && done = false  ·  labels in urgent  ·  priority >= 3")
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), query.trim()) },
                enabled =
                    name.isNotBlank() && query.isNotBlank(),
            ) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun NameDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Nom") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Couleur d'un projet : hex sans « # » tel que Vikunja le stocke ; `null` si vide ou invalide (couleur du thème). */
internal fun hexColor(hex: String): androidx.compose.ui.graphics.Color? =
    hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let {
        androidx.compose.ui.graphics.Color(
            0xFF000000 or it,
        )
    }
