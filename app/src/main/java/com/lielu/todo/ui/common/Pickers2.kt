package com.lielu.todo.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lielu.todo.data.model.Reminder
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.model.User
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val DAY = 86_400L
private const val WEEK = 7 * DAY

@Composable
private fun ChoiceRow(
    selected: Boolean,
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text,
            modifier = Modifier.padding(start = 12.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
    }
}

/** Répétition : aucune, quotidienne, hebdomadaire, mensuelle ou « tous les N jours/semaines ». */
@Composable
fun RepeatDialog(
    task: Task,
    onConfirm: (repeatAfter: Long, repeatMode: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val monthly = task.repeatMode == Task.REPEAT_MONTHLY
    val initialUnit = if (task.repeatAfter > 0 && task.repeatAfter % WEEK == 0L) WEEK else DAY
    var choice by remember {
        mutableStateOf(
            when {
                monthly -> "month"
                task.repeatAfter == 0L -> "none"
                else -> "custom"
            },
        )
    }
    var count by remember {
        mutableStateOf(
            if (task.repeatAfter >
                0
            ) {
                (task.repeatAfter / initialUnit).toString()
            } else {
                "1"
            },
        )
    }
    var unit by remember { mutableStateOf(initialUnit) }
    var fromCompletion by remember { mutableStateOf(task.repeatMode == Task.REPEAT_FROM_COMPLETION) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Répétition") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ChoiceRow(choice == "none", "Aucune") { choice = "none" }
                ChoiceRow(choice == "month", "Tous les mois") { choice = "month" }
                ChoiceRow(choice == "custom", "Tous les…") { choice = "custom" }
                if (choice == "custom") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 40.dp)) {
                        OutlinedTextField(
                            value = count,
                            onValueChange = { count = it.filter(Char::isDigit).take(3) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { unit = if (unit == DAY) WEEK else DAY }) {
                            Text(if (unit == DAY) "jour(s)" else "semaine(s)")
                        }
                    }
                }
                if (choice == "custom") {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().clickable { fromCompletion = !fromCompletion }.padding(
                                top = 8.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = fromCompletion, onCheckedChange = null)
                        Text("À partir de la date de complétion", modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (choice) {
                        "none" -> onConfirm(0, 0)
                        "month" -> onConfirm(0, Task.REPEAT_MONTHLY)
                        else ->
                            onConfirm(
                                (count.toLongOrNull() ?: 1L).coerceAtLeast(1) * unit,
                                if (fromCompletion) Task.REPEAT_FROM_COMPLETION else 0,
                            )
                    }
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Rappels d'une tâche : liste supprimable + ajout relatif à l'échéance ou à une date et heure choisies. */
@Composable
fun RemindersDialog(
    task: Task,
    onChange: (List<Reminder>) -> Unit,
    onPickAbsolute: () -> Unit,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val hasDue = task.dueDate != null
    val presets =
        listOf(
            "À l'échéance" to 0L,
            "10 min avant" to 600L,
            "30 min avant" to 1_800L,
            "1 h avant" to 3_600L,
            "1 jour avant" to DAY,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rappels") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (task.reminders.isEmpty()) {
                    Text("Aucun rappel.", color = MaterialTheme.colorScheme.outline)
                }
                task.reminders.forEachIndexed { index, r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(reminderLabel(r, task.dueDate, today), modifier = Modifier.weight(1f))
                        IconButton(onClick = { onChange(task.reminders.filterIndexed { i, _ -> i != index }) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Supprimer ce rappel")
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                TextButton(onClick = onPickAbsolute) { Text("Date et heure précises…") }
                if (!hasDue) {
                    Text("Ajoutez une échéance pour des rappels relatifs.", style = MaterialTheme.typography.bodySmall)
                }
                presets.forEach { (label, seconds) ->
                    TextButton(
                        enabled = hasDue,
                        onClick = { onChange(task.reminders + Reminder.beforeDue(seconds)) },
                    ) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initial: LocalTime,
    onPick: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
    onNoTime: (() -> Unit)? = null,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = {
            Row {
                if (onNoTime != null) TextButton(onClick = onNoTime) { Text("Sans heure") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}

fun absoluteReminder(
    date: LocalDate,
    time: LocalTime,
    zone: ZoneId = ZoneId.systemDefault(),
): Reminder = Reminder(date.atTime(time).atZone(zone).toInstant())

/** Assignation : coche les utilisateurs du projet. */
@Composable
fun AssigneesDialog(
    users: List<User>,
    selected: List<User>,
    loading: Boolean,
    onConfirm: (List<User>) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(selected.map { it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assigner à") },
        text = {
            when {
                users.isEmpty() && loading -> Text("Chargement…")
                users.isEmpty() ->
                    Text(
                        "Aucun utilisateur connu pour ce projet (serveur injoignable, ou projet non partagé).",
                    )
                else ->
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        users.forEach { user ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            chosen =
                                                if (user.id in chosen) chosen - user.id else chosen + user.id
                                        }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = user.id in chosen, onCheckedChange = null)
                                Text(user.display, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    (users + selected).distinctBy { it.id }.filter {
                        it.id in
                            chosen
                    },
                )
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
