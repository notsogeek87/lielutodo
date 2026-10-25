package com.lielu.todo.data.api

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Serveur + jeton de connexion. SharedPreferences (et non le DataStore des réglages) : ce fichier n'est pas dans
 * `backup_rules.xml`, le jeton ne part donc ni dans une sauvegarde Android ni dans un export.
 */
class SessionStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    private val _loggedIn = MutableStateFlow(token != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    val baseUrl: String get() = prefs.getString(KEY_URL, null) ?: DEFAULT_URL
    val token: String? get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    val username: String get() = prefs.getString(KEY_USER, null).orEmpty()

    fun save(
        baseUrl: String,
        token: String,
        username: String,
    ) {
        prefs
            .edit()
            .putString(KEY_URL, baseUrl)
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER, username)
            .apply()
        _loggedIn.value = true
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
        _loggedIn.value = false
    }

    companion object {
        const val DEFAULT_URL = "https://task.lielu.eu"
        private const val KEY_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "username"
    }
}
