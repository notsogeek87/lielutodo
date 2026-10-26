package com.lielu.todo.data.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator

/** Icône d'app au choix : chacune est un `activity-alias` du manifest (un seul actif à la fois). */
enum class LauncherIcon(
    val alias: String,
    val label: String,
) {
    DEFAULT("LauncherDefault", "Violet (par défaut)"),
    RED("LauncherRed", "Rouge"),
    ORANGE("LauncherOrange", "Orange"),
    GREEN("LauncherGreen", "Vert"),
    BLUE("LauncherBlue", "Bleu"),
    BLACK("LauncherBlack", "Noir"),
}

/**
 * Préférences propres à l'appareil, hors du DataStore des réglages : elles n'ont pas à suivre une sauvegarde
 * restaurée sur un autre téléphone (un verrou ou une icône choisis ici n'ont de sens que sur cet appareil).
 */
class AppPrefs(
    context: Context,
) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var lockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK, value).apply()

    var completionSound: Boolean
        get() = prefs.getBoolean(KEY_SOUND, false)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    var launcherIcon: LauncherIcon
        get() = LauncherIcon.entries.firstOrNull { it.name == prefs.getString(KEY_ICON, null) } ?: LauncherIcon.DEFAULT
        set(value) {
            prefs.edit().putString(KEY_ICON, value.name).apply()
            applyLauncherIcon(value)
        }

    /**
     * Active l'alias choisi et désactive les autres. DONT_KILL_APP : sans lui Android ferme l'app à ce moment ;
     * le lanceur met à jour l'icône de lui-même (parfois après quelques secondes).
     */
    private fun applyLauncherIcon(icon: LauncherIcon) {
        LauncherIcon.entries.forEach { other ->
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, "com.lielu.todo.${other.alias}"),
                if (other ==
                    icon
                ) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /** Petit « bip » de validation à la fin d'une tâche, si l'option est activée. */
    fun playCompletionSound() {
        if (!completionSound) return
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, TONE_MS)
            // Libère le générateur une fois le son joué, sans bloquer le fil appelant.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, TONE_MS * 2L)
        }
    }

    private companion object {
        const val KEY_LOCK = "lock"
        const val KEY_SOUND = "sound"
        const val KEY_ICON = "icon"
        const val TONE_VOLUME = 60
        const val TONE_MS = 150
    }
}
