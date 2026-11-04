package com.lielu.todo.data.notes

import com.lielu.todo.data.joplin.RemoteNote

/** Ce qu'une synchronisation doit faire, calculé sans aucun effet de bord (testé unitairement). */
data class SyncPlan(
    /** Notes locales modifiées à pousser vers le serveur. */
    val toPush: List<Note>,
    /** Ids de notes à supprimer côté serveur (supprimées localement). */
    val toDeleteRemote: List<String>,
    /** Notes distantes à écrire en local (nouvelles ou plus récentes que la copie locale). */
    val toApplyRemote: List<RemoteNote>,
    /** Ids de notes locales à supprimer (absentes du serveur : supprimées ailleurs). */
    val toDeleteLocal: List<String>,
)

/**
 * Découpe une synchronisation en actions. Règle de conflit : dernier modifié gagne — une note locale
 * modifiée ([Note.dirty]) est toujours poussée (elle écrase la version distante), une note distante plus
 * récente que la copie locale est téléchargée, une note absente d'un côté est supprimée de l'autre.
 */
object SyncPlanner {
    fun plan(local: List<Note>, remote: List<RemoteNote>): SyncPlan {
        val remoteById = remote.associateBy { it.id }
        val localIds = local.map { it.id }.toSet()
        val toPush = mutableListOf<Note>()
        val toDeleteRemote = mutableListOf<String>()
        val toApplyRemote = mutableListOf<RemoteNote>()
        val toDeleteLocal = mutableListOf<String>()
        for (note in local) {
            val distant = remoteById[note.id]
            when {
                note.deleted -> toDeleteRemote += note.id
                note.dirty -> toPush += note
                distant == null -> toDeleteLocal += note.id
                distant.userUpdatedTime > note.userUpdatedTime -> toApplyRemote += distant
            }
        }
        for (distant in remote) {
            if (distant.id !in localIds) toApplyRemote += distant
        }
        return SyncPlan(toPush, toDeleteRemote, toApplyRemote, toDeleteLocal)
    }
}
