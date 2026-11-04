package com.lielu.todo.data.settings

/**
 * Connexion au serveur Joplin de l'utilisateur (ex. https://notes.lielu.eu). L'e-mail et le mot de passe
 * sont des secrets : ils vivent dans le DataStore mais ne sont JAMAIS exportés dans les sauvegardes
 * (voir SettingsBackupSection). À chaque synchronisation, ils servent à ouvrir une session
 * (`POST /api/sessions`) dont l'id authentifie ensuite les appels de l'API Data (`X-API-AUTH`).
 */
data class JoplinSettings(
    val serverUrl: String = "",
    val email: String = "",
    val password: String = "",
    val syncEnabled: Boolean = false,
) {
    /** Assez renseigné pour tenter une synchronisation. */
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && email.isNotBlank() && password.isNotBlank()
}

/** Résultat de la dernière synchronisation Joplin, pour l'afficher dans Paramètres. */
data class SyncStatus(
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
)
