package app.privatex.browser.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Biometric unlock for the vault: the vault's data key (VDK) is
 * wrapped with an Android Keystore AES key that requires a fresh
 * biometric authentication for every use. The wrapped blob lives in
 * SharedPreferences; the key itself never leaves the Keystore, and
 * enrolling new biometrics invalidates it (the user then falls back
 * to the master password and can re-enable).
 */
object BiometricVault {
    private const val KEY_ALIAS = "sett_vault_bio_key"
    private const val PREFS = "sett-vault-bio"
    private const val PREF_BLOB = "wrapped_vdk"

    fun isEnrolled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(PREF_BLOB)

    fun isAvailable(context: Context): Boolean =
        androidx.biometric.BiometricManager.from(context)
            .canAuthenticate(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG,
            ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Creates the auth-required Keystore key and returns an encrypt
     * cipher for BiometricPrompt's CryptoObject. After the user
     * authenticates, call [finishEnroll] with the authenticated cipher.
     */
    fun encryptCipher(): Cipher {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        val key = keyGenerator.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /** Stores iv+ciphertext of [vdk] wrapped by the authenticated cipher. */
    fun finishEnroll(context: Context, authenticatedCipher: Cipher, vdk: ByteArray) {
        val ct = authenticatedCipher.doFinal(vdk)
        val blob = authenticatedCipher.iv + ct
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_BLOB, java.util.Base64.getEncoder().encodeToString(blob))
            .apply()
    }

    /**
     * A decrypt-mode cipher for BiometricPrompt's CryptoObject. The
     * caller authenticates the user with this cipher, then calls
     * [unwrap] with the authenticated cipher.
     */
    fun decryptCipher(context: Context): Cipher? {
        val blob = storedBlob(context) ?: return null
        val key = loadKey() ?: return null
        val iv = blob.copyOf(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher
    }

    fun unwrap(context: Context, authenticatedCipher: Cipher): ByteArray? {
        val blob = storedBlob(context) ?: return null
        val ct = blob.copyOfRange(12, blob.size)
        return runCatching { authenticatedCipher.doFinal(ct) }.getOrNull()
    }

    fun disable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_BLOB).apply()
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(KEY_ALIAS)
        }
    }

    private fun storedBlob(context: Context): ByteArray? {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_BLOB, null) ?: return null
        return runCatching { java.util.Base64.getDecoder().decode(text) }.getOrNull()
    }

    private fun loadKey(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(KEY_ALIAS, null) as? SecretKey
    }
}
