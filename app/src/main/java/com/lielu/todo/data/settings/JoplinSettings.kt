package com.lielu.todo.data.settings

/**
 * Connexion au serveur Joplin de l'utilisateur (ex. https://notes.lielu.eu). Le jeton d'API est un secret :
 * il vit dans le DataStore mais n'est JAMAIS exporté dans les sauvegardes (voir SettingsBackupSection).
 */
data class JoplinSettings(
    val serverUrl: String = "",
    val apiToken: String = "",
    val syncEnabled: Boolean = false,
) {
    /** Assez renseigné pour tenter une synchronisation. */
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiToken.isNotBlank()
}

/** Résultat de la dernière synchronisation Joplin, pour l'afficher dans Paramètres. */
data class SyncStatus(
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
)
