package com.lielu.todo.data.notes

/**
 * Une note, synchronisable avec un serveur Joplin.
 *
 * [id] est l'identifiant Joplin (32 caractères hexadécimaux), créé ici pour une nouvelle note et conservé tel quel
 * par le serveur. [userUpdatedTime] est l'estampille (ms) de la dernière modification, locale ou distante, pour
 * l'affichage et le tri. [dirty] marque une modification locale pas encore poussée ; [deleted] une suppression locale
 * en attente.
 *
 * [serverRaw] est le contenu sérialisé de la dernière version connue du serveur et [serverUpdatedTime] son
 * `updated_time` : à l'envoi, seuls titre, corps et carnet sont remplacés dans [serverRaw] (les autres propriétés
 * Joplin — to-do, échéance, auteur, source… — sont conservées), et une version distante plus récente que
 * [serverUpdatedTime] alors que la note est modifiée ici est un conflit (voir [SyncPlanner]).
 */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val parentId: String = "",
    val userUpdatedTime: Long = 0,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
    val createdTime: Long = 0,
    val serverRaw: String? = null,
    val serverUpdatedTime: Long = 0,
)

/**
 * Un carnet Joplin (dossier, item `type_: 2`). [shareId] non vide = carnet partagé (Joplin Server) : chaque note
 * qu'il contient est envoyée avec ce `share_id`, sinon les autres participants ne la verraient pas. [dirty] = créé ou
 * modifié ici, pas encore envoyé ; [serverRaw] comme pour [Note].
 */
data class Folder(
    val id: String,
    val title: String,
    val parentId: String = "",
    val shareId: String = "",
    val dirty: Boolean = false,
    val createdTime: Long = 0,
    val serverRaw: String? = null,
)
