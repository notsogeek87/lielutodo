package com.lielu.todo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lielu.todo.data.model.Project
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.data.todo.TodoState
import com.lielu.todo.ui.theme.parseHexColor
import kotlinx.coroutines.launch
import java.io.IOException

private enum class Creating { PROJECT, LABEL }

@Composable
fun BrowseTab(
    repository: TodoRepository,
    state: TodoState,
    userName: String,
    serverLabel: String,
    snackbar: SnackbarHostState,
    onOpenProject: (Long) -> Unit,
    onOpenLabel: (Long) -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf<Creating?>(null) }
    var projectsOpen by rememberSaveable { mutableStateOf(true) }
    var labelsOpen by rememberSaveable { mutableStateOf(true) }
    val openCount =
        state.tasks
            .filter { !it.done }
            .groupingBy { it.projectId }
            .eachCount()
    val others = orderedProjects(state.projects.filter { it.id != state.inboxId })
    val muted = MaterialTheme.colorScheme.outline

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item { BrowseTopBar(userName.ifBlank { "Mon compte" }, onSettings) }
        item {
            BrowseCard {
                BrowseRow(Icons.Outlined.Settings, "Paramètres et sauvegarde", onClick = onSettings)
                BrowseRow(Icons.AutoMirrored.Outlined.Logout, "Se déconnecter", onClick = onLogout)
            }
        }
        item {
            BrowseHeader(
                "Mes projets",
                expanded = projectsOpen,
                onToggle = { projectsOpen = !projectsOpen },
                onAdd = { creating = Creating.PROJECT },
            )
        }
        if (projectsOpen) {
            item {
                BrowseCard {
                    if (others.isEmpty()) {
                        Text(
                            "Aucun projet pour l'instant.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    others.forEach { (project, depth) ->
                        BrowseRow(
                            icon = null,
                            title = project.title,
                            count = openCount[project.id],
                            indent = depth,
                            iconColor = parseHexColor(project.hexColor, muted),
                            onClick = { onOpenProject(project.id) },
                        )
                    }
                }
            }
        }
        item {
            BrowseHeader(
                "Étiquettes",
                expanded = labelsOpen,
                onToggle = { labelsOpen = !labelsOpen },
                onAdd = { creating = Creating.LABEL },
            )
        }
        if (labelsOpen) {
            item {
                BrowseCard {
                    if (state.labels.isEmpty()) {
                        Text(
                            "Aucune étiquette pour l'instant.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    state.labels.forEach { label ->
                        val count = state.tasks.count { t -> !t.done && t.labels.any { it.id == label.id } }
                        BrowseRow(
                            Icons.AutoMirrored.Outlined.Label,
                            label.title,
                            count = count.takeIf { it > 0 },
                            iconColor = parseHexColor(label.hexColor, muted),
                            onClick = { onOpenLabel(label.id) },
                        )
                    }
                }
            }
        }
        item {
            Text(
                serverLabel,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }

    creating?.let { kind ->
        NameDialog(
            title = if (kind == Creating.PROJECT) "Nouveau projet" else "Nouvelle étiquette",
            onConfirm = { name ->
                creating = null
                scope.launch {
                    try {
                        if (kind == Creating.PROJECT) repository.createProject(name) else repository.createLabel(name)
                    } catch (e: IOException) {
                        snackbar.showSnackbar("Création impossible : ${e.message}")
                    }
                }
            },
            onDismiss = { creating = null },
        )
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

/** En-tête de l'onglet Parcourir : pastille d'initiale, nom du compte, réglages. */
@Composable
private fun BrowseTopBar(
    userName: String,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                userName.first().uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(
            userName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "Paramètres", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Groupe de lignes sur une carte arrondie, un ton au-dessus du fond. */
@Composable
private fun BrowseCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(content = content)
    }
}

@Composable
private fun BrowseHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Ajouter : $title") }
        IconButton(onClick = onToggle) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Replier : $title" else "Déplier : $title",
            )
        }
    }
}

/** Ligne de carte. Sans [icon], le repère « # » des projets Todoist est dessiné dans [iconColor]. */
@Composable
private fun BrowseRow(
    icon: ImageVector?,
    title: String,
    count: Int? = null,
    indent: Int = 0,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp + 20.dp * indent, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
        } else {
            Text(
                "#",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = iconColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(24.dp),
            )
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(start = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
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
