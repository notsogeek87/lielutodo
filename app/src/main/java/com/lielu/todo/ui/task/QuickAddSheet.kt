package com.lielu.todo.ui.task

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import com.lielu.todo.data.model.Label
import com.lielu.todo.data.todo.QuickAddParser
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.data.todo.TodoState
import com.lielu.todo.ui.common.DatePickerSheet
import com.lielu.todo.ui.common.LabelsDialog
import com.lielu.todo.ui.common.PriorityMenu
import com.lielu.todo.ui.common.ProjectMenu
import com.lielu.todo.ui.common.dayLabel
import com.lielu.todo.ui.common.priorityName
import com.lielu.todo.ui.theme.LocalTodoColors
import com.lielu.todo.ui.theme.forPriority
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Ajout rapide : le texte est analysé en direct (date, heure, #projet, @étiquette, p1 à p4) et chaque repère
 * reconnu s'affiche en pastille ; un choix manuel sur une pastille l'emporte sur ce que le texte dit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    state: TodoState,
    defaultProjectId: Long,
    defaultDate: LocalDate?,
    onAdd: (NewTask) -> Unit,
    onDismiss: () -> Unit,
    initialText: String = "",
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf(initialText) }
    // Enveloppe : « Aucune date » (null choisi explicitement) doit l'emporter sur la date lue dans le texte.
    var manualDate by remember { mutableStateOf<DateChoice?>(null) }
    var manualPriority by remember { mutableStateOf<Int?>(null) }
    var manualProject by remember { mutableStateOf<Long?>(null) }
    var manualLabels by remember { mutableStateOf<List<Label>?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var showPriority by remember { mutableStateOf(false) }
    var showProject by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(false) }

    val parsed =
        remember(text, state.projects, state.labels) { QuickAddParser.parse(text, today, state.projects, state.labels) }
    val date = manualDate.let { if (it != null) it.date else parsed.date ?: defaultDate }
    val projectId = manualProject ?: parsed.projectId ?: defaultProjectId.takeIf { it != 0L } ?: state.inboxId
    val priority = manualPriority ?: parsed.priority ?: 0
    val labels = manualLabels ?: parsed.labels
    val project = state.projects.firstOrNull { it.id == projectId }
    val colors = LocalTodoColors.current

    fun submit() {
        val title = parsed.title.trim()
        if (title.isEmpty()) return
        val due =
            if (manualDate != null &&
                date == null
            ) {
                null
            } else {
                parsed.copy(date = date).dueInstant(LocalDateTime.now(zone), zone)
            }
        onAdd(NewTask(title, projectId, due, priority, labels, parsed.repeatAfter, parsed.repeatMode))
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Ex. : Appeler Marie demain 9h #Perso p1") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    AssistChip(
                        onClick = { showDate = true },
                        label = { Text(date?.let { dayLabel(it, today) } ?: "Date") },
                        leadingIcon = { Icon(Icons.Filled.CalendarToday, contentDescription = null) },
                    )
                }
                Box {
                    AssistChip(
                        onClick = { showPriority = true },
                        label = { Text(if (priority == 0) "Priorité" else priorityName(priority)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Flag, contentDescription = null, tint = colors.forPriority(priority))
                        },
                    )
                    PriorityMenu(showPriority, onDismiss = { showPriority = false }) {
                        manualPriority = it
                        showPriority = false
                    }
                }
                Box {
                    AssistChip(
                        onClick = { showProject = true },
                        label = { Text(project?.title ?: "Projet") },
                        leadingIcon = { Icon(Icons.Filled.Inbox, contentDescription = null) },
                    )
                    ProjectMenu(showProject, state.projects, onDismiss = { showProject = false }) {
                        manualProject = it.id
                        showProject = false
                    }
                }
                if (parsed.repeatAfter > 0 || parsed.repeatMode != 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Répétée") },
                        leadingIcon = { Icon(Icons.Filled.Repeat, contentDescription = null) },
                    )
                }
                AssistChip(
                    onClick = { showLabels = true },
                    label = { Text(if (labels.isEmpty()) "Étiquettes" else labels.joinToString { it.title }) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = ::submit, enabled = parsed.title.isNotBlank()) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Ajouter la tâche",
                        tint =
                            if (parsed.title.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                    )
                }
            }
        }
    }

    if (showDate) {
        DatePickerSheet(
            initial = date,
            onPick = {
                manualDate = DateChoice(it)
                showDate = false
            },
            onDismiss = { showDate = false },
        )
    }
    if (showLabels) {
        LabelsDialog(
            all = state.labels,
            selected = labels,
            onConfirm = {
                manualLabels = it
                showLabels = false
            },
            onDismiss = { showLabels = false },
        )
    }
}

/** Tâche à créer, telle que résolue par l'ajout rapide (texte analysé + choix manuels). */
data class NewTask(
    val title: String,
    val projectId: Long,
    val due: Instant?,
    val priority: Int,
    val labels: List<Label>,
    val repeatAfter: Long = 0,
    val repeatMode: Int = 0,
)

fun TodoRepository.add(task: NewTask): Long =
    addTask(
        title = task.title,
        projectId = task.projectId,
        due = task.due,
        priority = task.priority,
        labels = task.labels,
        repeatAfter = task.repeatAfter,
        repeatMode = task.repeatMode,
    )

private data class DateChoice(
    val date: LocalDate?,
)
