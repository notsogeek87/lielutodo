package com.lielu.todo.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lielu.todo.R
import com.lielu.todo.data.api.ApiException
import com.lielu.todo.data.api.SessionStore
import com.lielu.todo.data.api.VikunjaClient
import com.lielu.todo.data.settings.JoplinSettings
import com.lielu.todo.data.settings.SettingsRepository
import com.lielu.todo.data.todo.TodoRepository
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun LoginScreen(
    session: SessionStore,
    client: VikunjaClient,
    repository: TodoRepository,
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(session.baseUrl) }
    var username by remember { mutableStateOf(session.username) }
    var showAdvanced by remember { mutableStateOf(session.baseUrl.isBlank()) }
    var password by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var needsTotp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Connexion réussie à un AUTRE compte alors que le précédent a des modifications non envoyées : à confirmer.
    var switchTo by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    // Section Notes (Joplin) : pré-remplie avec les valeurs déjà enregistrées.
    val savedJoplin by settings.joplin.collectAsState(initial = JoplinSettings())
    var joplinServer by remember { mutableStateOf("") }
    var joplinEmail by remember { mutableStateOf("") }
    var joplinPassword by remember { mutableStateOf("") }
    var joplinSyncEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(savedJoplin) {
        if (joplinServer.isBlank()) joplinServer = savedJoplin.serverUrl
        if (joplinEmail.isBlank()) joplinEmail = savedJoplin.email
        if (!joplinSyncEnabled) joplinSyncEnabled = savedJoplin.syncEnabled
    }

    fun finish(
        url: String,
        token: String,
        user: String,
    ) {
        // Un autre compte ne doit pas voir le cache du précédent.
        if (url != session.baseUrl || user != session.username) repository.reset()
        session.save(url, token, user, password.takeIf { totp.isBlank() })
        scope.launch {
            repository.refresh()
            if (joplinServer.isNotBlank()) {
                settings.setJoplinServerUrl(joplinServer.trim())
                settings.setJoplinEmail(joplinEmail.trim())
                if (joplinPassword.isNotBlank()) settings.setJoplinPassword(joplinPassword)
                val joplinReady = joplinEmail.isNotBlank() && joplinPassword.isNotBlank()
                settings.setJoplinSyncEnabled(joplinSyncEnabled && joplinReady)
            }
        }
    }

    fun submit() {
        busy = true
        error = null
        scope.launch {
            try {
                val url = VikunjaClient.normalizeUrl(server)
                val token = client.login(url, username.trim(), password, totp)
                val otherAccount = url != session.baseUrl || username.trim() != session.username
                val unsent = repository.state.value.let { it.ops.size + it.failed.size }
                if (otherAccount && unsent > 0) {
                    switchTo = Triple(url, token, username.trim())
                } else {
                    finish(url, token, username.trim())
                }
            } catch (e: ApiException) {
                if (e.code == HTTP_PRECONDITION_FAILED) needsTotp = true
                error =
                    if (e.code ==
                        HTTP_PRECONDITION_FAILED
                    ) {
                        "Code de double authentification requis ou incorrect."
                    } else {
                        e.message
                    }
            } catch (e: IOException) {
                error = "Serveur injoignable : ${e.message ?: "vérifiez l'adresse et la connexion"}"
            } finally {
                busy = false
            }
        }
    }

    switchTo?.let { (url, token, user) ->
        val unsent = repository.state.value.let { it.ops.size + it.failed.size }
        AlertDialog(
            onDismissRequest = { switchTo = null },
            title = { Text("Changer de compte ?") },
            text = {
                Text(
                    "Le compte précédent (${session.username}) a $unsent modification(s) pas encore envoyée(s). " +
                        "Se connecter avec un autre compte les efface. Pour les garder, reconnectez-vous d'abord " +
                        "avec ${session.username} quand le serveur répond.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    switchTo = null
                    finish(url, token, user)
                }) { Text("Effacer et continuer") }
            },
            dismissButton = { TextButton(onClick = { switchTo = null }) { Text("Annuler") } },
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(
                    rememberScrollState(),
                ).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = "Logo (double-tap pour modifier les réglages enregistrés)",
                modifier =
                    Modifier
                        .size(96.dp)
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { showAdvanced = !showAdvanced }) },
            )
        }
        Text("Connexion", style = MaterialTheme.typography.headlineMedium)

        Text(
            "Tâches (Vikunja)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (showAdvanced) {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Serveur") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Pseudo ou adresse e-mail") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mot de passe") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (needsTotp) {
            OutlinedTextField(
                value = totp,
                onValueChange = { totp = it },
                label = { Text("Code de double authentification") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            "Notes (Joplin Server)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Facultatif : synchronise vos notes avec un serveur Joplin.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (showAdvanced || savedJoplin.isConfigured) {
            OutlinedTextField(
                value = joplinServer,
                onValueChange = { joplinServer = it },
                label = { Text("Serveur Joplin") },
                placeholder = { Text("https://notes.lielu.eu") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = joplinEmail,
            onValueChange = { joplinEmail = it },
            label = { Text("E-mail Joplin") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        if (showAdvanced || savedJoplin.isConfigured) {
            OutlinedTextField(
                value = joplinPassword,
                onValueChange = { joplinPassword = it },
                label = { Text("Mot de passe Joplin") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = joplinSyncEnabled,
                    onCheckedChange = { joplinSyncEnabled = it },
                )
                Text("Activer la synchronisation des notes")
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = ::submit,
            enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text("Se connecter")
        }
    }
}

private const val HTTP_PRECONDITION_FAILED = 412
