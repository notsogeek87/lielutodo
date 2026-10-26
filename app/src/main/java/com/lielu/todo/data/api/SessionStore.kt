package com.lielu.todo.data.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Serveur + jeton de connexion. SharedPreferences (et non le DataStore des réglages) : ce fichier n'est pas dans
 * `backup_rules.xml`, le jeton ne part donc ni dans une sauvegarde Android ni dans un export.
 *
 * Le mot de passe est gardé chiffré (clé AES du Keystore Android, non exportable) pour se reconnecter en silence
 * quand le jeton expire : l'utilisateur ne ressaisit rien. Sans TOTP uniquement (le code change à chaque fois).
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

    /** Mot de passe mémorisé, ou `null` (absent, ou clé Keystore perdue). */
    val password: String?
        get() =
            prefs.getString(KEY_PASSWORD, null)?.let { runCatching { decrypt(it) }.getOrNull() }

    fun save(
        baseUrl: String,
        token: String,
        username: String,
        password: String? = null,
    ) {
        val editor =
            prefs
                .edit()
                .putString(KEY_URL, baseUrl)
                .putString(KEY_TOKEN, token)
                .putString(KEY_USER, username)
        val encrypted = password?.let { runCatching { encrypt(it) }.getOrNull() }
        if (encrypted != null) editor.putString(KEY_PASSWORD, encrypted) else editor.remove(KEY_PASSWORD)
        editor.apply()
        _loggedIn.value = true
    }

    /** Nouveau jeton après une reconnexion silencieuse ; ne touche pas au reste. */
    fun updateToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun forgetPassword() {
        prefs.edit().remove(KEY_PASSWORD).apply()
    }

    fun clear() {
        prefs
            .edit()
            .remove(KEY_TOKEN)
            .remove(KEY_PASSWORD)
            .apply()
        _loggedIn.value = false
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, data, 0, GCM_IV_BYTES))
            }
        return String(cipher.doFinal(data, GCM_IV_BYTES, data.size - GCM_IV_BYTES), Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_URL = "https://task.lielu.eu"
        private const val KEY_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "username"
        private const val KEY_PASSWORD = "password_enc"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "lielutodo_session"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
