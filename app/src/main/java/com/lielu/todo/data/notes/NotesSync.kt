package com.lielu.todo.data.notes

import com.lielu.todo.data.joplin.JoplinApi
import com.lielu.todo.data.joplin.JoplinApiException
import com.lielu.todo.data.joplin.RemoteNote
import com.lielu.todo.data.settings.JoplinSettings
import com.lielu.todo.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

sealed interface SyncResult {
    data class Success(
        val pushed: Int,
        val pulled: Int,
        val deleted: Int,
    ) : SyncResult

    data class Failure(
        val message: String,
    ) : SyncResult
}

/**
 * Synchronise les notes locales avec le serveur Joplin, en lecture ET en écriture : pousse les
 * modifications et suppressions locales, applique les changements distants. Le plan est calculé par
 * [SyncPlanner] ; ici on l'exécute via [JoplinApi] et on met la base à jour.
 */
class NotesSync(
    private val settings: SettingsRepository,
    private val notes: NotesRepository,
    private val apiFactory: (JoplinSettings) -> JoplinApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun syncNow(): SyncResult {
        val config = settings.joplin.first()
        if (!config.syncEnabled || !config.isConfigured) {
            return fail("Synchronisation Joplin non configurée (voir Paramètres).")
        }
        val api = apiFactory(config)
        return try {
            val plan = SyncPlanner.plan(notes.getAllIncludingDeleted(), api.listNotes())
            var pushed = 0
            for (note in plan.toPush) {
                val remote =
                    try {
                        api.updateNote(note.id, note.title, note.body, note.parentId)
                    } catch (e: JoplinApiException) {
                        // Note jamais poussée (404) : on la crée côté serveur, qui lui donne son vrai id.
                        if (e.statusCode == 404) {
                            api.createNote(note.title, note.body, note.parentId)
                        } else {
                            throw e
                        }
                    }
                if (remote.id != note.id) notes.deleteById(note.id)
                notes.upsert(syncedNote(if (remote.id == note.id) note else null, remote))
                pushed++
            }
            for (id in plan.toDeleteRemote) {
                try {
                    api.deleteNote(id)
                } catch (e: JoplinApiException) {
                    if (e.statusCode != 404) throw e
                }
                notes.deleteById(id)
            }
            for (remote in plan.toApplyRemote) {
                notes.upsert(syncedNote(notes.getById(remote.id), remote))
            }
            for (id in plan.toDeleteLocal) notes.deleteById(id)
            settings.recordSyncSuccess(clock())
            SyncResult.Success(
                pushed = pushed,
                pulled = plan.toApplyRemote.size,
                deleted = plan.toDeleteRemote.size + plan.toDeleteLocal.size,
            )
        } catch (e: Exception) {
            fail("Échec de la synchronisation : ${e.message}")
        }
    }

    private suspend fun fail(message: String): SyncResult {
        settings.recordSyncFailure(message)
        return SyncResult.Failure(message)
    }

    private fun syncedNote(
        existing: Note?,
        remote: RemoteNote,
    ) = (existing ?: Note(id = remote.id, title = "", body = "")).copy(
        id = remote.id,
        title = remote.title,
        body = remote.body,
        parentId = remote.parentId,
        userUpdatedTime = remote.userUpdatedTime,
        dirty = false,
        deleted = false,
    )
}
