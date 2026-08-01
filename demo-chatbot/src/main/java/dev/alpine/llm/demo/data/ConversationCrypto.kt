package dev.alpine.llm.demo.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ConversationCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(envelope: ByteArray): ByteArray
}

class ConversationCrypto(
    private val keyAlias: String = KEY_ALIAS,
) : ConversationCipher {
    private val delegate = AesGcmConversationCipher(::loadOrCreateKey)

    override fun encrypt(plaintext: ByteArray): ByteArray = delegate.encrypt(plaintext)

    override fun decrypt(envelope: ByteArray): ByteArray = delegate.decrypt(envelope)

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val KEY_ALIAS = "alpine_demo_conversation_aes_gcm_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        internal fun deleteKeyForTests(alias: String = KEY_ALIAS) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }
}

class AesGcmConversationCipher(
    private val keyProvider: () -> SecretKey,
) : ConversationCipher {
    constructor(key: SecretKey) : this({ key })

    override fun encrypt(plaintext: ByteArray): ByteArray {
        require(plaintext.isNotEmpty()) { "Conversation payload is empty" }
        return cryptoBoundary {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // Android Keystore keys require the provider to generate the encryption nonce.
            // Supplying a caller nonce would require weakening randomized-encryption policy.
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
            val nonce = cipher.iv.also {
                require(it.size == NONCE_BYTES) { "Conversation nonce is invalid" }
            }
            val ciphertext = cipher.doFinal(plaintext)
            byteArrayOf(ENVELOPE_VERSION) + nonce + ciphertext
        }
    }

    override fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size >= 1 + NONCE_BYTES + TAG_BYTES) {
            "Conversation payload is invalid"
        }
        require(envelope[0] == ENVELOPE_VERSION) { "Conversation payload version is invalid" }
        return cryptoBoundary {
            val nonce = envelope.copyOfRange(1, 1 + NONCE_BYTES)
            val ciphertext = envelope.copyOfRange(1 + NONCE_BYTES, envelope.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, nonce))
            cipher.doFinal(ciphertext)
        }
    }

    private inline fun cryptoBoundary(block: () -> ByteArray): ByteArray = try {
        block()
    } catch (error: ConversationCryptoException) {
        throw error
    } catch (error: Exception) {
        throw ConversationCryptoException(error)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val ENVELOPE_VERSION: Byte = 1
    }
}

class ConversationCryptoException(cause: Throwable? = null) : IllegalStateException(
    "Conversation storage cryptography failed",
    cause,
)
