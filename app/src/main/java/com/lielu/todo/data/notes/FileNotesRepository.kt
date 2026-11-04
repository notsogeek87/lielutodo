package com.lielu.todo.data.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Notes conservées dans un fichier JSON du stockage interne, écrit de façon atomique (fichier temporaire puis
 * renommage) : même stratégie que le cache des tâches. L'état est tenu en mémoire et exposé en flux.
 */
class FileNotesRepository(
    file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : NotesRepository {
    private val store = MutableStateFlow<List<Note>>(emptyList())

    override val notes: Flow<List<Note>> =
        store.map { list -> list.filter { !it.deleted }.sortedByDescending { it.userUpdatedTime } }

    init {
        store.value = readAll(file)
    }

    override suspend fun getById(id: String): Note? = store.value.firstOrNull { it.id == id }

    override suspend fun create(): Note {
        val note =
            Note(
                id = UUID.randomUUID().toString(),
                title = "",
                body = "",
                userUpdatedTime = clock(),
                dirty = true,
            )
        upsert(note)
        return note
    }

    override suspend fun save(
        id: String,
        title: String,
        body: String,
    ) {
        val current = getById(id) ?: return
        upsert(current.copy(title = title, body = body, userUpdatedTime = clock(), dirty = true))
    }

    override suspend fun delete(id: String) {
        val current = getById(id) ?: return
        upsert(current.copy(deleted = true, dirty = true, userUpdatedTime = clock()))
    }

    override suspend fun getAllActive(): List<Note> = store.value.filter { !it.deleted }

    override suspend fun getAllIncludingDeleted(): List<Note> = store.value

    override suspend fun upsert(note: Note) {
        store.value = store.value.filter { it.id != note.id } + note
        persist()
    }

    override suspend fun deleteById(id: String) {
        store.value = store.value.filter { it.id != id }
        persist()
    }

    override suspend fun replaceAll(notes: List<Note>) {
        store.value = notes
        persist()
    }

    private suspend fun persist() {
        val snapshot = store.value
        withContext(Dispatchers.IO) { writeAll(file, snapshot) }
    }

    private fun readAll(file: File): List<Note> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { noteFromJson(array.getJSONObject(it)) }
        }.getOrElse { emptyList() }
    }

    private fun writeAll(
        file: File,
        notes: List<Note>,
    ) {
        val array = JSONArray()
        notes.forEach { array.put(noteToJson(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("notes", array).toString())
        tmp.renameTo(file)
    }

    private fun noteToJson(note: Note): JSONObject =
        JSONObject()
            .put("id", note.id)
            .put("title", note.title)
            .put("body", note.body)
            .put("parentId", note.parentId)
            .put("userUpdatedTime", note.userUpdatedTime)
            .put("dirty", note.dirty)
            .put("deleted", note.deleted)

    private fun noteFromJson(json: JSONObject): Note =
        Note(
            id = json.getString("id"),
            title = json.optString("title"),
            body = json.optString("body"),
            parentId = json.optString("parentId"),
            userUpdatedTime = json.optLong("userUpdatedTime", 0L),
            dirty = json.optBoolean("dirty", false),
            deleted = json.optBoolean("deleted", false),
        )
}
