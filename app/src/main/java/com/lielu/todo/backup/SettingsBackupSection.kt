package com.lielu.todo.backup

import com.lielu.todo.data.settings.SettingsRepository
import com.lielu.todo.data.settings.ThemeMode
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Réglages qui suivent l'utilisateur d'un appareil à l'autre : thème, et serveur + e-mail Joplin (pas secrets : après
 * une réinstallation suivie d'une restauration, seul le mot de passe reste à retaper). Le dossier de sauvegarde
 * automatique et le mot de passe Joplin n'en font volontairement pas partie (autorisation propre à l'appareil, secret).
 */
class SettingsBackupSection(
    private val settings: SettingsRepository,
) : BackupSection {
    override val id: String = "settings"

    override suspend fun export(): JSONObject {
        val joplin = settings.joplin.first()
        return JSONObject()
            .put("themeMode", settings.themeMode.first().name)
            .put("joplinServerUrl", joplin.serverUrl)
            .put("joplinEmail", joplin.email)
    }

    override suspend fun restore(data: JSONObject) {
        settings.setThemeMode(ThemeMode.fromStorageValue(data.optString("themeMode")))
        // Ne remplace jamais un compte Joplin déjà saisi sur cet appareil (ancien fichier : champs absents).
        val current = settings.joplin.first()
        if (current.email.isBlank() && data.optString("joplinEmail").isNotBlank()) {
            data.optString("joplinServerUrl").takeIf { it.isNotBlank() }?.let { settings.setJoplinServerUrl(it) }
            settings.setJoplinEmail(data.optString("joplinEmail"))
        }
    }
}
