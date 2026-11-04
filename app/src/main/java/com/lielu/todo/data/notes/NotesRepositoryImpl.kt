package com.lielu.todo.data.notes

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class NotesRepositoryImpl(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : NotesRepository {
    private val dao = db.noteDao()

    override val notes: Flow<List<Note>> = dao.observeActive()

    override suspend fun getById(id: String): Note? = dao.getById(id)

    override suspend fun create(): Note {
        val note =
            Note(
                id = UUID.randomUUID().toString(),
                title = "",
                body = "",
                userUpdatedTime = clock(),
                dirty = true,
            )
        dao.upsert(note)
        return note
    }

    override suspend fun save(
        id: String,
        title: String,
        body: String,
    ) {
        val current = dao.getById(id) ?: return
        dao.upsert(current.copy(title = title, body = body, userUpdatedTime = clock(), dirty = true))
    }

    override suspend fun delete(id: String) {
        val current = dao.getById(id) ?: return
        dao.upsert(current.copy(deleted = true, dirty = true, userUpdatedTime = clock()))
    }

    override suspend fun getAllActive(): List<Note> = dao.getAllActive()

    override suspend fun getAllIncludingDeleted(): List<Note> = dao.getAll()

    override suspend fun upsert(note: Note) = dao.upsert(note)

    override suspend fun deleteById(id: String) = dao.deleteById(id)

    override suspend fun replaceAll(notes: List<Note>) {
        db.withTransaction {
            dao.deleteAll()
            dao.upsertAll(notes)
        }
    }
}
