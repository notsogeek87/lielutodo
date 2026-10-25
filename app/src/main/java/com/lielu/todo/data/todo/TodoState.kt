package com.lielu.todo.data.todo

import com.lielu.todo.data.model.Label
import com.lielu.todo.data.model.Project
import com.lielu.todo.data.model.Task

/**
 * Écriture locale pas encore envoyée au serveur. L'état de la tâche concernée n'est pas copié ici : au moment de
 * l'envoi on relit la version la plus récente, ce qui fusionne naturellement plusieurs modifications successives.
 */
data class PendingOp(
    val kind: Kind,
    val taskId: Long,
    val labelsChanged: Boolean = false,
) {
    enum class Kind { CREATE, UPDATE, DELETE }
}

data class TodoState(
    val projects: List<Project> = emptyList(),
    val labels: List<Label> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val defaultProjectId: Long = 0,
    val ops: List<PendingOp> = emptyList(),
    val syncing: Boolean = false,
    val error: String? = null,
    /** `false` tant que ni le cache ni le serveur n'ont répondu : évite d'afficher « rien à faire » à tort. */
    val loaded: Boolean = false,
) {
    val pending: Int get() = ops.size
    val inboxId: Long get() =
        defaultProjectId.takeIf { id -> projects.any { it.id == id } }
            ?: projects.firstOrNull()?.id
            ?: 0
}
