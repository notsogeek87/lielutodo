package com.lielu.todo.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.todo.TodoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Termine (ou rouvre) une tâche ; à la fin d'une tâche, propose « Annuler » comme Todoist. */
fun CoroutineScope.toggleDone(
    repository: TodoRepository,
    snackbar: SnackbarHostState,
    task: Task,
    done: Boolean,
) {
    repository.setDone(task.id, done)
    if (!done) return
    launch {
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar("Tâche terminée", "Annuler", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) repository.setDone(task.id, false)
    }
}
