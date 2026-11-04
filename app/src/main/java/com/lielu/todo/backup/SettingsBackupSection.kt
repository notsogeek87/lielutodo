package com.lielu.todo.backup

import com.lielu.todo.data.settings.SettingsRepository
import com.lielu.todo.data.settings.ThemeMode
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Réglages qui suivent l'utilisateur d'un appareil à l'autre. Le dossier de sauvegarde automatique et le
 * jeton Joplin n'en font volontairement pas partie : ce sont des secrets/autorisations propres à l'appareil,
 * restaurés ailleurs ils ne serviraient à rien (voire seraient dangereux).
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
