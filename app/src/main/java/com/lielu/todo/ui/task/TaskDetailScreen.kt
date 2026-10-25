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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lielu.todo.data.todo.QuickAdd
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.ui.common.AssigneesDialog
import com.lielu.todo.ui.common.DatePickerSheet
import com.lielu.todo.ui.common.LabelsDialog
import com.lielu.todo.ui.common.PriorityMenu
import com.lielu.todo.ui.common.ProjectMenu
import com.lielu.todo.ui.common.RemindersDialog
import com.lielu.todo.ui.common.RepeatDialog
import com.lielu.todo.ui.common.TaskRow
import com.lielu.todo.ui.common.TimePickerDialog
import com.lielu.todo.ui.common.absoluteReminder
import com.lielu.todo.ui.common.dueLabel
import com.lielu.todo.ui.common.localDate
import com.lielu.todo.ui.common.priorityName
import com.lielu.todo.ui.common.reminderLabel
import com.lielu.todo.ui.common.repeatLabel
import com.lielu.todo.ui.theme.LocalTodoColors
import com.lielu.todo.ui.theme.forPriority
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    repository: TodoRepository,
    taskId: Long,
    onOpenTask: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val task = state.tasks.firstOrNull { it.id == taskId }
    val colors = LocalTodoColors.current

    // La tâche a disparu (supprimée, ou remplacée par son id serveur après un envoi) : retour à la liste.
    if (task == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var title by remember(task.id) { mutableStateOf(task.title) }
    var description by remember(task.id) { mutableStateOf(task.description) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var pendingReminderDate by remember { mutableStateOf<LocalDate?>(null) }
    var showPriority by remember { mutableStateOf(false) }
    var showProject by remember { mutableStateOf(false) }
    var commentNote by remember { mutableStateOf<String?>(null) }
    var newComment by remember { mutableStateOf("") }
    var newSubtask by remember { mutableStateOf("") }
    var usersLoading by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val comments = state.comments[task.id].orEmpty()
    val subtasks =
        state.tasks
            .filter {
                it.parentId == task.id
            }.sortedWith(compareBy({ it.done }, { it.position }, { it.id }))
    val parent = state.tasks.firstOrNull { it.id == task.parentId }

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

    // Les commentaires déjà vus restent lisibles hors ligne ; on les rafraîchit quand le serveur répond.
    LaunchedEffect(task.id) {
        try {
            repository.refreshComments(task.id)
            commentNote = null
        } catch (e: IOException) {
            commentNote =
                if (comments.isEmpty()) {
                    "Commentaires indisponibles : serveur injoignable."
                } else {
                    "Hors ligne : commentaires en cache."
                }
        }
    }

    /** Date d'échéance donnée : on la crée à midi (« date seule ») si la tâche n'en avait pas. */
    fun withDefaultDue() = task.dueDate ?: today.atTime(QuickAdd.DATE_ONLY_TIME).atZone(zone).toInstant()

    Scaffold(
        topBar = {
            TopAppBar(
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
            if (parent != null) {
                Text(
                    "Sous-tâche de « ${parent.title} »",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .clickable {
                                onOpenTask(
                                    parent.id,
                                )
                            }.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                IconButton(onClick = { repository.setDone(task.id, !task.done) }) {
                    Icon(
                        if (task.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (task.done) "Rouvrir la tâche" else "Terminer la tâche",
                        tint = colors.forPriority(task.priority),
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    textStyle =
                        MaterialTheme.typography.titleMedium.copy(
                            textDecoration = if (task.done) TextDecoration.LineThrough else null,
                        ),
                )
            }
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                minLines = 2,
            )

            val due = task.dueDate
            DetailRow(Icons.Filled.CalendarToday, due?.let { dueLabel(it, today) } ?: "Date d'échéance") {
                dialog =
                    Dialog.DATE
            }
            DetailRow(
                Icons.Filled.Alarm,
                if (task.reminders.isEmpty()) {
                    "Rappels"
                } else {
                    task.reminders.joinToString {
                        reminderLabel(
                            it,
                            due,
                            today,
                        )
                    }
                },
            ) { dialog = Dialog.REMINDERS }
            DetailRow(Icons.Filled.Repeat, repeatLabel(task) ?: "Répétition") { dialog = Dialog.REPEAT }
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
                    showProject = true
                }
                ProjectMenu(showProject, state.projects, onDismiss = { showProject = false }) {
                    repository.updateTask(task.copy(projectId = it.id))
                    showProject = false
                }
            }
            DetailRow(
                Icons.AutoMirrored.Filled.Label,
                if (task.labels.isEmpty()) "Étiquettes" else task.labels.joinToString { it.title },
            ) { dialog = Dialog.LABELS }
            DetailRow(
                Icons.Filled.Person,
                if (task.assignees.isEmpty()) "Assigner à" else task.assignees.joinToString { it.display },
            ) { dialog = Dialog.ASSIGNEES }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Sous-tâches")
            subtasks.forEach { sub ->
                TaskRow(
                    task = sub,
                    today = today,
                    projectName = null,
                    onToggle = { repository.setDone(sub.id, it) },
                    onClick = { onOpenTask(sub.id) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newSubtask,
                    onValueChange = { newSubtask = it },
                    placeholder = { Text("Ajouter une sous-tâche") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = newSubtask.isNotBlank(),
                    onClick = {
                        repository.addTask(newSubtask.trim(), task.projectId, parentId = task.id)
                        newSubtask = ""
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ajouter la sous-tâche") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Commentaires")
            commentNote?.let {
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
                        c.author + if (c.id < 0) " · en attente d'envoi" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    // Vikunja stocke les commentaires en HTML ; on affiche le texte sans les balises.
                    Text(stripHtml(c.text), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newComment,
                    onValueChange = { newComment = it },
                    placeholder = { Text("Ajouter un commentaire") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = newComment.isNotBlank(),
                    onClick = {
                        // Hors ligne aussi : le commentaire est mis en file et part au retour du serveur.
                        repository.addComment(task.id, newComment.trim())
                        newComment = ""
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer le commentaire") }
            }
        }
    }

    when (dialog) {
        Dialog.DATE ->
            DatePickerSheet(
                initial = task.dueDate?.localDate(),
                onPick = { date ->
                    dialog = null
                    // On garde l'heure existante ; une nouvelle date seule reprend « midi » (date sans heure).
                    val time = task.dueDate?.atZone(zone)?.toLocalTime() ?: QuickAdd.DATE_ONLY_TIME
                    repository.updateTask(task.copy(dueDate = date?.atTime(time)?.atZone(zone)?.toInstant()))
                },
                onDismiss = { dialog = null },
            )
        Dialog.LABELS ->
            LabelsDialog(
                all = state.labels,
                selected = task.labels,
                onConfirm = {
                    dialog = null
                    repository.updateTask(task.copy(labels = it), labelsChanged = true)
                },
                onDismiss = { dialog = null },
            )
        Dialog.REPEAT ->
            RepeatDialog(
                task = task,
                onConfirm = { after, mode ->
                    dialog = null
                    // Vikunja ne répète que les tâches qui ont une échéance.
                    val repeats = after > 0 || mode == com.lielu.todo.data.model.Task.REPEAT_MONTHLY
                    repository.updateTask(
                        task.copy(
                            repeatAfter = after,
                            repeatMode = mode,
                            dueDate = if (repeats) withDefaultDue() else task.dueDate,
                        ),
                    )
                },
                onDismiss = { dialog = null },
            )
        Dialog.REMINDERS ->
            RemindersDialog(
                task = task,
                onChange = { repository.updateTask(task.copy(reminders = it)) },
                onPickAbsolute = { dialog = Dialog.REMINDER_DATE },
                onDismiss = { dialog = null },
            )
        Dialog.REMINDER_DATE ->
            DatePickerSheet(
                initial = task.dueDate?.localDate() ?: today,
                onPick = { date ->
                    if (date == null) {
                        dialog = Dialog.REMINDERS
                    } else {
                        pendingReminderDate = date
                        dialog = Dialog.REMINDER_TIME
                    }
                },
                onDismiss = { dialog = Dialog.REMINDERS },
            )
        Dialog.REMINDER_TIME ->
            TimePickerDialog(
                initial = LocalTime.of(9, 0),
                onPick = { time ->
                    val date = pendingReminderDate ?: today
                    repository.updateTask(task.copy(reminders = task.reminders + absoluteReminder(date, time, zone)))
                    dialog = Dialog.REMINDERS
                },
                onDismiss = { dialog = Dialog.REMINDERS },
            )
        Dialog.ASSIGNEES -> {
            LaunchedEffect(task.projectId) {
                usersLoading = true
                try {
                    repository.refreshProjectUsers(task.projectId)
                } catch (e: IOException) {
                    // Hors ligne : on garde la liste en cache, éventuellement vide.
                }
                usersLoading = false
            }
            AssigneesDialog(
                users = state.projectUsers[task.projectId].orEmpty(),
                selected = task.assignees,
                loading = usersLoading,
                onConfirm = { chosen ->
                    dialog = null
                    val before = task.assignees.map { it.id }.toSet()
                    val after = chosen.map { it.id }.toSet()
                    chosen.filter { it.id !in before }.forEach { repository.assign(task.id, it, true) }
                    task.assignees.filter { it.id !in after }.forEach { repository.assign(task.id, it, false) }
                },
                onDismiss = { dialog = null },
            )
        }
        null -> Unit
    }
}

private enum class Dialog { DATE, LABELS, REPEAT, REMINDERS, REMINDER_DATE, REMINDER_TIME, ASSIGNEES }

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    text: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
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
