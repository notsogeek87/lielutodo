package com.lielu.todo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lielu.todo.reminder.ReminderReceiver
import com.lielu.todo.ui.navigation.AppNavHost
import com.lielu.todo.ui.theme.ThemedContent
import com.lielu.todo.ui.update.AppUpdateViewModel
import com.lielu.todo.ui.update.UpdatePrompt

class MainActivity : ComponentActivity() {
    /** Tâche à ouvrir (clic sur une notification de rappel ou sur une ligne du widget), consommée par la navigation. */
    private var openTaskId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openTaskId = taskIdFrom(intent)

        val container = (application as LieluApplication).container

        setContent {
            ThemedContent(container.settingsRepository) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavHost(container = container, openTaskId = openTaskId, onTaskOpened = { openTaskId = null })
                    val updateViewModel: AppUpdateViewModel =
                        viewModel(
                            factory =
                                viewModelFactory {
                                    initializer {
                                        AppUpdateViewModel(container.updateManager, container.updatesEnabled)
                                    }
                                },
                        )
                    UpdatePrompt(updateViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTaskId = taskIdFrom(intent)
    }

    // Les modifications récentes ne doivent pas attendre la temporisation d'écriture si l'app est tuée en fond.
    override fun onStop() {
        super.onStop()
        (application as LieluApplication).container.todoRepository.saveNow()
    }

    private fun taskIdFrom(intent: Intent?): Long? =
        intent?.getLongExtra(ReminderReceiver.EXTRA_TASK_ID, 0)?.takeIf { it != 0L }
}
