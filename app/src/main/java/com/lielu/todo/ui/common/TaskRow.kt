package com.lielu.todo.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lielu.todo.data.model.Task
import com.lielu.todo.ui.theme.LocalTodoColors
import com.lielu.todo.ui.theme.TodoColors
import com.lielu.todo.ui.theme.forPriority
import java.time.LocalDate

/** Couleur de l'échéance, comme Todoist : rouge en retard, vert aujourd'hui, orange demain, violet cette semaine. */
internal fun TodoColors.forDue(
    date: LocalDate,
    today: LocalDate,
    neutral: Color,
): Color {
    val days = date.toEpochDay() - today.toEpochDay()
    return when {
        days < 0 -> overdue
        days == 0L -> this.today
        days == 1L -> tomorrow
        days in 2..6 -> upcoming
        else -> neutral
    }
}

/** Case ronde de Todoist : anneau de la couleur de priorité, fond légèrement teinté, coche une fois terminée. */
@Composable
fun TaskCheckbox(
    done: Boolean,
    priority: Int,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = LocalTodoColors.current.forPriority(priority)
    val description = if (done) "Rouvrir la tâche" else "Terminer la tâche"
    Box(
        modifier =
            modifier
                .size(48.dp)
                .semantics { contentDescription = description }
                .clickable(role = Role.Checkbox) { onToggle(!done) },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(22.dp),
            shape = CircleShape,
            color = if (done) color else color.copy(alpha = 0.12f),
            border = BorderStroke(2.dp, color),
        ) {
            if (done) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.background,
                    modifier = Modifier.padding(2.dp),
                )
            }
        }
    }
}

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
    val outline = MaterialTheme.colorScheme.outline
    val due = task.dueDate
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            TaskCheckbox(task.done, task.priority, onToggle)
            Column(modifier = Modifier.weight(1f).padding(top = 12.dp, bottom = 10.dp)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) outline else MaterialTheme.colorScheme.onSurface,
                )
                if (due != null || task.labels.isNotEmpty() || projectName != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        if (due != null) {
                            val tint = if (task.done) outline else colors.forDue(due.localDate(), today, outline)
                            MetaItem(Icons.Outlined.CalendarToday, dueLabel(due, today), tint)
                        }
                        if (task.labels.isNotEmpty()) {
                            MetaItem(Icons.AutoMirrored.Outlined.Label, task.labels.joinToString { it.title }, outline)
                        }
                        if (projectName != null) {
                            Text(
                                text = "$projectName #",
                                style = MaterialTheme.typography.bodySmall,
                                color = outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun MetaItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
