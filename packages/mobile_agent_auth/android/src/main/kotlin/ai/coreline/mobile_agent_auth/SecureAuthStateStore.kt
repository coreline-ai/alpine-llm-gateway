package ai.coreline.mobile_agent_auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.openid.appauth.AuthState

internal class SecureAuthStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun read(): AuthState? {
        val encoded = preferences.getString(AUTH_STATE_KEY, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(payload)
            val ivLength = buffer.int
            require(ivLength in 12..16 && buffer.remaining() > ivLength)
            val iv = ByteArray(ivLength).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            }
            AuthState.jsonDeserialize(cipher.doFinal(ciphertext).decodeToString())
        } catch (_: Exception) {
            clear()
            null
        }
    }

    @Synchronized
    fun write(state: AuthState) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, encryptionKey())
            }
            val ciphertext = cipher.doFinal(state.jsonSerializeString().encodeToByteArray())
            val payload = ByteBuffer.allocate(Int.SIZE_BYTES + cipher.iv.size + ciphertext.size)
                .putInt(cipher.iv.size)
                .put(cipher.iv)
                .put(ciphertext)
                .array()
            check(
                preferences.edit()
                    .putString(AUTH_STATE_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                    .commit(),
            ) { "secure auth state could not be persisted" }
        } catch (error: Exception) {
            throw SecureStoreException(error)
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(AUTH_STATE_KEY).commit()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "mobile_agent_auth_state"
        private const val AUTH_STATE_KEY = "encrypted_auth_state"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mobile_agent_oidc_auth_state_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal class SecureStoreException(cause: Throwable) : Exception(cause)
