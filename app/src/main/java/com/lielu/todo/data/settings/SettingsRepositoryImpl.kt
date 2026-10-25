package com.lielu.todo.data.settings

import androidx.datastore.core.DataStore
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

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val AUTO_BACKUP_FOLDER = stringPreferencesKey("auto_backup_folder")
        val AUTO_BACKUP_KEEP = intPreferencesKey("auto_backup_keep")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val LAST_BACKUP_ERROR = stringPreferencesKey("last_backup_error")
        val WELCOME_SHOWN = booleanPreferencesKey("welcome_shown")
    }
}
