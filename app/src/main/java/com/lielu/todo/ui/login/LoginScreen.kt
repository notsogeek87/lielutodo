package com.lielu.todo.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lielu.todo.data.api.ApiException
import com.lielu.todo.data.api.SessionStore
import com.lielu.todo.data.api.VikunjaClient
import com.lielu.todo.data.todo.TodoRepository
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun LoginScreen(
    session: SessionStore,
    client: VikunjaClient,
    repository: TodoRepository,
) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(session.baseUrl) }
    var username by remember { mutableStateOf(session.username) }
    var password by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var needsTotp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        busy = true
        error = null
        scope.launch {
            try {
                val url = VikunjaClient.normalizeUrl(server)
                val token = client.login(url, username.trim(), password, totp)
                // Un autre compte ne doit pas voir le cache du précédent.
                if (url != session.baseUrl || username.trim() != session.username) repository.reset()
                session.save(url, token, username.trim(), password.takeIf { totp.isBlank() })
                repository.refresh()
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
        Text("Connexion", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Connectez-vous à votre serveur Vikunja.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("Serveur") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Nom d'utilisateur") },
            singleLine = true,
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
