package com.lielu.todo.data.todo

/** Ce que l'écriture voulait faire, en clair, pour la liste des modifications refusées par le serveur. */
fun describeWrite(
    op: PendingOp,
    state: TodoState,
): String {
    val task = state.tasks.firstOrNull { it.id == op.taskId }?.let { "« ${it.title} »" } ?: "une tâche"
    return when (op.kind) {
        PendingOp.Kind.CREATE -> "Nouvelle tâche $task"
        PendingOp.Kind.UPDATE -> "Modification de $task"
        PendingOp.Kind.DELETE -> "Suppression d'une tâche"
        PendingOp.Kind.COMMENT -> "Commentaire sur $task : « ${op.text} »"
        PendingOp.Kind.CREATE_PROJECT -> "Nouveau projet « ${op.text} »"
        PendingOp.Kind.CREATE_LABEL -> "Nouvelle étiquette « ${op.text} »"
        PendingOp.Kind.RELATE -> "Sous-tâche $task"
        PendingOp.Kind.ASSIGN -> if (op.flag) "Affectation de $task" else "Fin d'affectation de $task"
        PendingOp.Kind.POSITION -> "Ordre de $task"
        PendingOp.Kind.ATTACH -> "Fichier « ${op.extra.substringBefore('\n')} » sur $task"
        PendingOp.Kind.DELETE_ATTACHMENT -> "Suppression d'un fichier de $task"
        PendingOp.Kind.COMMENT_EDIT -> "Commentaire modifié sur $task : « ${op.text} »"
        PendingOp.Kind.COMMENT_DELETE -> "Commentaire supprimé sur $task"
        PendingOp.Kind.LABEL_UPDATE -> "Étiquette renommée en « ${op.text} »"
        PendingOp.Kind.LABEL_DELETE -> "Suppression d'une étiquette"
        PendingOp.Kind.MOVE_BUCKET -> "Déplacement de $task"
        PendingOp.Kind.MARK_READ, PendingOp.Kind.MARK_ALL_READ -> "Notification marquée comme lue"
    }
}
