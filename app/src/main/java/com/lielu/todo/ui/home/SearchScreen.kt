package com.lielu.todo.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.data.todo.searchTasks
import com.lielu.todo.ui.common.EmptyState
import com.lielu.todo.ui.common.TaskRow
import com.lielu.todo.ui.common.projectColors
import java.time.LocalDate

/** Recherche dans toutes les tâches du cache : fonctionne serveur coupé. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: TodoRepository,
    onOpenTask: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val results = remember(query, state.tasks) { searchTasks(state.tasks, query) }
    val projects = state.projects.associate { it.id to it.title }
    val projectColors = projectColors(state.projects)
    val today = LocalDate.now()
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Rechercher une tâche") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(results, key = { it.id }) { task ->
                TaskRow(
                    task = task,
                    today = today,
                    projectName = projects[task.projectId],
                    projectColor = projectColors[task.projectId],
                    onToggle = { repository.setDone(task.id, it) },
                    onClick = { onOpenTask(task.id) },
                )
            }
            if (query.isNotBlank() && results.isEmpty()) item { EmptyState("Aucun résultat.") }
        }
    }
}
