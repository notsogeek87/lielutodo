package com.lielu.todo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.data.todo.TodoState
import com.lielu.todo.reminder.ReminderScheduler
import com.lielu.todo.ui.common.AddFab
import com.lielu.todo.ui.common.EmptyState
import com.lielu.todo.ui.common.ScreenTitle
import com.lielu.todo.ui.common.SectionHeader
import com.lielu.todo.ui.common.TaskRow
import com.lielu.todo.ui.common.dayHeader
import com.lielu.todo.ui.common.dayLabel
import com.lielu.todo.ui.common.localDate
import com.lielu.todo.ui.common.syncSummary
import com.lielu.todo.ui.common.toggleDone
import com.lielu.todo.ui.task.QuickAddSheet
import com.lielu.todo.ui.task.add
import com.lielu.todo.ui.theme.LocalTodoColors
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class HomeTab(
    val label: String,
) {
    INBOX("Boîte"),
    TODAY("Auj"),
    UPCOMING("Prochainement"),
    BROWSE("Parcourir"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: TodoRepository,
    userName: String,
    serverLabel: String,
    onOpenTask: (Long) -> Unit,
    onOpenProject: (Long) -> Unit,
    onOpenLabel: (Long) -> Unit,
    onOpenFilter: (Long) -> Unit,
    onOpenFavorites: () -> Unit,
    onSearch: () -> Unit,
    reminderScheduler: ReminderScheduler,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(HomeTab.TODAY.ordinal) }
    var showAdd by remember { mutableStateOf(false) }
    // 0 = tous les projets ; sinon Aujourd'hui et Prochainement n'affichent que les tâches de ce projet.
    var projectFilter by rememberSaveable { mutableLongStateOf(0L) }
    val today = LocalDate.now()
    // Un projet supprimé ou archivé ailleurs ne doit pas laisser un filtre fantôme qui vide les listes.
    val activeFilter = projectFilter.takeIf { id -> state.projects.any { it.id == id } } ?: 0L
    val visible =
        if (activeFilter == 0L) state else state.copy(tasks = state.tasks.filter { it.projectId == activeFilter })
    val filterTitle = state.projects.firstOrNull { it.id == activeFilter }?.title
    val filterable = HomeTab.entries[tab] == HomeTab.TODAY || HomeTab.entries[tab] == HomeTab.UPCOMING
    val titleActions: @Composable () -> Unit = {
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Rechercher") }
        if (filterable) {
            ProjectFilterButton(state, activeFilter, onPick = { projectFilter = it })
        }
    }

    NotificationPermissionRequest()

    // Comme Todoist : on resynchronise à chaque retour au premier plan.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { repository.refresh() } }

    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        snackbar.showSnackbar(
            if (state.pending >
                0
            ) {
                "$message · ${state.pending} modification(s) en attente"
            } else {
                message
            },
        )
        repository.clearError()
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                HomeTab.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            Icon(
                                when (item) {
                                    HomeTab.INBOX -> Icons.Outlined.Inbox
                                    HomeTab.TODAY -> Icons.Outlined.Today
                                    HomeTab.UPCOMING -> Icons.Outlined.CalendarMonth
                                    HomeTab.BROWSE -> Icons.Filled.Menu
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(item.label, maxLines = 1) },
                        colors =
                            NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.outline,
                                unselectedTextColor = MaterialTheme.colorScheme.outline,
                            ),
                    )
                }
            }
        },
        floatingActionButton = { AddFab(onClick = { showAdd = true }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = { scope.launch { repository.refresh() } },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val onToggle: (Task, Boolean) -> Unit = { task, done -> scope.toggleDone(repository, snackbar, task, done) }
            when (HomeTab.entries[tab]) {
                HomeTab.INBOX -> InboxTab(state, today, onToggle, onOpenTask, titleActions)
                HomeTab.TODAY ->
                    TodayTab(visible, today, onToggle, onOpenTask, titleActions, filterTitle, onReschedule = {
                        val moved = repository.rescheduleOverdue(today)
                        scope.launch { snackbar.showSnackbar("$moved tâche(s) reportée(s) à aujourd'hui") }
                    })
                HomeTab.UPCOMING -> UpcomingTab(visible, today, onToggle, onOpenTask, titleActions, filterTitle)
                HomeTab.BROWSE ->
                    BrowseTab(
                        repository = repository,
                        state = state,
                        userName = userName,
                        serverLabel = serverLabel,
                        snackbar = snackbar,
                        onOpenProject = onOpenProject,
                        onOpenLabel = onOpenLabel,
                        onOpenFilter = onOpenFilter,
                        onOpenFavorites = onOpenFavorites,
                        reminderScheduler = reminderScheduler,
                        onSettings = onSettings,
                        onLogout = onLogout,
                    )
            }
        }
    }

    if (showAdd) {
        QuickAddSheet(
            state = state,
            defaultProjectId = if (filterable && activeFilter != 0L) activeFilter else state.inboxId,
            defaultDate = if (HomeTab.entries[tab] == HomeTab.TODAY) today else null,
            onAdd = { repository.add(it) },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun TodayTab(
    state: TodoState,
    today: LocalDate,
    onToggle: (Task, Boolean) -> Unit,
    onOpenTask: (Long) -> Unit,
    actions: @Composable () -> Unit,
    filterTitle: String?,
    onReschedule: () -> Unit,
) {
    val colors = LocalTodoColors.current
    val dated = state.tasks.filter { !it.done && it.dueDate != null }
    val overdue = dated.filter { it.dueDate!!.localDate().isBefore(today) }.sortedBy { it.dueDate }
    val dueToday =
        dated
            .filter { it.dueDate!!.localDate() == today }
            .sortedWith(compareBy<Task> { it.dueDate }.thenByDescending { it.priority })
    if (!state.loaded) {
        LoadingState()
        return
    }
    val projects = state.projects.associate { it.id to it.title }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            val count = overdue.size + dueToday.size
            val summary = if (count == 0) null else "$count tâche${if (count > 1) "s" else ""}"
            val sync = syncSummary(state, System.currentTimeMillis())
            val project = filterTitle?.let { "Projet : $it" }
            ScreenTitle(
                "Aujourd'hui",
                listOfNotNull(summary, project, sync).joinToString(" · ").ifEmpty { null },
                actions = actions,
            )
        }
        if (overdue.isNotEmpty()) {
            item {
                SectionHeader(
                    "En retard",
                    color = colors.overdue,
                    count = overdue.size,
                    actionLabel = "Reporter",
                    onAction = onReschedule,
                )
            }
            items(overdue, key = {
                it.id
            }) { TaskRow(it, today, projects[it.projectId], { d -> onToggle(it, d) }, { onOpenTask(it.id) }) }
        }
        if (dueToday.isNotEmpty()) {
            item { SectionHeader(if (overdue.isEmpty()) dayHeader(today) else "Aujourd'hui", count = dueToday.size) }
            items(dueToday, key = {
                it.id
            }) { TaskRow(it, today, projects[it.projectId], { d -> onToggle(it, d) }, { onOpenTask(it.id) }) }
        }
        if (overdue.isEmpty() && dueToday.isEmpty()) item { EmptyState("Rien de prévu aujourd'hui.") }
    }
}

@Composable
private fun UpcomingTab(
    state: TodoState,
    today: LocalDate,
    onToggle: (Task, Boolean) -> Unit,
    onOpenTask: (Long) -> Unit,
    actions: @Composable () -> Unit,
    filterTitle: String?,
) {
    val colors = LocalTodoColors.current
    val dated = state.tasks.filter { !it.done && it.dueDate != null }
    val overdue = dated.filter { it.dueDate!!.localDate().isBefore(today) }.sortedBy { it.dueDate }
    val byDay =
        dated
            .filter { !it.dueDate!!.localDate().isBefore(today) }
            .groupBy { it.dueDate!!.localDate() }
            .toSortedMap()
    if (!state.loaded) {
        LoadingState()
        return
    }
    val projects = state.projects.associate { it.id to it.title }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item { ScreenTitle("Prochainement", filterTitle?.let { "Projet : $it" }, actions = actions) }
        if (overdue.isNotEmpty()) {
            item { SectionHeader("En retard", color = colors.overdue, count = overdue.size) }
            items(overdue, key = {
                it.id
            }) { TaskRow(it, today, projects[it.projectId], { d -> onToggle(it, d) }, { onOpenTask(it.id) }) }
        }
        byDay.forEach { (day, tasks) ->
            item(key = "day-$day") {
                SectionHeader(
                    dayHeader(day) + if (day == today) " · ${dayLabel(day, today)}" else "",
                )
            }
            items(tasks.sortedWith(compareBy<Task> { it.dueDate }.thenByDescending { it.priority }), key = { it.id }) {
                TaskRow(it, today, projects[it.projectId], { d -> onToggle(it, d) }, { onOpenTask(it.id) })
            }
        }
        if (overdue.isEmpty() && byDay.isEmpty()) item { EmptyState("Aucune tâche planifiée.") }
    }
}

@Composable
private fun InboxTab(
    state: TodoState,
    today: LocalDate,
    onToggle: (Task, Boolean) -> Unit,
    onOpenTask: (Long) -> Unit,
    actions: @Composable () -> Unit,
) {
    if (!state.loaded) {
        LoadingState()
        return
    }
    val tasks = state.tasks.filter { !it.done && it.projectId == state.inboxId }.sortedBy { it.position }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            ScreenTitle(
                "Boîte de réception",
                if (tasks.isEmpty()) null else "${tasks.size} tâche${if (tasks.size > 1) "s" else ""}",
                actions = actions,
            )
        }
        items(tasks, key = { it.id }) {
            TaskRow(it, today, null, { d -> onToggle(it, d) }, { onOpenTask(it.id) })
        }
        if (tasks.isEmpty()) item { EmptyState("Votre boîte de réception est vide.") }
    }
}

@Composable
internal fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            "Chargement…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Android 13+ : les rappels ne s'affichent qu'avec cette autorisation, demandée une fois à l'ouverture. */
@Composable
private fun NotificationPermissionRequest() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestPermission(),
        ) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Filtre par projet : menu des projets (arborescence), « Tous les projets » remet à zéro. */
@Composable
private fun ProjectFilterButton(
    state: TodoState,
    active: Long,
    onPick: (Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.FilterList,
                contentDescription = "Filtrer par projet",
                tint =
                    if (active !=
                        0L
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Tous les projets") },
                leadingIcon = { if (active == 0L) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = {
                    onPick(0L)
                    open = false
                },
            )
            orderedProjects(state.projects).forEach { (project, depth) ->
                DropdownMenuItem(
                    text = { Text("  ".repeat(depth) + project.title) },
                    leadingIcon = { if (active == project.id) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        onPick(project.id)
                        open = false
                    },
                )
            }
        }
    }
}
