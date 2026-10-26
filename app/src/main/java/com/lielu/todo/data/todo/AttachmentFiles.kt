package com.lielu.todo.data.todo

import java.io.File
import java.util.UUID

/**
 * Fichiers qui vivent sur le téléphone : ceux en attente d'envoi (copiés ici au moment de l'ajout, pour survivre à
 * un redémarrage et au retrait du fichier d'origine) et ceux déjà téléchargés (consultables serveur coupé).
 */
interface AttachmentFiles {
    /** Copie [bytes] dans le stockage des envois en attente ; renvoie un chemin à relire avec [readPending]. */
    fun stagePending(
        name: String,
        bytes: ByteArray,
    ): String

    fun readPending(path: String): ByteArray

    fun deletePending(path: String)

    /** Fichier déjà téléchargé pour cette pièce jointe, ou `null`. */
    fun cached(
        attachmentId: Long,
        name: String,
    ): File?

    fun cache(
        attachmentId: Long,
        name: String,
        bytes: ByteArray,
    ): File
}

class DiskAttachmentFiles(
    private val root: File,
) : AttachmentFiles {
    private val pending get() = File(root, "pending").also { it.mkdirs() }
    private val downloaded get() = File(root, "downloaded").also { it.mkdirs() }

    override fun stagePending(
        name: String,
        bytes: ByteArray,
    ): String = File(pending, UUID.randomUUID().toString()).also { it.writeBytes(bytes) }.path

    override fun readPending(path: String): ByteArray = File(path).readBytes()

    override fun deletePending(path: String) {
        File(path).delete()
    }

    override fun cached(
        attachmentId: Long,
        name: String,
    ): File? = fileFor(attachmentId, name).takeIf { it.exists() }

    override fun cache(
        attachmentId: Long,
        name: String,
        bytes: ByteArray,
    ): File = fileFor(attachmentId, name).also { it.writeBytes(bytes) }

    // Le nom d'origine est gardé (le visionneur d'Android déduit le type de l'extension), préfixé par l'id pour
    // que deux pièces jointes de même nom ne s'écrasent pas.
    private fun fileFor(
        id: Long,
        name: String,
    ) = File(downloaded, "$id-" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
}
