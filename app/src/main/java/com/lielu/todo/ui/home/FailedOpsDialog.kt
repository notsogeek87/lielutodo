package com.lielu.todo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lielu.todo.data.todo.FailedOp
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.data.todo.TodoState
import com.lielu.todo.data.todo.describeWrite
import com.lielu.todo.ui.formatDateTime

/**
 * Modifications que le serveur a refusées : rien n'est effacé sans l'accord de l'utilisateur. Il peut les renvoyer
 * (après avoir corrigé la cause, côté serveur ou dans la tâche) ou y renoncer.
 */
@Composable
internal fun FailedOpsDialog(
    state: TodoState,
    repository: TodoRepository,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifications non envoyées") },
        text = {
            Column {
                Text(
                    "Le serveur a refusé ces modifications. Elles restent sur ce téléphone jusqu'à ce que vous les " +
                        "renvoyiez ou y renonciez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    items(state.failed) { f ->
                        FailedOpRow(
                            failed = f,
                            state = state,
                            onRetry = { repository.retryFailed(f) },
                            onAbandon = { repository.abandonFailed(f) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            if (state.failed.size > 1) {
                TextButton(onClick = {
                    repository.retryAllFailed()
                    onDismiss()
                }) { Text("Tout renvoyer") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun FailedOpRow(
    failed: FailedOp,
    state: TodoState,
    onRetry: () -> Unit,
    onAbandon: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(describeWrite(failed.op, state), style = MaterialTheme.typography.bodyMedium)
        Text(
            "Refusée le ${formatDateTime(failed.at)} : " +
                failed.message.ifBlank { "erreur" } + " (HTTP ${failed.code})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onAbandon) { Text("Abandonner") }
            TextButton(onClick = onRetry) { Text("Renvoyer") }
        }
    }
}
