package com.lielu.todo.data.notes

import com.lielu.todo.data.joplin.RemoteNote

/** Ce qu'une synchronisation doit faire, calculé sans aucun effet de bord (testé unitairement). */
data class SyncPlan(
    /** Notes locales modifiées à pousser vers le serveur. */
    val toPush: List<Note>,
    /** Notes supprimées localement, à mettre à la corbeille côté serveur. */
    val toDeleteRemote: List<Note>,
    /** Notes reçues à écrire en local (nouvelles ou plus récentes que la copie locale propre). */
    val toApplyRemote: List<RemoteNote>,
    /** Ids de notes locales à supprimer (supprimées côté serveur, sans modification locale en attente). */
    val toDeleteLocal: List<String>,
    /**
     * Versions distantes modifiées ailleurs pendant que la note l'était aussi ici : la version locale est poussée,
     * la version distante est gardée à côté dans une copie « (conflit) » — rien n'est perdu.
     */
    val conflicts: List<RemoteNote>,
)

/**
 * Découpe une synchronisation en actions.
 * - Note locale propre : la version distante l'emporte si elle est plus récente que la dernière version connue.
 * - Note locale modifiée ([Note.dirty]) : poussée. Si la version distante a aussi changé depuis
 *   [Note.serverUpdatedTime] (ou si on ne la connaît pas) avec un contenu différent, c'est un conflit : la version
 *   distante est conservée dans une copie.
 * - Suppression d'un côté sans modification de l'autre : appliquée de l'autre côté.
 */
object SyncPlanner {
    fun plan(
        local: List<Note>,
        pulled: List<RemoteNote>,
        remoteDeleted: Set<String>,
    ): SyncPlan {
        val localById = local.associateBy { it.id }
        val toPush = local.filter { it.dirty && !it.deleted }
        val toDeleteRemote = local.filter { it.deleted }
        val toApplyRemote = mutableListOf<RemoteNote>()
        val conflicts = mutableListOf<RemoteNote>()
        for (remote in pulled) {
            val localNote = localById[remote.id]
            when {
                localNote == null -> toApplyRemote += remote
                localNote.deleted -> Unit
                !localNote.dirty -> {
                    val known = localNote.serverUpdatedTime.takeIf { it > 0 } ?: localNote.userUpdatedTime
                    if (remote.userUpdatedTime > known) toApplyRemote += remote
                }
                remoteChangedSinceBase(localNote, remote) && !sameContent(localNote, remote) -> conflicts += remote
            }
        }
        val toDeleteLocal = local.filter { it.id in remoteDeleted && !it.dirty }.map { it.id }
        return SyncPlan(toPush, toDeleteRemote, toApplyRemote, toDeleteLocal, conflicts)
    }

    private fun remoteChangedSinceBase(
        local: Note,
        remote: RemoteNote,
    ): Boolean = local.serverUpdatedTime == 0L || remote.userUpdatedTime > local.serverUpdatedTime

    private fun sameContent(
        local: Note,
        remote: RemoteNote,
    ): Boolean = local.title == remote.title && local.body == remote.body
}
