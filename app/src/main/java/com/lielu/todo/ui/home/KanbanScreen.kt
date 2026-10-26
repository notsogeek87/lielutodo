package com.lielu.todo.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lielu.todo.data.model.Bucket
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.ui.common.dueLabel
import java.io.IOException
import java.time.LocalDate

/**
 * Vue Kanban d'un projet : une colonne par bucket Vikunja. Les colonnes vues restent affichées serveur coupé ;
 * « Déplacer vers… » fonctionne aussi hors ligne (le déplacement part au prochain envoi).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanScreen(
    repository: TodoRepository,
    projectId: Long,
    onOpenTask: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    var offline by remember { mutableStateOf(false) }
    LaunchedEffect(projectId) {
        offline =
            try {
                repository.refreshKanban(projectId)
                false
            } catch (e: IOException) {
                true
            }
    }
    val buckets = state.kanban[projectId].orEmpty()
    val byId = state.tasks.associateBy { it.id }
    val today = LocalDate.now()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.projects
                            .firstOrNull { it.id == projectId }
                            ?.title
                            .orEmpty() + " · Kanban",
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (offline) {
                Text(
                    "Hors ligne : colonnes en cache.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (buckets.isEmpty()) {
                Text(
                    "Aucune colonne : ouvrez ce projet une fois en ligne pour charger sa vue Kanban.",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(12.dp),
            ) {
                items(buckets, key = { it.id }) { bucket ->
                    val tasks = bucket.taskIds.mapNotNull { byId[it] }
                    Column(modifier = Modifier.width(280.dp).fillMaxHeight()) {
                        Text(
                            "${bucket.title} · ${tasks.size}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            tasks.forEach { task ->
                                KanbanCard(task, today, buckets, bucket, onOpenTask) {
                                    repository.moveToBucket(projectId, task.id, it.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KanbanCard(
    task: Task,
    today: LocalDate,
    buckets: List<Bucket>,
    current: Bucket,
    onOpenTask: (Long) -> Unit,
    onMove: (Bucket) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenTask(task.id) }) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge)
                task.dueDate?.let {
                    Text(
                        dueLabel(it, today),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { menu = true },
                ) { Icon(Icons.Filled.MoreVert, contentDescription = "Déplacer vers…") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    buckets.filter { it.id != current.id }.forEach { target ->
                        DropdownMenuItem(
                            text = { Text("Déplacer vers « ${target.title} »") },
                            onClick = {
                                menu = false
                                onMove(target)
                            },
                        )
                    }
                }
            }
        }
    }
}
