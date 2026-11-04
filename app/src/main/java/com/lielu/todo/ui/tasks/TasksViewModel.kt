package com.lielu.todo.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lielu.todo.data.settings.SettingsRepository
import com.lielu.todo.data.vikunja.VikunjaApi
import com.lielu.todo.data.vikunja.VikunjaTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface TasksUiState {
    data object Loading : TasksUiState

    data class Success(
        val tasks: List<VikunjaTask>,
    ) : TasksUiState

    data class Error(
        val message: String,
    ) : TasksUiState
}

class TasksViewModel(
    private val settingsRepository: SettingsRepository,
    private val vikunjaApi: VikunjaApi,
) : ViewModel() {
    private val _state = MutableStateFlow<TasksUiState>(TasksUiState.Loading)
    val state: StateFlow<TasksUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = TasksUiState.Loading
            val connection = settingsRepository.vikunja.first()
            _state.value =
                if (!connection.isConfigured) {
                    TasksUiState.Error(
                        "Vikunja non configuré : renseignez adresse, e-mail et mot de passe dans Paramètres.",
                    )
                } else {
                    try {
                        TasksUiState.Success(vikunjaApi.listTasks(connection))
                    } catch (e: Exception) {
                        TasksUiState.Error("Échec du chargement des tâches : ${e.message}")
                    }
                }
        }
    }
}
