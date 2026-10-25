package com.lielu.todo

import android.content.Context

/** Nommage des fichiers de sauvegarde, commun à l'export manuel et à la sauvegarde automatique. */
object BackupFileNames {
    /** « lieluinit » pour « LieluInit » : nom de l'app en minuscules, sans caractère risqué dans un nom de fichier. */
    fun prefix(context: Context): String = slug(context.getString(R.string.app_name))

    internal fun slug(appName: String): String =
        appName
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "app" }
}
