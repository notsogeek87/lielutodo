package com.lielu.todo.backup

import com.lielu.todo.data.notes.Folder
import com.lielu.todo.data.notes.Note
import com.lielu.todo.data.notes.NotesRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Les notes dans les sauvegardes (manuelles ou automatiques). Les notes marquées supprimées (en attente
 * de synchro Joplin) ne sont pas exportées ; `restore` remplace tout, de façon atomique.
 */
class NoteBackupSection(
    private val notes: NotesRepository,
) : BackupSection {
    override val id: String = "notes"

    override suspend fun export(): JSONObject {
        val array = JSONArray()
        for (note in notes.getAllActive()) {
            array.put(
                JSONObject()
                    .put("id", note.id)
                    .put("title", note.title)
                    .put("body", note.body)
                    .put("parentId", note.parentId)
                    .put("userUpdatedTime", note.userUpdatedTime)
                    .put("dirty", note.dirty),
            )
        }
        val foldersArray = JSONArray()
        for (folder in notes.getFolders()) {
            foldersArray.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("title", folder.title)
                    .put("parentId", folder.parentId),
            )
        }
        return JSONObject().put("notes", array).put("folders", foldersArray)
    }

    override suspend fun restore(data: JSONObject) {
        val array = data.optJSONArray("notes") ?: return
        val restored =
            (0 until array.length()).map { i ->
                val json = array.getJSONObject(i)
                Note(
                    id = json.getString("id"),
                    title = json.optString("title"),
                    body = json.optString("body"),
                    parentId = json.optString("parentId"),
                    userUpdatedTime = json.optLong("userUpdatedTime", 0L),
                    dirty = json.optBoolean("dirty", false),
                )
            }
        notes.replaceAll(restored)
        val foldersArray = data.optJSONArray("folders") ?: return
        val folders =
            (0 until foldersArray.length()).map { i ->
                val json = foldersArray.getJSONObject(i)
                Folder(
                    id = json.getString("id"),
                    title = json.optString("title"),
                    parentId = json.optString("parentId"),
                )
            }
        notes.replaceFolders(folders)
    }
}
