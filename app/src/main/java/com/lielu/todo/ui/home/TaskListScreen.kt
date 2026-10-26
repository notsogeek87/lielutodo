package com.lielu.todo.ui.home

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.ui.common.AddFab
import com.lielu.todo.ui.common.EmptyState
import com.lielu.todo.ui.common.TaskRow
import com.lielu.todo.ui.common.toggleDone
import com.lielu.todo.ui.task.QuickAddSheet
import com.lielu.todo.ui.task.add
import com.lielu.todo.ui.theme.ProjectPalette
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate

/** Ce que l'écran liste : un projet, une étiquette ou un filtre enregistré (un seul des trois). */
data class ListTarget(
    val projectId: Long? = null,
    val labelId: Long? = null,
    val filterId: Long? = null,
    val favorites: Boolean = false,
)

private const val POSITION_STEP = 65_536.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    repository: TodoRepository,
    target: ListTarget,
    onOpenTask: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenKanban: (Long) -> Unit = {},
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var projectDialog by remember { mutableStateOf<ProjectDialog?>(null) }
    val today = LocalDate.now()
    val projectId = target.projectId
    val live = state.tasks.associateBy { it.id }

    val title =
        when {
            target.favorites -> "Favoris"
            projectId != null -> state.projects.firstOrNull { it.id == projectId }?.title ?: "Projet"
            target.filterId != null -> state.filters.firstOrNull { it.id == target.filterId }?.title ?: "Filtre"
            else ->
                state.labels
                    .firstOrNull { it.id == target.labelId }
                    ?.title
                    ?.let { "@$it" } ?: "Étiquette"
        }
    val all: List<Task> =
        when {
            target.favorites -> state.tasks.filter { it.isFavorite }
            projectId != null -> state.tasks.filter { it.projectId == projectId }
            target.filterId != null -> state.filterResults[target.filterId].orEmpty().map { live[it.id] ?: it }
            else -> state.tasks.filter { t -> t.labels.any { it.id == target.labelId } }
        }
    val open = all.filter { !it.done }
    val done = all.filter { it.done }.sortedByDescending { it.id }
    val projects = state.projects.associate { it.id to it.title }
    val manualOrder = projectId != null

    // Ordre local pendant un glisser-déposer ; remis à zéro dès que la liste change.
    val roots =
        run {
            val ids = open.map { it.id }.toSet()
            val sorted =
                if (manualOrder) {
                    open.sortedWith(compareBy<Task> { it.position }.thenBy { it.id })
                } else {
                    open.sortedWith(
                        compareBy<Task> { it.dueDate == null }.thenBy { it.dueDate }.thenByDescending { it.priority },
                    )
                }
            // Une sous-tâche dont la parente est dans la liste s'affiche sous elle ; sinon elle est à la racine.
            sorted.filter { it.parentId == 0L || it.parentId !in ids }
        }
    val childrenOf = open.filter { it.parentId != 0L }.groupBy { it.parentId }
    val doneChildren = all.filter { it.done && it.parentId != 0L }.groupBy { it.parentId }
    var localOrder by remember(roots.map { it.id }) { mutableStateOf(roots.map { it.id }) }
    val rootById = roots.associateBy { it.id }
    val ordered = localOrder.mapNotNull { rootById[it] }

    val listState = rememberLazyListState()
    val drag = remember { DragState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    if (projectId != null && projectId > 0) {
                        IconButton(
                            onClick = { showShare = true },
                        ) { Icon(Icons.Filled.Share, contentDescription = "Partager le projet") }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Options du projet")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                listOf(
                                    "Renommer" to ProjectDialog.RENAME,
                                    "Couleur" to ProjectDialog.COLOR,
                                    "Déplacer dans…" to ProjectDialog.MOVE,
                                    "Monter" to ProjectDialog.UP,
                                    "Descendre" to ProjectDialog.DOWN,
                                    "Vue Kanban" to ProjectDialog.KANBAN,
                                    "Archiver" to ProjectDialog.ARCHIVE,
                                    "Supprimer" to ProjectDialog.DELETE,
                                ).forEach { (label, kind) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            showMenu = false
                                            when (kind) {
                                                ProjectDialog.KANBAN -> onOpenKanban(projectId)
                                                ProjectDialog.UP, ProjectDialog.DOWN ->
                                                    scope.launch {
                                                        try {
                                                            repository.moveProjectOrder(
                                                                projectId,
                                                                up =
                                                                    kind == ProjectDialog.UP,
                                                            )
                                                        } catch (e: IOException) {
                                                            snackbar.showSnackbar(
                                                                "Action impossible (serveur injoignable) : ${e.message}",
                                                            )
                                                        }
                                                    }
                                                else -> projectDialog = kind
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = { AddFab(onClick = { showAdd = true }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = { scope.launch { repository.refresh() } },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(ordered, key = { "task-${it.id}" }) { task ->
                    val dragging = drag.id == task.id
                    Column(
                        modifier =
                            Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { translationY = if (dragging) drag.offset else 0f }
                                .alpha(if (dragging) 0.85f else 1f)
                                .then(
                                    if (manualOrder) {
                                        Modifier.pointerInput(task.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    drag.id = task.id
                                                    drag.offset = 0f
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    drag.offset += amount.y
                                                    localOrder = reorderDuringDrag(listState, drag, localOrder)
                                                },
                                                onDragEnd = {
                                                    commitReorder(repository, drag.id, localOrder, rootById)
                                                    drag.id = null
                                                    drag.offset = 0f
                                                },
                                                onDragCancel = {
                                                    localOrder = roots.map { it.id }
                                                    drag.id = null
                                                    drag.offset = 0f
                                                },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                    ) {
                        val kids =
                            childrenOf[task.id].orEmpty().sortedWith(
                                compareBy<Task> { it.position }.thenBy { it.id },
                            )
                        val total = kids.size + doneChildren[task.id].orEmpty().size
                        TaskRow(
                            task = task,
                            today = today,
                            projectName = if (projectId == null) projects[task.projectId] else null,
                            subtasks = if (total > 0) "${total - kids.size}/$total" else null,
                            onToggle = { d -> scope.toggleDone(repository, snackbar, task, d) },
                            onClick = { onOpenTask(task.id) },
                        )
                        kids.forEach { kid ->
                            TaskRow(
                                task = kid,
                                today = today,
                                projectName = null,
                                indent = 1,
                                onToggle = { d -> scope.toggleDone(repository, snackbar, kid, d) },
                                onClick = { onOpenTask(kid.id) },
                            )
                        }
                    }
                }
                if (ordered.isEmpty()) item { EmptyState("Aucune tâche.") }
                if (done.isNotEmpty()) {
                    item(key = "done-header") {
                        TextButton(onClick = { showDone = !showDone }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(if (showDone) "Masquer les terminées" else "Terminées (${done.size})")
                        }
                    }
                    if (showDone) {
                        items(done, key = { "done-${it.id}" }) { task ->
                            TaskRow(
                                task = task,
                                today = today,
                                projectName = null,
                                onToggle = { d -> scope.toggleDone(repository, snackbar, task, d) },
                                onClick = { onOpenTask(task.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        QuickAddSheet(
            state = state,
            defaultProjectId = projectId ?: state.inboxId,
            defaultDate = null,
            onAdd = { new ->
                // Dans la vue d'une étiquette, la nouvelle tâche la porte d'office.
                val extra = state.labels.filter { it.id == target.labelId && new.labels.none { l -> l.id == it.id } }
                repository.add(new.copy(labels = new.labels + extra))
            },
            onDismiss = { showAdd = false },
        )
    }
    val currentProject = state.projects.firstOrNull { it.id == projectId }
    if (projectDialog != null && projectId != null && currentProject != null) {
        ProjectActionDialog(
            kind = projectDialog!!,
            projectTitle = currentProject.title,
            projectId = projectId,
            projects = state.projects,
            onDismiss = { projectDialog = null },
            onConfirm = { kind, value ->
                projectDialog = null
                scope.launch {
                    try {
                        when (kind) {
                            ProjectDialog.RENAME -> repository.updateProject(projectId) { it.put("title", value) }
                            ProjectDialog.COLOR -> repository.updateProject(projectId) { it.put("hex_color", value) }
                            ProjectDialog.ARCHIVE -> {
                                repository.updateProject(projectId) { it.put("is_archived", true) }
                                onBack()
                            }
                            ProjectDialog.DELETE -> {
                                repository.deleteProject(projectId)
                                onBack()
                            }
                            ProjectDialog.MOVE -> repository.moveProject(projectId, value.toLongOrNull() ?: 0L)
                            // Actions directes du menu : elles n'ouvrent jamais de boîte de dialogue.
                            ProjectDialog.UP, ProjectDialog.DOWN, ProjectDialog.KANBAN -> Unit
                        }
                    } catch (e: IOException) {
                        snackbar.showSnackbar("Action impossible (serveur injoignable ou refus) : ${e.message}")
                    }
                }
            },
        )
    }
    if (showShare && projectId != null) {
        ShareDialog(
            onShare = { username, permission ->
                showShare = false
                scope.launch {
                    try {
                        repository.shareProject(projectId, username, permission)
                        snackbar.showSnackbar("Projet partagé avec $username")
                    } catch (e: IOException) {
                        snackbar.showSnackbar("Partage impossible : ${e.message}")
                    }
                }
            },
            onDismiss = { showShare = false },
        )
    }
}

/** Glisser-déposer en cours : tâche saisie et décalage vertical accumulé. */
private class DragState {
    var id by mutableStateOf<Long?>(null)
    var offsetState by mutableFloatStateOf(0f)
    var offset: Float
        get() = offsetState
        set(value) {
            offsetState = value
        }
}

/**
 * Échange la tâche glissée avec celle qu'elle recouvre. L'écart de position entre les deux lignes est retiré du
 * décalage pour que la ligne reste sous le doigt au lieu de sauter.
 */
private fun reorderDuringDrag(
    listState: LazyListState,
    drag: DragState,
    order: List<Long>,
): List<Long> {
    val draggedKey = "task-${drag.id}"
    val items = listState.layoutInfo.visibleItemsInfo
    val dragged = items.firstOrNull { it.key == draggedKey } ?: return order
    val center = dragged.offset + drag.offset + dragged.size / 2f
    val target =
        items.firstOrNull {
            it.key != draggedKey &&
                it.key.toString().startsWith("task-") &&
                center >= it.offset &&
                center <= it.offset + it.size
        }
            ?: return order
    val from = order.indexOf(drag.id)
    val to =
        order.indexOf(
            target.key
                .toString()
                .removePrefix("task-")
                .toLong(),
        )
    if (from < 0 || to < 0 || from == to) return order
    drag.offset -= (target.offset - dragged.offset).toFloat()
    return order.toMutableList().also { it.add(to, it.removeAt(from)) }
}

/** Enregistre la nouvelle place : entre ses voisins, ou toute la liste renumérotée si leurs positions sont égales. */
private fun commitReorder(
    repository: TodoRepository,
    id: Long?,
    order: List<Long>,
    byId: Map<Long, Task>,
) {
    val index = order.indexOf(id ?: return)
    if (index < 0) return
    val prev = order.getOrNull(index - 1)?.let { byId[it]?.position }
    val next = order.getOrNull(index + 1)?.let { byId[it]?.position }
    val position =
        when {
            prev == null && next == null -> return
            prev == null -> next!! - POSITION_STEP
            next == null -> prev + POSITION_STEP
            prev < next -> (prev + next) / 2
            else -> null
        }
    if (position != null) {
        repository.reorder(id, position)
    } else {
        // Positions confondues (jamais ordonnées côté serveur) : on numérote toute la liste.
        order.forEachIndexed { i, taskId -> repository.reorder(taskId, (i + 1) * POSITION_STEP) }
    }
}

@Composable
private fun ShareDialog(
    onShare: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var permission by remember { mutableStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Partager le projet") },
        text = {
            Column {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Nom d'utilisateur") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val choices = listOf(0 to "Lecture seule", 1 to "Lecture et écriture", 2 to "Administrateur")
                choices.forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        RadioButton(selected = permission == value, onClick = { permission = value })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onShare(username.trim(), permission) },
                enabled = username.isNotBlank(),
            ) { Text("Partager") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

private enum class ProjectDialog { RENAME, COLOR, MOVE, UP, DOWN, KANBAN, ARCHIVE, DELETE }

@Composable
private fun ProjectActionDialog(
    kind: ProjectDialog,
    projectTitle: String,
    projectId: Long,
    projects: List<com.lielu.todo.data.model.Project>,
    onDismiss: () -> Unit,
    onConfirm: (ProjectDialog, String) -> Unit,
) {
    var name by remember { mutableStateOf(projectTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (kind) {
                    ProjectDialog.RENAME -> "Renommer le projet"
                    ProjectDialog.COLOR -> "Couleur du projet"
                    ProjectDialog.MOVE -> "Déplacer « $projectTitle » dans…"
                    ProjectDialog.UP, ProjectDialog.DOWN, ProjectDialog.KANBAN -> ""
                    ProjectDialog.ARCHIVE -> "Archiver « $projectTitle » ?"
                    ProjectDialog.DELETE -> "Supprimer « $projectTitle » ?"
                },
            )
        },
        text = {
            when (kind) {
                ProjectDialog.RENAME ->
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                ProjectDialog.COLOR ->
                    Column {
                        ProjectPalette.chunked(5).forEach { row ->
                            Row {
                                row.forEach { hex ->
                                    TextButton(onClick = { onConfirm(kind, hex) }) {
                                        Text("●", color = hexColor(hex) ?: MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                ProjectDialog.MOVE -> {
                    // Un projet ne peut pas devenir le sous-projet de l'un de ses propres descendants.
                    val blocked = mutableSetOf(projectId)
                    repeat(projects.size) { projects.filter { it.parentId in blocked }.forEach { blocked += it.id } }
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        TextButton(onClick = { onConfirm(kind, "0") }) { Text("(Racine, sans parent)") }
                        projects.filter { it.id !in blocked }.forEach { target ->
                            TextButton(onClick = { onConfirm(kind, target.id.toString()) }) { Text(target.title) }
                        }
                    }
                }
                ProjectDialog.UP, ProjectDialog.DOWN, ProjectDialog.KANBAN -> Unit
                ProjectDialog.ARCHIVE -> Text("Le projet disparaît de la liste mais ses tâches sont conservées.")
                ProjectDialog.DELETE ->
                    Text("Le projet et TOUTES ses tâches seront supprimés du serveur. Cette action est définitive.")
            }
        },
        confirmButton = {
            if (kind != ProjectDialog.COLOR && kind != ProjectDialog.MOVE) {
                TextButton(
                    onClick = { onConfirm(kind, name.trim()) },
                    enabled = kind != ProjectDialog.RENAME || name.isNotBlank(),
                ) {
                    Text(
                        when (kind) {
                            ProjectDialog.RENAME -> "Renommer"
                            ProjectDialog.ARCHIVE -> "Archiver"
                            else -> "Supprimer"
                        },
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
