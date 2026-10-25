package com.lielu.todo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.lielu.todo.ui.common.EmptyState
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
    val title: String,
) {
    TODAY("Aujourd'hui"),
    UPCOMING("À venir"),
    BROWSE("Parcourir"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: TodoRepository,
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val today = LocalDate.now()

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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(HomeTab.entries[tab].title)
                        syncSummary(state, System.currentTimeMillis())?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                },
                actions = {
                    if (state.syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(24.dp),
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            Icon(
                                when (item) {
                                    HomeTab.TODAY -> Icons.Filled.Today
                                    HomeTab.UPCOMING -> Icons.Filled.CalendarMonth
                                    HomeTab.BROWSE -> Icons.Filled.Folder
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(item.title) },
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
            ) { Icon(Icons.Filled.Add, contentDescription = "Ajouter une tâche") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = { scope.launch { repository.refresh() } },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val onToggle: (Task, Boolean) -> Unit = { task, done -> scope.toggleDone(repository, snackbar, task, done) }
            when (HomeTab.entries[tab]) {
                HomeTab.TODAY ->
                    TodayTab(state, today, onToggle, onOpenTask, onReschedule = {
                        val moved = repository.rescheduleOverdue(today)
                        scope.launch { snackbar.showSnackbar("$moved tâche(s) reportée(s) à aujourd'hui") }
                    })
                HomeTab.UPCOMING -> UpcomingTab(state, today, onToggle, onOpenTask)
                HomeTab.BROWSE ->
                    BrowseTab(
                        repository = repository,
                        state = state,
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
            defaultProjectId = state.inboxId,
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
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (overdue.isNotEmpty()) {
            item { SectionHeader("En retard", color = colors.overdue) }
            items(overdue, key = {
                it.id
            }) { TaskRow(it, today, projects[it.projectId], { d -> onToggle(it, d) }, { onOpenTask(it.id) }) }
        }
        if (dueToday.isNotEmpty()) {
            item { SectionHeader("Aujourd'hui") }
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
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (overdue.isNotEmpty()) {
            item { SectionHeader("En retard", color = colors.overdue) }
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
