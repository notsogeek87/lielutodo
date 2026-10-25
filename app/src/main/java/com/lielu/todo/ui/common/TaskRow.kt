package com.lielu.todo.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
) {
    val colors = LocalTodoColors.current
    val due = task.dueDate
    val overdue = due != null && !task.done && due.localDate().isBefore(today)
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 4.dp, end = 16.dp),
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
                color = if (task.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            val details =
                buildList {
                    if (due != null) add(dueLabel(due, today) to if (overdue) colors.overdue else colors.today)
                    task.labels.forEach { add("@${it.title}" to MaterialTheme.colorScheme.outline) }
                }
            if (details.isNotEmpty() || projectName != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    details.forEach { (text, color) ->
                        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
                    }
                    if (projectName != null) {
                        Text(
                            text = "#$projectName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}
