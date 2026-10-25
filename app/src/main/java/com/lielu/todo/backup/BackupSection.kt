package com.lielu.todo.backup

import org.json.JSONObject

/**
 * Une famille de données de l'app à sauvegarder (réglages, base Room, fichiers…).
 *
 * Le [BackupManager] assemble toutes les sections enregistrées dans `AppContainer.backupSections` en un seul
 * fichier JSON ; ajouter les données d'une app au modèle revient à écrire une section et à l'enregistrer.
 *
 * Contrat :
 * - [id] est stable et unique : il nomme la section dans le fichier. Le changer revient à perdre cette section
 *   dans les sauvegardes existantes.
 * - [restore] REMPLACE les données de la section (pas de fusion) et doit être atomique — une transaction
 *   (`database.withTransaction { … }`) pour une base de données. Il doit tolérer l'absence de champs
 *   ajoutés après la création de la sauvegarde (valeur par défaut), car les anciens fichiers restent lisibles.
 */
interface BackupSection {
    val id: String

    suspend fun export(): JSONObject

    suspend fun restore(data: JSONObject)
}
