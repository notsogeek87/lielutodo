package com.lielu.todo.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Vrai si l'appareil a un code, un schéma ou une empreinte : sans verrou d'écran, rien à demander. */
fun deviceHasSecureLock(context: Context): Boolean =
    (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

/**
 * Écran de verrouillage : demande le code, le schéma ou l'empreinte de l'appareil (fenêtre système, donc aucune
 * donnée biométrique ne passe par l'app). [onUnlocked] est appelé une fois l'identité confirmée.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) onUnlocked()
        }
    val intent =
        remember {
            (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .createConfirmDeviceCredentialIntent("Application verrouillée", "Confirmez votre identité")
        }
    // Appareil sans verrou d'écran (retiré depuis l'activation) : impossible de demander quoi que ce soit.
    LaunchedEffect(Unit) {
        if (intent == null) onUnlocked() else launcher.launch(intent)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            "Application verrouillée",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (intent != null) {
            Button(
                onClick = { launcher.launch(intent) },
                modifier = Modifier.padding(top = 24.dp),
            ) { Text("Déverrouiller") }
        }
    }
}
