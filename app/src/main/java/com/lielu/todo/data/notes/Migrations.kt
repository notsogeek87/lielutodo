package com.lielu.todo.data.notes

import androidx.room.migration.Migration

/**
 * Migrations Room, branchées dans `AppContainer` : une `Migration` par incrément de version de
 * [AppDatabase] (cohérence vérifiée par `scripts/verify.sh`).
 */
object Migrations {
    // Version 1 : création de la table `notes`, aucune migration.
    val ALL: Array<Migration> = emptyArray()
}
