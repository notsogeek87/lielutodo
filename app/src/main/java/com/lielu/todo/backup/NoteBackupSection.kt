package com.lielu.todo.backup

import com.lielu.todo.data.notes.NoteJson
import com.lielu.todo.data.notes.NotesRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Les notes dans les sauvegardes (manuelles ou automatiques), avec leur état de synchro : une modification ou une
 * suppression pas encore envoyée à Joplin le reste après restauration, et le contenu serveur gardé (`serverRaw`)
 * évite de perdre les propriétés Joplin au prochain envoi. `restore` lit tout le fichier avant de remplacer quoi
 * que ce soit.
 */
class NoteBackupSection(
    private val notes: NotesRepository,
) : BackupSection {
    override val id: String = "notes"

    override suspend fun export(): JSONObject {
        val array = JSONArray()
        notes.getAllIncludingDeleted().forEach { array.put(NoteJson.noteToJson(it, includeLocalState = true)) }
        val foldersArray = JSONArray()
        notes.getFolders().forEach { foldersArray.put(NoteJson.folderToJson(it)) }
        return JSONObject().put("notes", array).put("folders", foldersArray)
    }

    override suspend fun restore(data: JSONObject) {
        val array = data.optJSONArray("notes") ?: return
        val restored = (0 until array.length()).map { NoteJson.noteFromJson(array.getJSONObject(it)) }
        val foldersArray = data.optJSONArray("folders")
        val folders =
            foldersArray?.let {
                (0 until it.length()).map { i ->
                    NoteJson.folderFromJson(it.getJSONObject(i))
                }
            }
        notes.replaceAll(restored)
        if (folders != null) notes.replaceFolders(folders)
    }
}
