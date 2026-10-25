package com.lielu.todo.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.ui.common.AddFab
import com.lielu.todo.ui.common.EmptyState
import com.lielu.todo.ui.common.TaskRow
import com.lielu.todo.ui.common.toggleDone
import com.lielu.todo.ui.task.QuickAddSheet
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Les tâches d'un projet (ou d'une étiquette) : [projectId] ou [labelId], jamais les deux. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    repository: TodoRepository,
    projectId: Long?,
    labelId: Long?,
    onOpenTask: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    val title =
        when {
            projectId != null -> state.projects.firstOrNull { it.id == projectId }?.title ?: "Projet"
            else ->
                state.labels
                    .firstOrNull { it.id == labelId }
                    ?.title
                    ?.let { "@$it" } ?: "Étiquette"
        }
    val tasks =
        state.tasks
            .filter { !it.done }
            .filter { if (projectId != null) it.projectId == projectId else it.labels.any { l -> l.id == labelId } }
            .sortedWith(
                compareBy<Task> {
                    it.dueDate == null
                }.thenBy { it.dueDate }.thenByDescending { it.priority }.thenBy { it.position },
            )
    val projects = state.projects.associate { it.id to it.title }

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
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(tasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        today = today,
                        projectName = if (projectId == null) projects[task.projectId] else null,
                        onToggle = { done -> scope.toggleDone(repository, snackbar, task, done) },
                        onClick = { onOpenTask(task.id) },
                    )
                }
                if (tasks.isEmpty()) item { EmptyState("Aucune tâche.") }
            }
        }
    }

    if (showAdd) {
        QuickAddSheet(
            state = state,
            defaultProjectId = projectId ?: state.inboxId,
            defaultDate = null,
            onAdd = { text, project, due, priority, labels ->
                // Dans la vue d'une étiquette, la nouvelle tâche la porte d'office.
                val extra = state.labels.filter { it.id == labelId && labels.none { l -> l.id == it.id } }
                repository.addTask(text, project, due, priority, labels + extra)
            },
            onDismiss = { showAdd = false },
        )
    }
}
