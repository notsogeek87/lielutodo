package com.lielu.todo.backup

import com.lielu.todo.data.settings.SettingsRepository
import com.lielu.todo.data.settings.ThemeMode
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Réglages qui suivent l'utilisateur d'un appareil à l'autre. Le dossier de sauvegarde automatique n'en fait
 * volontairement pas partie : c'est une autorisation propre à l'appareil, restaurée ailleurs elle ne
 * servirait à rien.
 */
class SettingsBackupSection(
    private val settings: SettingsRepository,
) : BackupSection {
    override val id: String = "settings"

    override suspend fun export(): JSONObject = JSONObject().put("themeMode", settings.themeMode.first().name)

    override suspend fun restore(data: JSONObject) {
        settings.setThemeMode(ThemeMode.fromStorageValue(data.optString("themeMode")))
    }
}
