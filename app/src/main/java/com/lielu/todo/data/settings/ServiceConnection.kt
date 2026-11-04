package com.lielu.todo.data.settings

/**
 * Infos de connexion d'un service auto-hébergé (ex. Vikunja), mémorisées dans le DataStore.
 * Comme pour Joplin, ce sont des secrets : jamais exportés dans les sauvegardes (voir SettingsBackupSection).
 */
data class ServiceConnection(
    val serverUrl: String = "",
    val email: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && email.isNotBlank() && password.isNotBlank()
}
