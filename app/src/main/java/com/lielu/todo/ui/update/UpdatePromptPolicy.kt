package com.lielu.todo.ui.update

import java.util.concurrent.TimeUnit

/**
 * Fréquence de la fenêtre de mise à jour automatique : au plus une fois tous les [INTERVAL_MS]. Entre deux
 * fenêtres, aucune vérification n'est faite à l'ouverture ; l'utilisateur force la mise à jour depuis Paramètres.
 */
object UpdatePromptPolicy {
    val INTERVAL_MS: Long = TimeUnit.DAYS.toMillis(7)

    /**
     * `true` si l'ouverture doit vérifier (et donc pouvoir afficher la fenêtre). [lastShownAt] = 0 : jamais
     * affichée. Une date dans le futur (horloge reculée) ne bloque pas indéfiniment : on revérifie.
     */
    fun shouldCheckOnOpen(
        now: Long,
        lastShownAt: Long,
    ): Boolean = lastShownAt <= 0L || lastShownAt > now || now - lastShownAt >= INTERVAL_MS
}
