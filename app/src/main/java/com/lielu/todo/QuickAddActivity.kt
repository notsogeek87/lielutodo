package com.lielu.todo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.lielu.todo.ui.common.IncomingFile
import com.lielu.todo.ui.common.IncomingFileResult
import com.lielu.todo.ui.common.problem
import com.lielu.todo.ui.common.readIncomingFile
import com.lielu.todo.ui.task.QuickAddSheet
import com.lielu.todo.ui.task.add
import com.lielu.todo.ui.theme.ThemedContent
import kotlinx.coroutines.launch

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
        val sharedText =
            if (intent.action == Intent.ACTION_SEND) {
                intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            } else {
                null
            }
        // Lecture hors du fil principal (un gros fichier ne doit pas geler l'écran), puis la feuille s'affiche.
        lifecycleScope.launch {
            val read = stream?.let { readIncomingFile(this@QuickAddActivity, it) }
            read?.problem()?.let { Toast.makeText(this@QuickAddActivity, it, Toast.LENGTH_LONG).show() }
            val file = (read as? IncomingFileResult.Ok)?.file
            showSheet(container, sharedText ?: file?.name?.substringBeforeLast('.').orEmpty(), file)
        }
    }

    private fun showSheet(
        container: AppContainer,
        initialText: String,
        file: IncomingFile?,
    ) {
        setContent {
            ThemedContent(container.settingsRepository) {
                val state by container.todoRepository.state.collectAsStateWithLifecycle()
                QuickAddSheet(
                    state = state,
                    defaultProjectId = state.inboxId,
                    defaultDate = null,
                    initialText = initialText,
                    onAdd = {
                        val taskId = container.todoRepository.add(it)
                        file?.let { f -> container.todoRepository.addAttachment(taskId, f.name, f.mime, f.bytes) }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        (application as LieluApplication).container.todoRepository.saveNow()
    }
}
