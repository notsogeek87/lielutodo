package com.lielu.todo.data.notes

/**
 * Une note, synchronisable avec un serveur Joplin.
 *
 * [id] est l'identifiant Joplin de la note ; tant qu'elle n'a jamais été synchronisée, c'est un UUID local
 * (le serveur en attribuera un autre à la première synchronisation). [userUpdatedTime] est l'estampille en
 * millisecondes de la dernière modification, locale ou distante : c'est elle qui tranche les conflits
 * (dernier modifié gagne, voir [SyncPlanner]). [dirty] marque une modification locale pas encore poussée vers
 * le serveur ; [deleted] une suppression locale en attente d'être poussée.
 */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val parentId: String = "",
    val userUpdatedTime: Long = 0,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
)
