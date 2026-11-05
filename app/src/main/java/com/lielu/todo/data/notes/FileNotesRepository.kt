package com.lielu.todo.data.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Notes conservées dans un fichier JSON du stockage interne, même stratégie que le cache des tâches : écriture
 * atomique (fichier temporaire puis renommage) avec copie de secours `.bak` de la version précédente. Un fichier
 * illisible n'est jamais écrasé par une liste vide : on relit la copie de secours, sinon il est mis de côté
 * (`.corrupt`). Toutes les écritures passent par un verrou : l'écran et la synchro ne s'écrasent pas.
 */
class FileNotesRepository(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : NotesRepository {
    private val store = MutableStateFlow<List<Note>>(emptyList())
    private val folderStore = MutableStateFlow<List<Folder>>(emptyList())
    private val lock = Mutex()
    private val backup = File(file.parentFile, file.name + ".bak")

    override val notes: Flow<List<Note>> =
        store.map { list -> list.filter { !it.deleted }.sortedByDescending { it.userUpdatedTime } }

    override val folders: Flow<List<Folder>> = folderStore

    init {
        val loaded = load()
        store.value = loaded.first
        folderStore.value = loaded.second
    }

    override suspend fun getFolders(): List<Folder> = folderStore.value

    override suspend fun upsertFolders(folders: List<Folder>) =
        mutate {
            // Un carnet modifié ici et pas encore envoyé garde sa version locale.
            val local = folderStore.value.associateBy { it.id }
            val incoming = folders.filter { local[it.id]?.dirty != true }.associateBy { it.id }
            val byId = local + incoming
            folderStore.value = byId.values.sortedBy { it.title.lowercase() }
        }

    override suspend fun createFolder(title: String): Folder {
        val now = clock()
        val folder =
            Folder(
                id = UUID.randomUUID().toString().replace("-", ""),
                title = title.trim(),
                dirty = true,
                createdTime = now,
            )
        updateFolder(folder.id) { folder }
        return folder
    }

    override suspend fun updateFolder(
        id: String,
        transform: (Folder?) -> Folder?,
    ) = mutate {
        val current = folderStore.value.firstOrNull { it.id == id }
        val next = transform(current)
        if (next != current) {
            folderStore.value =
                (folderStore.value.filter { it.id != id } + listOfNotNull(next)).sortedBy { it.title.lowercase() }
        }
    }

    override suspend fun removeFolders(ids: Set<String>) =
        mutate { folderStore.value = folderStore.value.filter { it.id !in ids } }

    override suspend fun getById(id: String): Note? = store.value.firstOrNull { it.id == id }

    override suspend fun create(): Note {
        val now = clock()
        val note =
            Note(
                id = UUID.randomUUID().toString().replace("-", ""),
                title = "",
                body = "",
                userUpdatedTime = now,
                createdTime = now,
                dirty = true,
            )
        upsert(note)
        return note
    }

    override suspend fun save(
        id: String,
        title: String,
        body: String,
    ) = update(id) { it?.copy(title = title, body = body, userUpdatedTime = clock(), dirty = true) }

    override suspend fun delete(id: String) =
        update(id) { it?.copy(deleted = true, dirty = true, userUpdatedTime = clock()) }

    override suspend fun setParent(
        id: String,
        parentId: String,
    ) = update(id) { it?.copy(parentId = parentId, userUpdatedTime = clock(), dirty = true) }

    override suspend fun getAllActive(): List<Note> = store.value.filter { !it.deleted }

    override suspend fun getAllIncludingDeleted(): List<Note> = store.value

    override suspend fun upsert(note: Note) = update(note.id) { note }

    override suspend fun update(
        id: String,
        transform: (Note?) -> Note?,
    ) = mutate {
        val current = store.value.firstOrNull { it.id == id }
        val next = transform(current)
        if (next != current) {
            store.value = store.value.filter { it.id != id } + listOfNotNull(next)
        }
    }

    override suspend fun deleteById(id: String) = update(id) { null }

    override suspend fun replaceAll(notes: List<Note>) = mutate { store.value = notes }

    override suspend fun replaceFolders(folders: List<Folder>) = mutate { folderStore.value = folders }

    private suspend fun mutate(block: () -> Unit) {
        lock.withLock {
            block()
            val notesSnapshot = store.value
            val foldersSnapshot = folderStore.value
            withContext(Dispatchers.IO) { writeAll(notesSnapshot, foldersSnapshot) }
        }
    }

    private fun load(): Pair<List<Note>, List<Folder>> {
        if (!file.exists() && !backup.exists()) return emptyList<Note>() to emptyList()
        runCatching { if (file.exists()) return readAll(file) }
        runCatching { if (backup.exists()) return readAll(backup) }
        // Ni le fichier ni sa copie ne se lisent : on les met de côté plutôt que de les écraser au prochain
        // enregistrement (les notes non envoyées restent récupérables).
        file.renameTo(File(file.parentFile, file.name + ".corrupt"))
        return emptyList<Note>() to emptyList()
    }

    private fun readAll(source: File): Pair<List<Note>, List<Folder>> {
        val root = JSONObject(source.readText())
        val notesArray = root.optJSONArray("notes") ?: JSONArray()
        val foldersArray = root.optJSONArray("folders") ?: JSONArray()
        val notes = (0 until notesArray.length()).map { NoteJson.noteFromJson(notesArray.getJSONObject(it)) }
        val folders = (0 until foldersArray.length()).map { NoteJson.folderFromJson(foldersArray.getJSONObject(it)) }
        return notes to folders
    }

    private fun writeAll(
        notes: List<Note>,
        folders: List<Folder>,
    ) {
        val notesArray = JSONArray()
        notes.forEach { notesArray.put(NoteJson.noteToJson(it, includeLocalState = true)) }
        val foldersArray = JSONArray()
        folders.forEach { foldersArray.put(NoteJson.folderToJson(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("notes", notesArray).put("folders", foldersArray).toString())
        if (file.exists()) file.copyTo(backup, overwrite = true)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            if (!tmp.delete()) throw IOException("Écriture du cache des notes impossible : $file")
        }
    }
}

/** Codec JSON des notes, partagé par le cache disque et la sauvegarde ([com.lielu.todo.backup.NoteBackupSection]). */
object NoteJson {
    fun noteToJson(
        note: Note,
        includeLocalState: Boolean,
    ): JSONObject =
        JSONObject()
            .put("id", note.id)
            .put("title", note.title)
            .put("body", note.body)
            .put("parentId", note.parentId)
            .put("userUpdatedTime", note.userUpdatedTime)
            .put("dirty", note.dirty)
            .put("createdTime", note.createdTime)
            .put("serverRaw", note.serverRaw ?: JSONObject.NULL)
            .put("serverUpdatedTime", note.serverUpdatedTime)
            .apply { if (includeLocalState) put("deleted", note.deleted) }

    /** Champs ajoutés après la première version lus avec une valeur par défaut : les anciens fichiers restent lisibles. */
    fun noteFromJson(json: JSONObject): Note =
        Note(
            id = json.getString("id"),
            title = json.optString("title"),
            body = json.optString("body"),
            parentId = json.optString("parentId"),
            userUpdatedTime = json.optLong("userUpdatedTime", 0L),
            dirty = json.optBoolean("dirty", false),
            deleted = json.optBoolean("deleted", false),
            createdTime = json.optLong("createdTime", 0L),
            serverRaw = if (json.isNull("serverRaw")) null else json.optString("serverRaw"),
            serverUpdatedTime = json.optLong("serverUpdatedTime", 0L),
        )

    fun folderToJson(folder: Folder): JSONObject =
        JSONObject()
            .put("id", folder.id)
            .put("title", folder.title)
            .put("parentId", folder.parentId)
            .put("shareId", folder.shareId)
            .put("dirty", folder.dirty)
            .put("createdTime", folder.createdTime)
            .put("serverRaw", folder.serverRaw ?: JSONObject.NULL)

    fun folderFromJson(json: JSONObject): Folder =
        Folder(
            id = json.getString("id"),
            title = json.optString("title"),
            parentId = json.optString("parentId"),
            shareId = json.optString("shareId"),
            dirty = json.optBoolean("dirty", false),
            createdTime = json.optLong("createdTime", 0L),
            serverRaw = if (json.isNull("serverRaw")) null else json.optString("serverRaw"),
        )
}
