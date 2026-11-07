package com.lielu.todo.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lielu.githubupdater.UpdateInfo
import com.lielu.githubupdater.UpdateManager
import com.lielu.githubupdater.UpdateState
import com.lielu.todo.data.settings.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Vérifie automatiquement les mises à jour à l'ouverture de l'app et pilote la fenêtre qui les propose,
 * au plus une fois par semaine ([UpdatePromptPolicy]). L'état brut est celui de [UpdateManager], partagé avec
 * l'écran Paramètres, où l'utilisateur force une vérification quand il veut.
 */
class AppUpdateViewModel(
    private val updateManager: UpdateManager,
    private val updatesEnabled: Boolean,
    private val prefs: AppPrefs,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val state = updateManager.state

    private val _promptAllowed = MutableStateFlow(false)

    /**
     * `true` seulement quand la vérification hebdomadaire de CETTE fenêtre a trouvé une mise à jour : une
     * vérification forcée dans Paramètres (même état partagé) s'affiche là-bas, pas en fenêtre ; et une
     * activité recréée dans la semaine ne remontre pas la fenêtre.
     */
    val promptAllowed: StateFlow<Boolean> = _promptAllowed

    private val _dismissed = MutableStateFlow(false)

    /** `true` une fois « Plus tard » touché : la fenêtre ne revient qu'au prochain lancement. */
    val dismissed: StateFlow<Boolean> = _dismissed

    private val _userStarted = MutableStateFlow(false)

    /** `true` dès que l'utilisateur a touché « Installer » : seulement alors on lui montre une erreur. */
    val userStarted: StateFlow<Boolean> = _userStarted

    /** `false` tant qu'Android n'a pas autorisé l'app à installer des applications (étape à expliquer). */
    fun canInstallPackages(): Boolean = updateManager.canInstallPackages()

    /**
     * Appelée à chaque passage de l'app au premier plan. Ne fait rien si la fenêtre s'est affichée il y a moins
     * de 7 jours. Sinon `force = true` : sans cela la bibliothèque réutilise sa réponse précédente (« à jour »)
     * jusqu'à `checkIntervalHours`, et une release publiée entre-temps n'est pas vue. Tant qu'aucune mise à jour
     * n'est trouvée, chaque ouverture revérifie (une requête GitHub, très en dessous du quota de 60/h).
     */
    fun checkOnOpen() {
        if (!updatesEnabled) return
        if (_promptAllowed.value) return
        if (!UpdatePromptPolicy.shouldCheckOnOpen(clock(), prefs.updatePromptShownAt)) return
        // Ne pas écraser une fenêtre déjà affichée, un téléchargement ou une installation en cours.
        val s = state.value
        if (s !is UpdateState.Idle && s !is UpdateState.UpToDate && s !is UpdateState.Error) return
        // Les erreurs (hors ligne, quota GitHub…) sont publiées dans `state` ; ici on reste silencieux.
        viewModelScope.launch {
            val update = runCatching { updateManager.checkForUpdate(force = true) }.getOrNull()
            if (update != null) {
                // Compté comme affichée dès qu'elle est proposée : la suivante attend 7 jours.
                prefs.updatePromptShownAt = clock()
                _promptAllowed.value = true
            }
        }
    }

    fun onDismiss() {
        _dismissed.value = true
    }

    fun onInstall(update: UpdateInfo) {
        _userStarted.value = true
        viewModelScope.launch {
            runCatching {
                val apk = updateManager.downloadUpdate(update)
                install(apk)
            }
        }
    }

    /** Relance l'installation d'un APK déjà téléchargé, typiquement au retour des réglages Android. */
    fun onInstallDownloaded(apk: File) {
        _userStarted.value = true
        runCatching { install(apk) }
    }

    private fun install(apk: File) {
        if (updateManager.canInstallPackages()) {
            updateManager.installUpdate(apk)
        } else {
            updateManager.openInstallPermissionSettings()
        }
    }
}
