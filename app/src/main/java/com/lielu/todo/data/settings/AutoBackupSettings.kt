package com.lielu.todo.data.settings

/**
 * Réglages de la sauvegarde automatique : une copie datée est écrite chaque jour dans [folderUri], un dossier
 * choisi par l'utilisateur (Storage Access Framework — Google Drive, Fichiers…). Seules les [keep] plus
 * récentes sont conservées.
 */
data class AutoBackupSettings(
    val enabled: Boolean = false,
    val folderUri: String? = null,
    val keep: Int = DEFAULT_KEEP,
) {
    /** Active ET exploitable : il faut un dossier pour écrire quelque part. */
    val isActive: Boolean get() = enabled && folderUri != null

    companion object {
        const val DEFAULT_KEEP = 7
        const val MIN_KEEP = 1
        const val MAX_KEEP = 30
    }
}

/** Résultat de la dernière sauvegarde automatique, pour l'afficher dans Paramètres. */
data class BackupStatus(
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
)
