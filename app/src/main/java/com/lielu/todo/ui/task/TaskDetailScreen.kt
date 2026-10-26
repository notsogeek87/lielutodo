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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.lielu.todo.data.model.Reminder
import com.lielu.todo.data.todo.QuickAdd
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.ui.common.AssigneesDialog
import com.lielu.todo.ui.common.DatePickerSheet
import com.lielu.todo.ui.common.LabelsDialog
import com.lielu.todo.ui.common.PriorityMenu
import com.lielu.todo.ui.common.ProjectMenu
import com.lielu.todo.ui.common.RemindersDialog
import com.lielu.todo.ui.common.RepeatDialog
import com.lielu.todo.ui.common.TaskCheckbox
import com.lielu.todo.ui.common.TaskRow
import com.lielu.todo.ui.common.TimePickerDialog
import com.lielu.todo.ui.common.absoluteReminder
import com.lielu.todo.ui.common.borderlessFieldColors
import com.lielu.todo.ui.common.dueLabel
import com.lielu.todo.ui.common.localDate
import com.lielu.todo.ui.common.priorityName
import com.lielu.todo.ui.common.reminderLabel
import com.lielu.todo.ui.common.repeatLabel
import com.lielu.todo.ui.theme.LocalTodoColors
import com.lielu.todo.ui.theme.forPriority
import kotlinx.coroutines.launch
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
    var pendingDueDate by remember { mutableStateOf<LocalDate?>(null) }
    var showPriority by remember { mutableStateOf(false) }
    var showProject by remember { mutableStateOf(false) }
    var commentNote by remember { mutableStateOf<String?>(null) }
    var newComment by remember { mutableStateOf("") }
    var editingComment by remember { mutableStateOf<com.lielu.todo.data.model.Comment?>(null) }
    var editText by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val detailScope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val attachments = state.attachments[task.id].orEmpty()

    fun attach(uri: android.net.Uri?) {
        if (uri == null) return
        val picked = readPickedFile(context, uri)
        if (picked == null) {
            detailScope.launch { snackbar.showSnackbar("Impossible de lire ce fichier, ou il dépasse 20 Mo.") }
        } else {
            repository.addAttachment(task.id, picked.name, picked.mime, picked.bytes)
        }
    }

    val pickFile =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .GetContent(),
        ) { attach(it) }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .TakePicture(),
        ) { saved -> if (saved) attach(cameraUri) }
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
            repository.refreshAttachments(task.id)
        } catch (e: IOException) {
            // Hors ligne : la liste en cache reste affichée.
        }
    }
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
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
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
                    IconButton(onClick = { repository.updateTask(task.copy(isFavorite = !task.isFavorite)) }) {
                        Icon(
                            if (task.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (task.isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                        )
                    }
                    IconButton(onClick = {
                        repository.duplicateTask(task.id)
                        onBack()
                    }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Dupliquer la tâche") }
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
            SectionTitle("Pièces jointes")
            attachments.forEach { a ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                detailScope.launch {
                                    try {
                                        openFile(context, repository.openAttachment(task.id, a), a.mime)
                                    } catch (e: IOException) {
                                        snackbar.showSnackbar(
                                            "Fichier indisponible hors ligne : ouvrez-le une fois en ligne.",
                                        )
                                    } catch (e: android.content.ActivityNotFoundException) {
                                        snackbar.showSnackbar("Aucune application pour ouvrir ce type de fichier.")
                                    }
                                }
                            }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(a.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            humanSize(a.size) + if (a.id < 0) " · en attente d'envoi" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    IconButton(onClick = { repository.deleteAttachment(task.id, a) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Supprimer la pièce jointe")
                    }
                }
            }
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                androidx.compose.material3.TextButton(
                    onClick = { pickFile.launch("*/*") },
                ) { Text("Ajouter un fichier") }
                androidx.compose.material3.TextButton(onClick = {
                    val uri = newCameraUri(context)
                    cameraUri = uri
                    takePhoto.launch(uri)
                }) { Text("Prendre une photo") }
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
                    Row {
                        androidx.compose.material3.TextButton(onClick = {
                            editText = stripHtml(c.text)
                            editingComment = c
                        }) { Text("Modifier") }
                        androidx.compose.material3.TextButton(onClick = { repository.deleteComment(task.id, c) }) {
                            Text("Supprimer")
                        }
                    }
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

    editingComment?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editingComment = null },
            title = { Text("Modifier le commentaire") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = editText.isNotBlank(),
                    onClick = {
                        repository.editComment(task.id, c, editText.trim())
                        editingComment = null
                    },
                ) { Text("Enregistrer") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { editingComment = null }) { Text("Annuler") }
            },
        )
    }

    when (dialog) {
        Dialog.DATE ->
            DatePickerSheet(
                initial = task.dueDate?.localDate(),
                onPick = { date ->
                    if (date == null) {
                        dialog = null
                        repository.updateTask(task.copy(dueDate = null))
                    } else {
                        // Une seule date à modifier : le jour, puis l'heure juste après.
                        pendingDueDate = date
                        dialog = Dialog.DUE_TIME
                    }
                },
                onDismiss = { dialog = null },
            )
        Dialog.DUE_TIME -> {
            val current = task.dueDate?.atZone(zone)?.toLocalTime()

            fun saveDue(
                time: LocalTime,
                withTime: Boolean,
            ) {
                val date = pendingDueDate ?: today
                dialog = null
                val due = date.atTime(time).atZone(zone).toInstant()
                val atDue = { r: Reminder -> r.relativeTo == Reminder.RELATIVE_TO_DUE && r.relativePeriod == 0L }
                // Avec une heure, la notification tombe à l'échéance sans autre geste ; sans heure, on la retire.
                val reminders =
                    when {
                        !withTime -> task.reminders.filterNot(atDue)
                        task.reminders.isEmpty() -> listOf(Reminder.beforeDue(0))
                        else -> task.reminders
                    }
                repository.updateTask(task.copy(dueDate = due, reminders = reminders))
            }
            TimePickerDialog(
                initial = current?.takeIf { it != QuickAdd.DATE_ONLY_TIME } ?: LocalTime.of(9, 0),
                onPick = { saveDue(it, withTime = true) },
                onDismiss = { dialog = null },
                onNoTime = { saveDue(QuickAdd.DATE_ONLY_TIME, withTime = false) },
            )
        }
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

private enum class Dialog { DATE, DUE_TIME, LABELS, REPEAT, REMINDERS, REMINDER_DATE, REMINDER_TIME, ASSIGNEES }

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

private class PickedFile(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
)

/** Vikunja refuse au-delà de 20 Mo par fichier : on le dit tout de suite plutôt qu'après un envoi raté. */
private const val MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024

private fun readPickedFile(
    context: android.content.Context,
    uri: android.net.Uri,
): PickedFile? =
    runCatching {
        val resolver = context.contentResolver
        val name =
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: "fichier"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > MAX_ATTACHMENT_BYTES) return null
        PickedFile(name, resolver.getType(uri) ?: "application/octet-stream", bytes)
    }.getOrNull()

private fun newCameraUri(context: android.content.Context): android.net.Uri {
    val dir = java.io.File(context.cacheDir, "camera").also { it.mkdirs() }
    val file = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider
        .getUriForFile(context, "${context.packageName}.files", file)
}

private fun openFile(
    context: android.content.Context,
    file: java.io.File,
    mime: String,
) {
    val uri =
        androidx.core.content.FileProvider
            .getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(
        android.content
            .Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, mime.ifBlank { "*/*" })
            .addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
    )
}

private fun humanSize(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 -> "%.1f Mo".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} Ko"
        else -> "$bytes o"
    }
