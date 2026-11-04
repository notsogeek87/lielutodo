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
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : NotesRepository {
    private val store = MutableStateFlow<List<Note>>(emptyList())
    private val folderStore = MutableStateFlow<List<Folder>>(emptyList())

    override val notes: Flow<List<Note>> =
        store.map { list -> list.filter { !it.deleted }.sortedByDescending { it.userUpdatedTime } }

    override val folders: Flow<List<Folder>> = folderStore

    init {
        val loaded = readAll(file)
        store.value = loaded.first
        folderStore.value = loaded.second
    }

    override suspend fun getFolders(): List<Folder> = folderStore.value

    override suspend fun upsertFolders(folders: List<Folder>) {
        folderStore.value = (folderStore.value + folders).distinctBy { it.id }.sortedBy { it.title.lowercase() }
        persist()
    }

    override suspend fun getById(id: String): Note? = store.value.firstOrNull { it.id == id }

    override suspend fun create(): Note {
        val note =
            Note(
                id = UUID.randomUUID().toString().replace("-", ""),
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

    override suspend fun setParent(
        id: String,
        parentId: String,
    ) {
        val current = getById(id) ?: return
        upsert(current.copy(parentId = parentId, userUpdatedTime = clock(), dirty = true))
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

    override suspend fun replaceFolders(folders: List<Folder>) {
        folderStore.value = folders
        persist()
    }

    private suspend fun persist() {
        val notesSnapshot = store.value
        val foldersSnapshot = folderStore.value
        withContext(Dispatchers.IO) { writeAll(file, notesSnapshot, foldersSnapshot) }
    }

    private fun readAll(file: File): Pair<List<Note>, List<Folder>> {
        if (!file.exists()) return emptyList<Note>() to emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val notesArray = root.optJSONArray("notes") ?: JSONArray()
            val foldersArray = root.optJSONArray("folders") ?: JSONArray()
            val notes = (0 until notesArray.length()).map { noteFromJson(notesArray.getJSONObject(it)) }
            val folders = (0 until foldersArray.length()).map { folderFromJson(foldersArray.getJSONObject(it)) }
            notes to folders
        }.getOrElse { emptyList<Note>() to emptyList() }
    }

    private fun writeAll(
        file: File,
        notes: List<Note>,
        folders: List<Folder>,
    ) {
        val notesArray = JSONArray()
        notes.forEach { notesArray.put(noteToJson(it)) }
        val foldersArray = JSONArray()
        folders.forEach { foldersArray.put(folderToJson(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("notes", notesArray).put("folders", foldersArray).toString())
        tmp.renameTo(file)
    }

    private fun folderToJson(folder: Folder): JSONObject =
        JSONObject()
            .put("id", folder.id)
            .put("title", folder.title)
            .put("parentId", folder.parentId)

    private fun folderFromJson(json: JSONObject): Folder =
        Folder(
            id = json.getString("id"),
            title = json.optString("title"),
            parentId = json.optString("parentId"),
        )

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
