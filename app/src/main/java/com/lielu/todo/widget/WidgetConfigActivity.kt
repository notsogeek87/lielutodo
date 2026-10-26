package com.lielu.todo.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lielu.todo.LieluApplication
import com.lielu.todo.data.model.Project
import com.lielu.todo.ui.theme.ThemedContent

/**
 * Configuration d'un widget liste, ouverte à son ajout (et par appui long > « Reconfigurer ») : l'utilisateur choisit
 * la période des tâches affichées (jusqu'à « sans limite ») et le projet (ou tous).
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId =
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Si l'utilisateur quitte sans valider, Android n'ajoute pas le widget.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val container = (application as LieluApplication).container
        val store = WidgetConfigStore(this)
        setContent {
            ThemedContent(container.settingsRepository) {
                val state by container.todoRepository.state.collectAsStateWithLifecycle()
                var config by remember { mutableStateOf(store.get(widgetId)) }
                WidgetConfigScreen(
                    config = config,
                    projects = state.projects.filter { !it.isArchived }.sortedBy { it.title.lowercase() },
                    onChange = { config = it },
                    onSave = {
                        store.set(widgetId, config)
                        updateTodayWidget(this, AppWidgetManager.getInstance(this), widgetId)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                        finish()
                    },
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetConfigScreen(
    config: WidgetConfig,
    projects: List<Project>,
    onChange: (WidgetConfig) -> Unit,
    onSave: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Configurer le widget", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tâches affichées",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            WidgetPeriod.entries.forEach { period ->
                ChoiceRow(period.label, config.period == period) { onChange(config.copy(period = period)) }
            }
            Text("Projet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            ChoiceRow("Tous les projets", config.projectId == 0L) { onChange(config.copy(projectId = 0)) }
            projects.forEach { project ->
                ChoiceRow(
                    project.title,
                    config.projectId == project.id,
                ) { onChange(config.copy(projectId = project.id)) }
            }
            if (config.projectId != 0L) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Inclure les sous-projets", modifier = Modifier.weight(1f))
                    Switch(
                        checked = config.includeSubProjects,
                        onCheckedChange = { onChange(config.copy(includeSubProjects = it)) },
                    )
                }
            }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) { Text("Valider") }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
