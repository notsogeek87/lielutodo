package com.lielu.todo.data.settings

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    /** Apparence choisie dans Paramètres — [ThemeMode.SYSTEM] par défaut. */
    val themeMode: Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)

    val autoBackup: Flow<AutoBackupSettings>

    suspend fun setAutoBackupEnabled(enabled: Boolean)

    /** Mémorise le dossier de sauvegarde automatique ; `null` l'oublie (et la désactive). */
    suspend fun setAutoBackupFolder(uri: String?)

    suspend fun setAutoBackupKeep(keep: Int)

    val backupStatus: Flow<BackupStatus>

    suspend fun recordBackupSuccess(timestamp: Long)

    suspend fun recordBackupFailure(message: String)

    /** `true` une fois l'invitation de bienvenue (« restaurer une sauvegarde ? ») traitée. */
    val welcomeShown: Flow<Boolean>

    suspend fun setWelcomeShown()

    /** Connexion au serveur Joplin ; le jeton n'est jamais exporté dans les sauvegardes. */
    val joplin: Flow<JoplinSettings>

    suspend fun setJoplinServerUrl(url: String)

    suspend fun setJoplinApiToken(token: String)

    suspend fun setJoplinSyncEnabled(enabled: Boolean)

    val syncStatus: Flow<SyncStatus>

    suspend fun recordSyncSuccess(timestamp: Long)

    suspend fun recordSyncFailure(message: String)
}
