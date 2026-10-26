package com.lielu.todo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lielu.todo.ui.task.QuickAddSheet
import com.lielu.todo.ui.task.add
import com.lielu.todo.ui.theme.ThemedContent

/**
 * Ajout rapide hors de l'app : widget, tuile des réglages rapides, raccourci d'icône, ou « Partager » depuis une
 * autre app (le texte partagé devient le titre). Fenêtre transparente : seule la feuille s'affiche.
 */
class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LieluApplication).container
        if (container.session.token == null) {
            // Pas connecté : l'écran de connexion est dans l'app.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        // Texte partagé = titre ; fichier ou photo partagé = tâche (titre : son nom) avec le fichier en pièce jointe.
        val stream: Uri? =
            if (intent.action == Intent.ACTION_SEND) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            } else {
                null
            }
        val file = stream?.let { readSharedFile(it) }
        val sharedText =
            if (intent.action == Intent.ACTION_SEND) {
                intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            } else {
                null
            }
        val shared = sharedText ?: file?.name?.substringBeforeLast('.').orEmpty()

        setContent {
            ThemedContent(container.settingsRepository) {
                val state by container.todoRepository.state.collectAsStateWithLifecycle()
                QuickAddSheet(
                    state = state,
                    defaultProjectId = state.inboxId,
                    defaultDate = null,
                    initialText = shared,
                    onAdd = {
                        val taskId = container.todoRepository.add(it)
                        file?.let { f -> container.todoRepository.addAttachment(taskId, f.name, f.mime, f.bytes) }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private class SharedFile(
        val name: String,
        val mime: String,
        val bytes: ByteArray,
    )

    /** Lit le fichier partagé ; `null` s'il est illisible ou dépasse les 20 Mo acceptés par Vikunja. */
    private fun readSharedFile(uri: Uri): SharedFile? =
        runCatching {
            val name =
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: uri.lastPathSegment ?: "fichier"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.size > MAX_FILE_BYTES) return null
            SharedFile(name, contentResolver.getType(uri) ?: "application/octet-stream", bytes)
        }.getOrNull()

    override fun onStop() {
        super.onStop()
        (application as LieluApplication).container.todoRepository.saveNow()
    }

    private companion object {
        const val MAX_FILE_BYTES = 20 * 1024 * 1024
    }
}
