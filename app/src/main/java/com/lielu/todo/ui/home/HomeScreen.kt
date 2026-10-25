package com.lielu.todo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.lielu.todo.ui.common.AddFab
import com.lielu.todo.ui.common.EmptyState
import com.lielu.todo.ui.common.ScreenTitle
import com.lielu.todo.ui.common.SectionHeader
import com.lielu.todo.ui.common.TaskRow
import com.lielu.todo.ui.common.dayHeader
import com.lielu.todo.ui.common.dayLabel
import com.lielu.todo.ui.common.localDate
import com.lielu.todo.ui.common.toggleDone
import com.lielu.todo.ui.task.QuickAddSheet
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
    onSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(HomeTab.TODAY.ordinal) }
    var showAdd by remember { mutableStateOf(false) }
    val today = LocalDate.now()

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
                HomeTab.INBOX -> InboxTab(state, today, onToggle, onOpenTask)
                HomeTab.TODAY -> TodayTab(state, today, onToggle, onOpenTask)
                HomeTab.UPCOMING -> UpcomingTab(state, today, onToggle, onOpenTask)
                HomeTab.BROWSE ->
                    BrowseTab(
                        repository = repository,
                        state = state,
                        userName = userName,
                        serverLabel = serverLabel,
                        snackbar = snackbar,
                        onOpenProject = onOpenProject,
                        onOpenLabel = onOpenLabel,
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
            onAdd = { title, projectId, due, priority, labels ->
                repository.addTask(title, projectId, due, priority, labels)
            },
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
            ScreenTitle("Aujourd'hui", if (count == 0) null else "$count tâche${if (count > 1) "s" else ""}")
        }
        if (overdue.isNotEmpty()) {
            item { SectionHeader("En retard", color = colors.overdue, count = overdue.size) }
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
        item { ScreenTitle("Prochainement") }
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
