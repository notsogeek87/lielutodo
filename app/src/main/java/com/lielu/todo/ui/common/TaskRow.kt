package com.lielu.todo.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lielu.todo.data.model.Task
import com.lielu.todo.ui.theme.LocalTodoColors
import com.lielu.todo.ui.theme.forPriority
import java.time.LocalDate

@Composable
fun TaskRow(
    task: Task,
    today: LocalDate,
    projectName: String?,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Niveau d'imbrication (sous-tâches). */
    indent: Int = 0,
    /** « 1/3 » : sous-tâches terminées sur total. */
    subtasks: String? = null,
) {
    val colors = LocalTodoColors.current
    val due = task.dueDate
    val overdue = due != null && !task.done && due.localDate().isBefore(today)
    val muted = MaterialTheme.colorScheme.outline
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(
                    onClick = onClick,
                ).padding(start = 4.dp + 28.dp * indent, end = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconButton(onClick = { onToggle(!task.done) }) {
            Icon(
                imageVector = if (task.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (task.done) "Rouvrir la tâche" else "Terminer la tâche",
                tint = colors.forPriority(task.priority),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                color = if (task.done) muted else MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                if (due != null) {
                    Text(
                        dueLabel(due, today),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) colors.overdue else colors.today,
                    )
                }
                if (task.isRecurring) SmallIcon(Icons.Filled.Repeat, "Tâche répétée")
                if (task.reminders.isNotEmpty()) SmallIcon(Icons.Filled.Alarm, "Rappel")
                if (subtasks != null) Text(subtasks, style = MaterialTheme.typography.bodySmall, color = muted)
                if (task.assignees.isNotEmpty()) {
                    SmallIcon(Icons.Filled.Person, "Assignée")
                    Text(
                        task.assignees.joinToString { it.display },
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
                task.labels.forEach { Text("@${it.title}", style = MaterialTheme.typography.bodySmall, color = muted) }
                if (projectName !=
                    null
                ) {
                    Text("#$projectName", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
    }
}

@Composable
private fun SmallIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
) {
    Icon(
        icon,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(14.dp),
    )
}
