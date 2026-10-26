package com.lielu.todo.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lielu.todo.calendar.CalendarSync
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.settings.AppPrefs
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.data.todo.TodoState
import com.lielu.todo.reminder.ReminderScheduler
import com.lielu.todo.ui.common.AddFab
import com.lielu.todo.ui.common.EmptyState
import com.lielu.todo.ui.common.ScreenTitle
import com.lielu.todo.ui.common.SectionHeader
import com.lielu.todo.ui.common.TaskRow
import com.lielu.todo.ui.common.dayHeader
import com.lielu.todo.ui.common.localDate
import com.lielu.todo.ui.common.projectColors
import com.lielu.todo.ui.common.syncSummary
import com.lielu.todo.ui.common.toggleDone
import com.lielu.todo.ui.task.QuickAddSheet
import com.lielu.todo.ui.task.TaskDetailScreen
import com.lielu.todo.ui.task.add
import com.lielu.todo.ui.theme.LocalTodoColors
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val RAIL_MIN_WIDTH_DP = 600
private const val TWO_PANE_MIN_WIDTH_DP = 840
private const val LIST_PANE_WEIGHT = 0.8f

private enum class HomeTab(
    val label: String,
    val icon: ImageVector,
) {
    INBOX("Tâches", Icons.Outlined.Inbox),
    TODAY("Auj", Icons.Outlined.Today),
    UPCOMING("Prochainement", Icons.Outlined.CalendarMonth),
    BROWSE("Parcourir", Icons.Filled.Menu),
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
    onOpenCompleted: () -> Unit,
    onSearch: () -> Unit,
    reminderScheduler: ReminderScheduler,
    calendarSync: CalendarSync,
    prefs: AppPrefs,
    onOpenNotifications: () -> Unit,
    onOpenActivity: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(HomeTab.INBOX.ordinal) }
    var showAdd by remember { mutableStateOf(false) }
    var upcomingSettings by remember { mutableStateOf(prefs.upcoming) }
    var inboxShowDone by remember { mutableStateOf(prefs.inboxShowDone) }
    // 0 = tous les projets ; sinon Aujourd'hui et Prochainement n'affichent que les tâches de ce projet.
    var projectFilter by rememberSaveable { mutableLongStateOf(0L) }
    // Boîte de réception : 0 = la boîte elle-même (défaut), ALL_PROJECTS = toutes les tâches, sinon un projet.
    var inboxFilter by rememberSaveable { mutableLongStateOf(0L) }
    val today = LocalDate.now()
    // Écran large (Fold déplié, tablette) : rail latéral au lieu de la barre du bas ; en très large, liste à gauche
    // et tâche ouverte dans un volet à droite (au lieu d'une liste étirée sur toute la largeur).
    val widthDp = LocalConfiguration.current.screenWidthDp
    val useRail = widthDp >= RAIL_MIN_WIDTH_DP
    val showPane = widthDp >= TWO_PANE_MIN_WIDTH_DP && HomeTab.entries[tab] != HomeTab.BROWSE
    var selectedTaskId by rememberSaveable { mutableLongStateOf(0L) }
    BackHandler(enabled = showPane && selectedTaskId != 0L) { selectedTaskId = 0L }
    val openTask: (Long) -> Unit = if (showPane) ({ selectedTaskId = it }) else onOpenTask
    // Un projet supprimé ou archivé ailleurs ne doit pas laisser un filtre fantôme qui vide les listes.
    val activeFilter = projectFilter.takeIf { id -> state.projects.any { it.id == id } } ?: 0L
    val visible =
        if (activeFilter == 0L) state else state.copy(tasks = state.tasks.filter { it.projectId == activeFilter })
    val filterTitle = state.projects.firstOrNull { it.id == activeFilter }?.title
    val activeInbox = inboxFilter.takeIf { id -> id == ALL_PROJECTS || state.projects.any { it.id == id } } ?: 0L
    val projectColors = projectColors(state.projects)
    val filterable = HomeTab.entries[tab] == HomeTab.TODAY || HomeTab.entries[tab] == HomeTab.UPCOMING
    val titleActions: @Composable () -> Unit = {
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Rechercher") }
        when (HomeTab.entries[tab]) {
            HomeTab.INBOX -> {
                IconButton(
                    onClick = {
                        inboxShowDone = !inboxShowDone
                        prefs.inboxShowDone = inboxShowDone
                    },
                ) {
                    Icon(
                        if (inboxShowDone) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                        contentDescription =
                            if (inboxShowDone) "Masquer les tâches achevées" else "Afficher les tâches achevées",
                        tint =
                            if (inboxShowDone) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                    )
                }
                ProjectFilterButton(
                    options =
                        listOf(
                            FilterOption(0L, "Tâches personnelles", 0),
                            FilterOption(ALL_PROJECTS, "Tous les projets", 0),
                        ) +
                            orderedProjects(state.projects.filter { it.id != state.inboxId })
                                .map { (p, depth) -> FilterOption(p.id, p.title, depth, projectColors[p.id]) },
                    active = activeInbox,
                    default = 0L,
                    onPick = { inboxFilter = it },
                )
            }
            HomeTab.TODAY, HomeTab.UPCOMING ->
                ProjectFilterButton(
                    options =
                        listOf(FilterOption(0L, "Tous les projets", 0)) +
                            orderedProjects(state.projects)
                                .map { (p, depth) -> FilterOption(p.id, p.title, depth, projectColors[p.id]) },
                    active = activeFilter,
                    default = 0L,
                    onPick = { projectFilter = it },
                )
            HomeTab.BROWSE -> Unit
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
            if (!useRail) {
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
            }
        },
        floatingActionButton = { if (!showPane) AddFab(onClick = { showAdd = true }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Row(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (useRail) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                    Spacer(Modifier.weight(1f))
                    HomeTab.entries.forEachIndexed { index, item ->
                        NavigationRailItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label, maxLines = 1) },
                            colors =
                                NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.outline,
                                    unselectedTextColor = MaterialTheme.colorScheme.outline,
                                ),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Box(modifier = Modifier.weight(if (showPane) LIST_PANE_WEIGHT else 1f).fillMaxHeight()) {
                PullToRefreshBox(
                    isRefreshing = state.syncing,
                    onRefresh = { scope.launch { repository.refresh() } },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val onToggle = { task: Task, done: Boolean -> scope.toggleDone(repository, snackbar, task, done) }
                    when (HomeTab.entries[tab]) {
                        HomeTab.INBOX ->
                            InboxTab(
                                state,
                                today,
                                activeInbox,
                                inboxShowDone,
                                onToggle,
                                openTask,
                                titleActions,
                            )
                        HomeTab.TODAY ->
                            TodayTab(visible, today, onToggle, openTask, titleActions, filterTitle, onReschedule = {
                                val moved = repository.rescheduleOverdue(today)
                                scope.launch { snackbar.showSnackbar("$moved tâche(s) reportée(s) à aujourd'hui") }
                            })
                        HomeTab.UPCOMING ->
                            UpcomingTab(
                                visible,
                                today,
                                upcomingSettings,
                                {
                                    upcomingSettings = it
                                    prefs.upcoming = it
                                },
                                onToggle,
                                openTask,
                                titleActions,
                                filterTitle,
                            )
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
                                onOpenCompleted = onOpenCompleted,
                                reminderScheduler = reminderScheduler,
                                calendarSync = calendarSync,
                                prefs = prefs,
                                onOpenNotifications = onOpenNotifications,
                                onOpenActivity = onOpenActivity,
                                onSettings = onSettings,
                                onLogout = onLogout,
                            )
                    }
                }
                if (showPane) {
                    AddFab(
                        onClick = { showAdd = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )
                }
            }
            if (showPane) {
                VerticalDivider()
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (selectedTaskId == 0L) {
                        EmptyState("Sélectionne une tâche pour l'afficher ici", Modifier.align(Alignment.Center))
                    } else {
                        TaskDetailScreen(
                            repository = repository,
                            taskId = selectedTaskId,
                            onOpenTask = { selectedTaskId = it },
                            onBack = { selectedTaskId = 0L },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        QuickAddSheet(
            state = state,
            defaultProjectId =
                when {
                    HomeTab.entries[tab] == HomeTab.INBOX && activeInbox > 0 -> activeInbox
                    filterable && activeFilter != 0L -> activeFilter
                    else -> state.inboxId
                },
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
    val dated = state.tasks.filter { !it.done && it.plannedAt != null }
    val overdue = dated.filter { it.plannedAt!!.localDate().isBefore(today) }.sortedBy { it.plannedAt }
    val dueToday =
        dated
            .filter { it.plannedAt!!.localDate() == today }
            .sortedWith(compareBy<Task> { it.plannedAt }.thenByDescending { it.priority })
    if (!state.loaded) {
        LoadingState()
        return
    }
    val projects = state.projects.associate { it.id to it.title }
    val projectColors = projectColors(state.projects)
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
            }) {
                TaskRow(
                    it,
                    today,
                    projects[it.projectId],
                    { d -> onToggle(it, d) },
                    { onOpenTask(it.id) },
                    projectColor = projectColors[it.projectId],
                )
            }
        }
        if (dueToday.isNotEmpty()) {
            item { SectionHeader(if (overdue.isEmpty()) dayHeader(today) else "Aujourd'hui", count = dueToday.size) }
            items(dueToday, key = {
                it.id
            }) {
                TaskRow(
                    it,
                    today,
                    projects[it.projectId],
                    { d -> onToggle(it, d) },
                    { onOpenTask(it.id) },
                    projectColor = projectColors[it.projectId],
                )
            }
        }
        if (overdue.isEmpty() && dueToday.isEmpty()) item { EmptyState("Rien de prévu aujourd'hui.") }
    }
}

@Composable
private fun InboxTab(
    state: TodoState,
    today: LocalDate,
    filter: Long,
    showDone: Boolean,
    onToggle: (Task, Boolean) -> Unit,
    onOpenTask: (Long) -> Unit,
    actions: @Composable () -> Unit,
) {
    if (!state.loaded) {
        LoadingState()
        return
    }
    val tasks =
        state.tasks
            .filter {
                (showDone || !it.done) &&
                    when (filter) {
                        0L -> it.projectId == state.inboxId || it.assignees.any { a -> a.id == state.userId }
                        ALL_PROJECTS -> true
                        else -> it.projectId == filter
                    }
            }.sortedWith(
                compareBy<Task> { it.done }
                    .thenBy { it.projectId != state.inboxId }
                    .thenBy(nullsLast()) { it.plannedAt }
                    .thenByDescending { it.priority }
                    .thenBy { it.position }
                    .thenBy { it.id },
            )
    val projects = state.projects.associate { it.id to it.title }
    val projectColors = projectColors(state.projects)
    // Titre = projet sélectionné (avec sa couleur) ; la boîte de réception s'appelle « Tâches personnelles » ;
    // « Tous les projets » n'a pas de couleur.
    val titleProject = if (filter == 0L) state.inboxId else filter
    val title =
        when (filter) {
            ALL_PROJECTS -> "Tous les projets"
            0L -> "Tâches personnelles"
            else -> projects[titleProject] ?: "Tâches"
        }
    val titleColor =
        if (filter == ALL_PROJECTS) null else projectColors[titleProject] ?: MaterialTheme.colorScheme.outline
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            val openCount = tasks.count { !it.done }
            val count = if (openCount == 0) null else "$openCount tâche${if (openCount > 1) "s" else ""}"
            ScreenTitle(
                title,
                listOfNotNull(
                    count,
                    if (showDone) "achevées affichées" else null,
                ).joinToString(" · ").ifEmpty { null },
                actions = actions,
                hashColor = titleColor,
            )
        }
        items(tasks, key = { it.id }) {
            // Hors de la boîte elle-même, on rappelle le projet de chaque tâche.
            TaskRow(
                it,
                today,
                if (filter == 0L && it.projectId == state.inboxId) null else projects[it.projectId],
                { d -> onToggle(it, d) },
                { onOpenTask(it.id) },
                projectColor = projectColors[it.projectId],
            )
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

/** Option du menu de filtre : valeur, libellé, profondeur d'indentation, couleur du projet (null = aucune). */
private data class FilterOption(
    val value: Long,
    val label: String,
    val depth: Int,
    val color: Color? = null,
)

/** Valeur de filtre « tous les projets » de la boîte de réception (les ids de projets sont positifs). */
private const val ALL_PROJECTS = -1L

/**
 * Filtre par projet : menu d'options (valeur, libellé, profondeur d'indentation). Le bouton est coloré quand le
 * filtre actif n'est pas la valeur par défaut [default].
 */
@Composable
private fun ProjectFilterButton(
    options: List<FilterOption>,
    active: Long,
    default: Long,
    onPick: (Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.FilterList,
                contentDescription = "Filtrer par projet",
                tint =
                    if (active != default) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label, depth, color) ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("  ".repeat(depth))
                            if (color != null) {
                                Box(Modifier.padding(end = 8.dp).size(10.dp).background(color, CircleShape))
                            }
                            Text(label, color = color ?: Color.Unspecified)
                        }
                    },
                    leadingIcon = { if (active == value) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        onPick(value)
                        open = false
                    },
                )
            }
        }
    }
}
