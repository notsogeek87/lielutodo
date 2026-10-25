package com.lielu.todo.ui.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lielu.todo.data.model.Comment
import com.lielu.todo.data.todo.QuickAdd
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.ui.common.DatePickerSheet
import com.lielu.todo.ui.common.LabelsDialog
import com.lielu.todo.ui.common.PriorityMenu
import com.lielu.todo.ui.common.ProjectMenu
import com.lielu.todo.ui.common.TaskCheckbox
import com.lielu.todo.ui.common.borderlessFieldColors
import com.lielu.todo.ui.common.dueLabel
import com.lielu.todo.ui.common.localDate
import com.lielu.todo.ui.common.priorityName
import com.lielu.todo.ui.theme.LocalTodoColors
import com.lielu.todo.ui.theme.forPriority
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    repository: TodoRepository,
    taskId: Long,
    onBack: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val task = state.tasks.firstOrNull { it.id == taskId }
    val colors = LocalTodoColors.current
    val scope = rememberCoroutineScope()

    // La tâche a disparu (supprimée, ou remplacée par son id serveur après un envoi) : retour à la liste.
    if (task == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var title by remember(task.id) { mutableStateOf(task.title) }
    var description by remember(task.id) { mutableStateOf(task.description) }
    var showDate by remember { mutableStateOf(false) }
    var showPriority by remember { mutableStateOf(false) }
    var showProject by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(false) }
    var comments by remember(task.id) { mutableStateOf<List<Comment>>(emptyList()) }
    var commentError by remember { mutableStateOf<String?>(null) }
    var newComment by remember { mutableStateOf("") }
    val today = LocalDate.now()

    // Titre et description s'enregistrent en quittant l'écran, sans bouton « Enregistrer ».
    val latestTitle by rememberUpdatedState(title)
    val latestDescription by rememberUpdatedState(description)
    DisposableEffect(task.id) {
        onDispose {
            val current = repository.task(task.id) ?: return@onDispose
            val newTitle = latestTitle.trim().ifEmpty { current.title }
            if (newTitle != current.title || latestDescription != current.description) {
                repository.updateTask(current.copy(title = newTitle, description = latestDescription))
            }
        }
    }

    suspend fun loadComments() {
        if (task.id < 0) return
        try {
            comments = repository.comments(task.id)
            commentError = null
        } catch (e: IOException) {
            commentError = "Commentaires indisponibles hors ligne."
        }
    }
    LaunchedEffect(task.id) { loadComments() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Text(
                        state.projects
                            .firstOrNull { it.id == task.projectId }
                            ?.title
                            .orEmpty(),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    IconButton(onClick = {
                        repository.deleteTask(task.id)
                        onBack()
                    }) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer la tâche") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                TaskCheckbox(task.done, task.priority, onToggle = { repository.setDone(task.id, it) })
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    colors = borderlessFieldColors(),
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                    textStyle =
                        MaterialTheme.typography.titleMedium.copy(
                            textDecoration = if (task.done) TextDecoration.LineThrough else null,
                        ),
                )
            }
            TextField(
                value = description,
                onValueChange = { description = it },
                placeholder = { Text("Description") },
                colors = borderlessFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 4.dp, bottom = 8.dp),
                minLines = 2,
            )

            val due = task.dueDate
            DetailRow(Icons.Filled.CalendarToday, due?.let { dueLabel(it, today) } ?: "Date d'échéance") {
                showDate =
                    true
            }
            Box {
                DetailRow(Icons.Filled.Flag, priorityName(task.priority), tint = colors.forPriority(task.priority)) {
                    showPriority =
                        true
                }
                PriorityMenu(showPriority, onDismiss = { showPriority = false }) {
                    repository.updateTask(task.copy(priority = it))
                    showPriority = false
                }
            }
            Box {
                DetailRow(
                    Icons.Filled.Inbox,
                    state.projects.firstOrNull { it.id == task.projectId }?.title ?: "Projet",
                ) {
                    showProject =
                        true
                }
                ProjectMenu(showProject, state.projects, onDismiss = { showProject = false }) {
                    repository.updateTask(task.copy(projectId = it.id))
                    showProject = false
                }
            }
            DetailRow(
                Icons.AutoMirrored.Filled.Label,
                if (task.labels.isEmpty()) "Étiquettes" else task.labels.joinToString { it.title },
            ) { showLabels = true }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "Commentaires",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            commentError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            comments.forEach { c ->
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        c.author,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    // Vikunja stocke les commentaires en HTML ; on affiche le texte sans les balises.
                    Text(stripHtml(c.text), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (task.id > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = newComment,
                        onValueChange = { newComment = it },
                        placeholder = { Text("Ajouter un commentaire") },
                        shape = MaterialTheme.shapes.medium,
                        colors =
                            borderlessFieldColors().copy(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = newComment.isNotBlank(),
                        onClick = {
                            val text = newComment.trim()
                            scope.launch {
                                try {
                                    repository.addComment(task.id, text)
                                    newComment = ""
                                    loadComments()
                                } catch (e: IOException) {
                                    commentError = "Commentaire non envoyé : ${e.message}"
                                }
                            }
                        },
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer le commentaire") }
                }
            }
        }
    }

    if (showDate) {
        DatePickerSheet(
            initial = task.dueDate?.localDate(),
            onPick = { date ->
                showDate = false
                val zone = ZoneId.systemDefault()
                // On garde l'heure existante ; une nouvelle date seule reprend « midi » (date sans heure).
                val time = task.dueDate?.atZone(zone)?.toLocalTime() ?: QuickAdd.DATE_ONLY_TIME
                repository.updateTask(task.copy(dueDate = date?.atTime(time)?.atZone(zone)?.toInstant()))
            },
            onDismiss = { showDate = false },
        )
    }
    if (showLabels) {
        LabelsDialog(
            all = state.labels,
            selected = task.labels,
            onConfirm = {
                showLabels = false
                repository.updateTask(task.copy(labels = it), labelsChanged = true)
            },
            onDismiss = { showLabels = false },
        )
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    text: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(text, modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun stripHtml(html: String): String =
    html
        .replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()
