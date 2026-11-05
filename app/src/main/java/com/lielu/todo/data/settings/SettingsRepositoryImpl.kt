package com.lielu.todo.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Rangement du mot de passe Joplin hors DataStore (le DataStore part dans la sauvegarde Android). */
interface SecretStore {
    var joplinPassword: String

    /**
     * Copie de secours du serveur et de l'e-mail Joplin, à côté de la session Vikunja (qui survit aux mises à jour) :
     * le compte Joplin reste connu même si le DataStore perd ces clés.
     */
    var joplinServer: String
    var joplinEmail: String
}

class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val secrets: SecretStore,
) : SettingsRepository {
    private val joplinPassword = MutableStateFlow(secrets.joplinPassword)

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
        combine(dataStore.data, joplinPassword) { prefs, password ->
            val email = prefs[Keys.JOPLIN_EMAIL]?.takeIf { it.isNotBlank() } ?: secrets.joplinEmail
            // Mot de passe illisible au démarrage (Keystore pas encore prêt, ex. relance juste après une mise à
            // jour) : relu ici plutôt que de rester vide jusqu'au prochain redémarrage de l'app.
            val pwd =
                password.ifEmpty {
                    secrets.joplinPassword.also { if (it.isNotEmpty()) joplinPassword.value = it }
                }
            JoplinSettings(
                // Jamais saisi : serveur par défaut (les identifiants suffisent alors pour synchroniser).
                serverUrl =
                    prefs[Keys.JOPLIN_SERVER_URL]?.takeIf { it.isNotBlank() }
                        ?: secrets.joplinServer.ifBlank { DEFAULT_JOPLIN_SERVER },
                email = email,
                password = pwd.ifEmpty { prefs[Keys.LEGACY_JOPLIN_PASSWORD].orEmpty() },
                // Réglage absent (DataStore perdu) : actif dès qu'un compte est connu.
                syncEnabled = prefs[Keys.JOPLIN_SYNC_ENABLED] ?: (email.isNotBlank() && pwd.isNotEmpty()),
            )
        }

    /** Les premières versions rangeaient le mot de passe Joplin en clair dans le DataStore : on le déplace. */
    suspend fun migrateLegacySecrets() {
        val prefs = dataStore.data.first()
        // Copie de secours du compte Joplin pour les installations qui l'avaient saisi avant qu'elle existe.
        withContext(Dispatchers.IO) {
            prefs[Keys.JOPLIN_SERVER_URL]?.takeIf { it.isNotBlank() && secrets.joplinServer.isBlank() }?.let {
                secrets.joplinServer = it
            }
            prefs[Keys.JOPLIN_EMAIL]?.takeIf { it.isNotBlank() && secrets.joplinEmail.isBlank() }?.let {
                secrets.joplinEmail = it
            }
        }
        val legacy = prefs[Keys.LEGACY_JOPLIN_PASSWORD] ?: return
        if (joplinPassword.value.isEmpty() && legacy.isNotEmpty()) setJoplinPassword(legacy)
        dataStore.edit { it.remove(Keys.LEGACY_JOPLIN_PASSWORD) }
    }

    override suspend fun setJoplinServerUrl(url: String) {
        dataStore.edit { it[Keys.JOPLIN_SERVER_URL] = url.trim() }
        withContext(Dispatchers.IO) { secrets.joplinServer = url.trim() }
    }

    override suspend fun setJoplinEmail(email: String) {
        dataStore.edit { it[Keys.JOPLIN_EMAIL] = email.trim() }
        withContext(Dispatchers.IO) { secrets.joplinEmail = email.trim() }
    }

    override suspend fun setJoplinPassword(password: String) {
        withContext(Dispatchers.IO) { secrets.joplinPassword = password }
        joplinPassword.value = password
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

    override val joplinCursor: Flow<String?> =
        dataStore.data.map { prefs -> prefs[Keys.JOPLIN_CURSOR] }

    override suspend fun setJoplinCursor(cursor: String?) {
        dataStore.edit {
            if (cursor == null) {
                it.remove(Keys.JOPLIN_CURSOR)
            } else {
                it[Keys.JOPLIN_CURSOR] = cursor
            }
        }
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

        /** Ancien emplacement (en clair, sauvegardé par Android) : lu une fois puis effacé. */
        val LEGACY_JOPLIN_PASSWORD = stringPreferencesKey("joplin_password")
        val JOPLIN_SYNC_ENABLED = booleanPreferencesKey("joplin_sync_enabled")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val LAST_SYNC_ERROR = stringPreferencesKey("last_sync_error")
        val JOPLIN_CURSOR = stringPreferencesKey("joplin_cursor")
    }
}
