package com.lielu.todo.data.notes

import kotlinx.coroutines.flow.Flow

interface NotesRepository {
    /** Notes actives (non supprimées), de la plus récemment modifiée à la plus ancienne. */
    val notes: Flow<List<Note>>

    suspend fun getById(id: String): Note?

    /** Crée une note vide (modifiée localement, en attente de synchro) et la retourne. */
    suspend fun create(): Note

    /** Enregistre le contenu d'une note existante ; elle passe en attente de synchro. */
    suspend fun save(
        id: String,
        title: String,
        body: String,
    )

    /** Marque la note comme supprimée (poussée au serveur à la prochaine synchro). */
    suspend fun delete(id: String)

    /** Affecte la note à un carnet (poussée au serveur à la prochaine synchro). */
    suspend fun setParent(
        id: String,
        parentId: String,
    )

    /** Carnets Joplin connus (issus de la synchro). */
    val folders: Flow<List<Folder>>

    suspend fun getFolders(): List<Folder>

    suspend fun upsertFolders(folders: List<Folder>)

    suspend fun getAllActive(): List<Note>

    suspend fun getAllIncludingDeleted(): List<Note>

    suspend fun upsert(note: Note)

    suspend fun deleteById(id: String)

    /** Remplace toutes les notes (restauration de sauvegarde), de façon atomique. */
    suspend fun replaceAll(notes: List<Note>)

    /** Remplace tous les carnets (restauration de sauvegarde). */
    suspend fun replaceFolders(folders: List<Folder>)
}
