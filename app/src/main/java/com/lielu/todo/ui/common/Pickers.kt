package com.lielu.todo.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lielu.todo.data.model.Label
import com.lielu.todo.data.model.Project
import com.lielu.todo.ui.home.orderedProjects
import com.lielu.todo.ui.theme.LocalTodoColors
import com.lielu.todo.ui.theme.forPriority
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Priorités à la Todoist : p1 la plus haute. Valeurs = priorités Vikunja. */
val PRIORITY_CHOICES = listOf(4 to "Priorité 1", 3 to "Priorité 2", 2 to "Priorité 3", 0 to "Priorité 4")

fun priorityName(priority: Int): String =
    when {
        priority >= 4 -> "Priorité 1"
        priority == 3 -> "Priorité 2"
        priority == 2 -> "Priorité 3"
        else -> "Priorité 4"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerSheet(
    initial: LocalDate?,
    onPick: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Le sélecteur travaille en millisecondes UTC à minuit : on convertit sans passer par le fuseau local.
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    onPick(millis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() })
                },
            ) { Text("OK") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text("Aucune date") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    ) {
        DatePicker(state = state)
    }
}

@Composable
fun PriorityMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val colors = LocalTodoColors.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        PRIORITY_CHOICES.forEach { (value, name) ->
            DropdownMenuItem(
                text = { Text(name) },
                leadingIcon = { Icon(Icons.Filled.Flag, contentDescription = null, tint = colors.forPriority(value)) },
                onClick = { onPick(value) },
            )
        }
    }
}

/** Choix du projet dans une boîte de dialogue (comme les autres champs), sous-projets indentés. */
@Composable
fun ProjectMenu(
    expanded: Boolean,
    projects: List<Project>,
    onDismiss: () -> Unit,
    onPick: (Project) -> Unit,
) {
    if (!expanded) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Projet") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                orderedProjects(projects).forEach { (project, depth) ->
                    Text(
                        project.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(project) }
                                .padding(start = (depth * 16).dp, top = 12.dp, bottom = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun LabelsDialog(
    all: List<Label>,
    selected: List<Label>,
    onConfirm: (List<Label>) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(selected.map { it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Étiquettes") },
        text = {
            if (all.isEmpty()) {
                Text("Aucune étiquette. Créez-en depuis l'onglet Parcourir.")
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    all.forEach { label ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        chosen = if (label.id in chosen) chosen - label.id else chosen + label.id
                                    }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = label.id in chosen, onCheckedChange = null)
                            Text(label.title, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(all.filter { it.id in chosen }) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Champ sans cadre ni fond, comme les champs de saisie de Todoist (nom de tâche, description). */
@Composable
fun borderlessFieldColors(): TextFieldColors =
    TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
    )
