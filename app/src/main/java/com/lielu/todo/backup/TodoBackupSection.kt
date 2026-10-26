package com.lielu.todo.backup

import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.data.todo.TodoStateCodec
import org.json.JSONObject

/**
 * Tâches, projets, étiquettes, filtres et écritures en attente. Le serveur reste la source de vérité, mais cette
 * section garde une copie lisible et restaurable : si le serveur personnel disparaît, rien n'est perdu.
 */
class TodoBackupSection(
    private val repository: TodoRepository,
) : BackupSection {
    override val id: String = "todo"

    override suspend fun export(): JSONObject = TodoStateCodec.encode(repository.state.value)

    override suspend fun restore(data: JSONObject) {
        repository.restore(TodoStateCodec.decode(data))
    }
}
