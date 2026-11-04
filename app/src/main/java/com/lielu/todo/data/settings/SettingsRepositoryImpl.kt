package com.lielu.todo.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override val themeMode: Flow<ThemeMode> =
        dataStore.data.map { prefs -> ThemeMode.fromStorageValue(prefs[Keys.THEME_MODE]) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override val autoBackup: Flow<AutoBackupSettings> =
        dataStore.data.map { prefs ->
            AutoBackupSettings(
                enabled = prefs[Keys.AUTO_BACKUP_ENABLED] ?: false,
                folderUri = prefs[Keys.AUTO_BACKUP_FOLDER],
                keep =
                    (prefs[Keys.AUTO_BACKUP_KEEP] ?: AutoBackupSettings.DEFAULT_KEEP)
                        .coerceIn(AutoBackupSettings.MIN_KEEP, AutoBackupSettings.MAX_KEEP),
            )
        }

    override suspend fun setAutoBackupEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_BACKUP_ENABLED] = enabled }
    }

    override suspend fun setAutoBackupFolder(uri: String?) {
        dataStore.edit {
            if (uri == null) {
                it.remove(Keys.AUTO_BACKUP_FOLDER)
                it[Keys.AUTO_BACKUP_ENABLED] = false
            } else {
                it[Keys.AUTO_BACKUP_FOLDER] = uri
            }
        }
    }

    override suspend fun setAutoBackupKeep(keep: Int) {
        dataStore.edit {
            it[Keys.AUTO_BACKUP_KEEP] = keep.coerceIn(AutoBackupSettings.MIN_KEEP, AutoBackupSettings.MAX_KEEP)
        }
    }

    override val backupStatus: Flow<BackupStatus> =
        dataStore.data.map { prefs ->
            BackupStatus(
                lastSuccessAt = prefs[Keys.LAST_BACKUP_AT],
                lastError = prefs[Keys.LAST_BACKUP_ERROR],
            )
        }

    override suspend fun recordBackupSuccess(timestamp: Long) {
        dataStore.edit {
            it[Keys.LAST_BACKUP_AT] = timestamp
            it.remove(Keys.LAST_BACKUP_ERROR)
        }
    }

    override suspend fun recordBackupFailure(message: String) {
        dataStore.edit { it[Keys.LAST_BACKUP_ERROR] = message }
    }

    override val welcomeShown: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[Keys.WELCOME_SHOWN] ?: false }

    override suspend fun setWelcomeShown() {
        dataStore.edit { it[Keys.WELCOME_SHOWN] = true }
    }

    override val joplin: Flow<JoplinSettings> =
        dataStore.data.map { prefs ->
            JoplinSettings(
                serverUrl = prefs[Keys.JOPLIN_SERVER_URL] ?: "",
                email = prefs[Keys.JOPLIN_EMAIL] ?: "",
                password = prefs[Keys.JOPLIN_PASSWORD] ?: "",
                syncEnabled = prefs[Keys.JOPLIN_SYNC_ENABLED] ?: false,
            )
        }

    override suspend fun setJoplinServerUrl(url: String) {
        dataStore.edit { it[Keys.JOPLIN_SERVER_URL] = url.trim() }
    }

    override suspend fun setJoplinEmail(email: String) {
        dataStore.edit { it[Keys.JOPLIN_EMAIL] = email.trim() }
    }

    override suspend fun setJoplinPassword(password: String) {
        dataStore.edit { it[Keys.JOPLIN_PASSWORD] = password }
    }

    override suspend fun setJoplinSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.JOPLIN_SYNC_ENABLED] = enabled }
    }

    override val syncStatus: Flow<SyncStatus> =
        dataStore.data.map { prefs ->
            SyncStatus(
                lastSuccessAt = prefs[Keys.LAST_SYNC_AT],
                lastError = prefs[Keys.LAST_SYNC_ERROR],
            )
        }

    override suspend fun recordSyncSuccess(timestamp: Long) {
        dataStore.edit {
            it[Keys.LAST_SYNC_AT] = timestamp
            it.remove(Keys.LAST_SYNC_ERROR)
        }
    }

    override suspend fun recordSyncFailure(message: String) {
        dataStore.edit { it[Keys.LAST_SYNC_ERROR] = message }
    }

    override val vikunja: Flow<ServiceConnection> =
        dataStore.data.map { prefs -> serviceConnection(prefs, "vikunja") }

    override suspend fun setVikunja(connection: ServiceConnection) {
        dataStore.edit { putServiceConnection(it, "vikunja", connection) }
    }

    private fun serviceConnection(
        prefs: Preferences,
        prefix: String,
    ): ServiceConnection =
        ServiceConnection(
            serverUrl = prefs[stringPreferencesKey("${prefix}_server_url")] ?: "",
            email = prefs[stringPreferencesKey("${prefix}_email")] ?: "",
            password = prefs[stringPreferencesKey("${prefix}_password")] ?: "",
        )

    private fun putServiceConnection(
        prefs: MutablePreferences,
        prefix: String,
        connection: ServiceConnection,
    ) {
        prefs[stringPreferencesKey("${prefix}_server_url")] = connection.serverUrl.trim()
        prefs[stringPreferencesKey("${prefix}_email")] = connection.email.trim()
        prefs[stringPreferencesKey("${prefix}_password")] = connection.password
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val AUTO_BACKUP_FOLDER = stringPreferencesKey("auto_backup_folder")
        val AUTO_BACKUP_KEEP = intPreferencesKey("auto_backup_keep")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val LAST_BACKUP_ERROR = stringPreferencesKey("last_backup_error")
        val WELCOME_SHOWN = booleanPreferencesKey("welcome_shown")
        val JOPLIN_SERVER_URL = stringPreferencesKey("joplin_server_url")
        val JOPLIN_EMAIL = stringPreferencesKey("joplin_email")
        val JOPLIN_PASSWORD = stringPreferencesKey("joplin_password")
        val JOPLIN_SYNC_ENABLED = booleanPreferencesKey("joplin_sync_enabled")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val LAST_SYNC_ERROR = stringPreferencesKey("last_sync_error")
    }
}
