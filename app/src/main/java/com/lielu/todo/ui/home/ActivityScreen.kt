package com.lielu.todo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.data.todo.productivity
import com.lielu.todo.data.todo.recentActivity
import com.lielu.todo.ui.common.SectionHeader
import com.lielu.todo.ui.common.ago
import com.lielu.todo.ui.theme.LocalTodoColors
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Productivité (terminées aujourd'hui, sur 7 jours, série) et journal d'activité, calculés sur le cache local. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    repository: TodoRepository,
    onOpenTask: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val stats = remember(state.tasks) { productivity(state.tasks, LocalDate.now()) }
    val events = remember(state.tasks) { recentActivity(state.tasks) }
    val accent = LocalTodoColors.current.today
    val max = (stats.lastDays.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Productivité") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stats.today.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                    Text("tâche(s) terminée(s) aujourd'hui", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${stats.week} cette semaine · série de ${stats.streak} jour(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(96.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        stats.lastDays.forEach { (day, count) ->
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height((60 * count / max + 4).dp)
                                            .background(
                                                if (count > 0) accent else MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(4.dp),
                                            ),
                                )
                                Text(
                                    day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.FRENCH),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Activité récente") }
            items(events, key = { "${it.taskId}-${it.completed}-${it.at}" }) { e ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpenTask(
                                    e.taskId,
                                )
                            }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (e.completed) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline,
                        contentDescription = null,
                        tint = if (e.completed) accent else MaterialTheme.colorScheme.outline,
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(e.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            (if (e.completed) "Terminée " else "Ajoutée ") +
                                ago(System.currentTimeMillis() - e.at.toEpochMilli()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}
