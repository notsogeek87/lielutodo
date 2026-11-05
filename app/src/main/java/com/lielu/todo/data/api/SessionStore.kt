package com.lielu.todo.data.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lielu.todo.data.settings.SecretStore
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
) : SecretStore {
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
        val encrypted = password?.let { encryptNew(it, dropJoplin = false) }
        if (encrypted != null) editor.putString(KEY_PASSWORD, encrypted) else editor.remove(KEY_PASSWORD)
        editor.apply()
        _loggedIn.value = true
    }

    /** Nouveau jeton après une reconnexion silencieuse ; ne touche pas au reste. */
    fun updateToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    /**
     * Mot de passe du serveur Joplin (notes), chiffré comme celui de Vikunja : hors DataStore, donc jamais dans
     * une sauvegarde Android ni dans un export. Survit à la déconnexion Vikunja (autre serveur).
     */
    override var joplinPassword: String
        get() = prefs.getString(KEY_JOPLIN_PASSWORD, null)?.let { runCatching { decrypt(it) }.getOrNull() }.orEmpty()
        set(value) {
            val encrypted = value.takeIf { it.isNotEmpty() }?.let { encryptNew(it, dropJoplin = true) }
            prefs
                .edit()
                .apply {
                    if (encrypted !=
                        null
                    ) {
                        putString(KEY_JOPLIN_PASSWORD, encrypted)
                    } else {
                        remove(KEY_JOPLIN_PASSWORD)
                    }
                }.apply()
        }

    override var joplinServer: String
        get() = prefs.getString(KEY_JOPLIN_SERVER, null).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_JOPLIN_SERVER, value).apply()
        }

    override var joplinEmail: String
        get() = prefs.getString(KEY_JOPLIN_EMAIL, null).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_JOPLIN_EMAIL, value).apply()
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

    /** Clé existante, ou null. Ne crée jamais de clé : une clé recréée rendrait illisibles les mots de passe gardés. */
    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
    }

    /**
     * Clé pour CHIFFRER : l'existante, créée seulement s'il n'y en a pas. Le déchiffrement passe par [existingKey] : si
     * le Keystore ne répond pas (ex. relance juste après une mise à jour), il échoue sans rien remplacer et sera
     * retenté, au lieu de générer une nouvelle clé qui ferait perdre le mot de passe Joplin pour toujours.
     */
    private fun key(): SecretKey {
        existingKey()?.let { return it }
        // Des mots de passe chiffrés existent : la clé existe forcément, le Keystore ne répond simplement pas.
        check(prefs.getString(KEY_PASSWORD, null) == null && prefs.getString(KEY_JOPLIN_PASSWORD, null) == null) {
            "Clé du Keystore indisponible"
        }
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

    /**
     * Chiffre un mot de passe que l'utilisateur vient de saisir. Si la clé a vraiment disparu (anciens chiffrés
     * illisibles pour toujours), ces chiffrés sont abandonnés et une clé neuve est créée : la nouvelle saisie prime.
     * Une connexion Vikunja ([dropJoplin] faux) ne sacrifie jamais le mot de passe Joplin : au pire, Vikunja est
     * enregistré sans mot de passe (reconnexion silencieuse indisponible).
     */
    private fun encryptNew(
        plain: String,
        dropJoplin: Boolean,
    ): String? =
        runCatching { encrypt(plain) }.getOrElse {
            val keyGone = runCatching { existingKey() == null }.getOrDefault(false)
            if (!keyGone) return null
            prefs
                .edit()
                .remove(KEY_PASSWORD)
                .apply { if (dropJoplin) remove(KEY_JOPLIN_PASSWORD) }
                .commit()
            runCatching { encrypt(plain) }.getOrNull()
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
                val key = existingKey() ?: error("Clé du Keystore indisponible")
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, data, 0, GCM_IV_BYTES))
            }
        return String(cipher.doFinal(data, GCM_IV_BYTES, data.size - GCM_IV_BYTES), Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_URL = "https://task.lielu.eu"
        private const val KEY_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "username"
        private const val KEY_PASSWORD = "password_enc"
        private const val KEY_JOPLIN_PASSWORD = "joplin_password_enc"
        private const val KEY_JOPLIN_SERVER = "joplin_server"
        private const val KEY_JOPLIN_EMAIL = "joplin_email"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "lielutodo_session"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
