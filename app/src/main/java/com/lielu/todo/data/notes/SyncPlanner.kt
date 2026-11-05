package com.lielu.todo.data.notes

import com.lielu.todo.data.joplin.RemoteNote

/** Ce qu'une synchronisation doit faire, calculé sans aucun effet de bord (testé unitairement). */
data class SyncPlan(
    /** Notes locales modifiées à pousser vers le serveur. */
    val toPush: List<Note>,
    /** Ids de notes à supprimer côté serveur (supprimées localement). */
    val toDeleteRemote: List<String>,
    /** Notes reçues du delta à écrire en local (nouvelles ou plus récentes que la copie locale). */
    val toApplyRemote: List<RemoteNote>,
    /** Ids de notes locales à supprimer (supprimées côté serveur). */
    val toDeleteLocal: List<String>,
)

/**
 * Découpe une synchronisation en actions. Règle de conflit : dernier modifié gagne — une note locale
 * modifiée ([Note.dirty]) est toujours poussée (elle écrase la version distante), une note reçue plus
 * récente que la copie locale propre est appliquée, une note supprimée d'un côté est supprimée de l'autre.
 */
object SyncPlanner {
    fun plan(
        local: List<Note>,
        pulled: List<RemoteNote>,
        remoteDeleted: Set<String>,
    ): SyncPlan {
        val localById = local.associateBy { it.id }
        val toPush = local.filter { it.dirty && !it.deleted }
        val toDeleteRemote = local.filter { it.deleted }.map { it.id }
        val toApplyRemote =
            pulled.filter { remote ->
                val localNote = localById[remote.id]
                localNote == null || (!localNote.dirty && remote.userUpdatedTime > localNote.userUpdatedTime)
            }
        val toDeleteLocal = local.filter { it.id in remoteDeleted && !it.dirty }.map { it.id }
        return SyncPlan(toPush, toDeleteRemote, toApplyRemote, toDeleteLocal)
    }
}
