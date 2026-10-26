package com.lielu.todo

import android.content.Intent
import android.os.Bundle
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
        val shared =
            if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else ""

        setContent {
            ThemedContent(container.settingsRepository) {
                val state by container.todoRepository.state.collectAsStateWithLifecycle()
                QuickAddSheet(
                    state = state,
                    defaultProjectId = state.inboxId,
                    defaultDate = null,
                    initialText = shared,
                    onAdd = { container.todoRepository.add(it) },
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
