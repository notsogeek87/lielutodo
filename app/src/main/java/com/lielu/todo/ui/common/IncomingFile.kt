package com.lielu.todo.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Fichier reçu d'une autre app (partage, sélecteur de fichiers, appareil photo), prêt à joindre à une tâche. */
class IncomingFile(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
)

sealed interface IncomingFileResult {
    class Ok(
        val file: IncomingFile,
    ) : IncomingFileResult

    /** Plus de [MAX_INCOMING_FILE_BYTES] : Vikunja le refuserait. */
    data object TooBig : IncomingFileResult

    /** Adresse refusée (fichier brut `file://`, fichier privé de l'app) ou illisible. */
    data object Unreadable : IncomingFileResult
}

/** Vikunja refuse au-delà de 20 Mo par fichier : on le dit tout de suite plutôt qu'après un envoi raté. */
const val MAX_INCOMING_FILE_BYTES = 20 * 1024 * 1024

fun IncomingFileResult.problem(): String? =
    when (this) {
        is IncomingFileResult.Ok -> null
        IncomingFileResult.TooBig -> "Fichier trop gros : 20 Mo au plus."
        IncomingFileResult.Unreadable -> "Impossible de lire ce fichier."
    }

/**
 * Seules les adresses `content://` d'une AUTRE app sont acceptées d'un partage : un `file://` ou une adresse du
 * FileProvider de l'app ferait joindre (et envoyer au serveur) un fichier privé de l'app, à la demande d'une app
 * tierce. [allowOwn] : la photo que l'app vient de prendre, elle, passe par son propre FileProvider.
 */
fun acceptsIncomingUri(
    scheme: String?,
    authority: String?,
    ownAuthority: String,
    allowOwn: Boolean,
): Boolean = scheme == "content" && (allowOwn || authority != ownAuthority)

/** Lit au plus [max] octets ; `null` s'il y en a davantage (sans jamais tout charger en mémoire). */
fun readAtMost(
    input: InputStream,
    max: Int,
): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > max) return null
        out.write(buffer, 0, n)
    }
}

/** Lit le fichier hors du fil principal : un gros fichier ne doit ni geler l'écran ni saturer la mémoire. */
suspend fun readIncomingFile(
    context: Context,
    uri: Uri,
    allowOwn: Boolean = false,
): IncomingFileResult =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        if (!acceptsIncomingUri(uri.scheme, uri.authority, "${context.packageName}.files", allowOwn)) {
            return@withContext IncomingFileResult.Unreadable
        }
        runCatching {
            var name: String? = null
            var size: Long? = null
            resolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use {
                    if (it.moveToFirst()) {
                        name = it.getString(0)
                        if (!it.isNull(1)) size = it.getLong(1)
                    }
                }
            // Taille annoncée : refus immédiat, sans rien lire.
            if ((size ?: 0) > MAX_INCOMING_FILE_BYTES) return@withContext IncomingFileResult.TooBig
            val stream = resolver.openInputStream(uri) ?: return@withContext IncomingFileResult.Unreadable
            val bytes =
                stream.use { readAtMost(it, MAX_INCOMING_FILE_BYTES) } ?: return@withContext IncomingFileResult.TooBig
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            IncomingFileResult.Ok(IncomingFile(name ?: uri.lastPathSegment ?: "fichier", mime, bytes))
        }.getOrElse { IncomingFileResult.Unreadable }
    }

private const val BUFFER_BYTES = 64 * 1024
