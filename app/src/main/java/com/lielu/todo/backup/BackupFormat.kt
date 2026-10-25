package com.lielu.todo.backup

import org.json.JSONException
import org.json.JSONObject

/** Fichier de sauvegarde illisible, d'un autre format ou d'une version plus récente que l'app. */
class InvalidBackupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Contenu décodé d'un fichier de sauvegarde. */
data class BackupEnvelope(
    val version: Int,
    val appId: String?,
    val exportedAt: Long,
    val sections: Map<String, JSONObject>,
)

/**
 * Format du fichier de sauvegarde :
 * ```
 * { "format": "lielu-backup", "version": 1, "appId": "com.lielu.todo", "exportedAt": 1700000000000,
 *   "sections": { "settings": { … }, "<autre section>": { … } } }
 * ```
 * `version` ne change que si l'enveloppe elle-même évolue ; l'évolution des données d'une section se gère
 * dans la section (champs optionnels), voir [BackupSection].
 */
object BackupFormat {
    const val FORMAT = "lielu-backup"
    const val VERSION = 1

    fun encode(
        appId: String,
        exportedAt: Long,
        sections: Map<String, JSONObject>,
    ): String {
        val sectionsJson = JSONObject()
        sections.forEach { (id, data) -> sectionsJson.put(id, data) }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("appId", appId)
            .put("exportedAt", exportedAt)
            .put("sections", sectionsJson)
            .toString()
    }

    fun decode(text: String): BackupEnvelope {
        val root =
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw InvalidBackupException("Ce fichier n'est pas une sauvegarde valide.", e)
            }
        if (root.optString("format") != FORMAT) {
            throw InvalidBackupException("Ce fichier n'est pas une sauvegarde de cette famille d'applications.")
        }
        val version = root.optInt("version", 0)
        if (version < 1) throw InvalidBackupException("Version de sauvegarde illisible.")
        if (version > VERSION) {
            throw InvalidBackupException(
                "Cette sauvegarde vient d'une version plus récente de l'app : mettez-la à jour.",
            )
        }
        val sectionsJson =
            root.optJSONObject("sections")
                ?: throw InvalidBackupException("La sauvegarde ne contient aucune section.")
        val sections = LinkedHashMap<String, JSONObject>()
        for (id in sectionsJson.keys()) {
            sectionsJson.optJSONObject(id)?.let { sections[id] = it }
        }
        return BackupEnvelope(
            version = version,
            appId = root.optString("appId").ifEmpty { null },
            exportedAt = root.optLong("exportedAt", 0L),
            sections = sections,
        )
    }
}
