package com.lielu.todo.testutil

import com.lielu.todo.data.notes.Folder
import com.lielu.todo.data.notes.Note
import com.lielu.todo.data.notes.NotesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Remplacant en mémoire de [NotesRepository]. */
class FakeNotesRepository : NotesRepository {
    private val store = MutableStateFlow<List<Note>>(emptyList())
    private val folderStore = MutableStateFlow<List<Folder>>(emptyList())

    override val folders: Flow<List<Folder>> = folderStore

    override suspend fun getFolders(): List<Folder> = folderStore.value

    override suspend fun upsertFolders(folders: List<Folder>) {
        folderStore.value = (folderStore.value + folders).distinctBy { it.id }
    }

    override val notes: Flow<List<Note>> =
        store.map { list -> list.filter { !it.deleted }.sortedByDescending { it.userUpdatedTime } }

    override suspend fun getById(id: String): Note? = store.value.firstOrNull { it.id == id }

    override suspend fun create(): Note {
        val note = Note(id = "local-${store.value.size + 1}", title = "", body = "", userUpdatedTime = 1L, dirty = true)
        store.value = store.value + note
        return note
    }

    override suspend fun save(
        id: String,
        title: String,
        body: String,
    ) {
        store.value = store.value.map { if (it.id == id) it.copy(title = title, body = body, dirty = true) else it }
    }

    override suspend fun delete(id: String) {
        store.value = store.value.map { if (it.id == id) it.copy(deleted = true, dirty = true) else it }
    }

    override suspend fun setParent(
        id: String,
        parentId: String,
    ) {
        store.value = store.value.map { if (it.id == id) it.copy(parentId = parentId, dirty = true) else it }
    }

    override suspend fun getAllActive(): List<Note> = store.value.filter { !it.deleted }

    override suspend fun getAllIncludingDeleted(): List<Note> = store.value

    override suspend fun upsert(note: Note) {
        store.value = store.value.filter { it.id != note.id } + note
    }

    override suspend fun update(
        id: String,
        transform: (Note?) -> Note?,
    ) {
        val hook = beforeUpdate
        beforeUpdate = null
        hook?.invoke()
        val next = transform(getById(id))
        store.value = store.value.filter { it.id != id } + listOfNotNull(next)
    }

    override suspend fun removeFolders(ids: Set<String>) {
        folderStore.value = folderStore.value.filter { it.id !in ids }
    }

    /** Appelé avant chaque `update` (simule une modification de l'utilisateur pendant une synchro). */
    var beforeUpdate: (suspend () -> Unit)? = null

    override suspend fun deleteById(id: String) {
        store.value = store.value.filter { it.id != id }
    }

    override suspend fun replaceAll(notes: List<Note>) {
        store.value = notes
    }

    override suspend fun replaceFolders(folders: List<Folder>) {
        folderStore.value = folders
    }
}
