package com.lielu.todo.data.todo

import com.lielu.todo.data.model.AppNotification
import com.lielu.todo.data.model.Attachment
import com.lielu.todo.data.model.Comment
import com.lielu.todo.data.model.Label
import com.lielu.todo.data.model.Project
import com.lielu.todo.data.model.SavedFilter
import com.lielu.todo.data.model.Task
import com.lielu.todo.data.model.User

/**
 * Écriture locale pas encore envoyée au serveur. L'état de la tâche concernée n'est pas copié ici : au moment de
 * l'envoi on relit la version la plus récente, ce qui fusionne naturellement plusieurs modifications successives.
 *
 * [taskId] vise l'objet concerné (tâche, ou projet/étiquette temporaire pour les créations) ; [otherId] est un
 * second objet (tâche parente, utilisateur) ; [text] un titre ou un commentaire ; [flag] a un sens par type.
 */
data class PendingOp(
    val kind: Kind,
    val taskId: Long,
    val otherId: Long = 0,
    val text: String = "",
    val flag: Boolean = false,
    /** Second texte : couleur d'une étiquette, nom et type d'un fichier, projet:vue d'un ancien déplacement Kanban. */
    val extra: String = "",
) {
    enum class Kind {
        CREATE,
        UPDATE,
        DELETE,
        COMMENT,
        CREATE_PROJECT,
        CREATE_LABEL,
        RELATE,
        ASSIGN,
        POSITION,
        ATTACH,
        DELETE_ATTACHMENT,
        COMMENT_EDIT,
        COMMENT_DELETE,
        LABEL_UPDATE,
        LABEL_DELETE,
        MOVE_BUCKET,
        MARK_READ,
        MARK_ALL_READ,
    }
}

data class TodoState(
    val projects: List<Project> = emptyList(),
    val labels: List<Label> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val filters: List<SavedFilter> = emptyList(),
    /** Dernier résultat connu de chaque filtre enregistré : consultable même serveur coupé. */
    val filterResults: Map<Long, List<Task>> = emptyMap(),
    val comments: Map<Long, List<Comment>> = emptyMap(),
    val attachments: Map<Long, List<Attachment>> = emptyMap(),
    val notifications: List<AppNotification> = emptyList(),
    val projectUsers: Map<Long, List<User>> = emptyMap(),
    val defaultProjectId: Long = 0,
    val ops: List<PendingOp> = emptyList(),
    val syncing: Boolean = false,
    val error: String? = null,
    /** `false` tant que ni le cache ni le serveur n'ont répondu : évite d'afficher « rien à faire » à tort. */
    val loaded: Boolean = false,
    /** Dernière synchronisation réussie (ms depuis 1970), 0 si jamais. */
    val lastSync: Long = 0,
    /** Le dernier essai de synchronisation a échoué faute de réseau ou de serveur. */
    val offline: Boolean = false,
) {
    val pending: Int get() = ops.size
    val unreadNotifications: Int get() = notifications.count { !it.read }
    val inboxId: Long get() =
        defaultProjectId.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id ?: 0
}
