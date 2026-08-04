package ai.coreline.mobile_agent_auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Stores the MobileAgent local-only conversation snapshot outside backup and encrypted at rest. */
internal class SecureConversationStore(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val temporaryFile = File(context.noBackupFilesDir, "$FILE_NAME.tmp")

    @Synchronized
    fun read(): String? {
        if (!file.exists()) return null
        return try {
            require(file.length() in 1..MAX_ENCRYPTED_BYTES.toLong())
            val envelope = file.readBytes()
            val buffer = ByteBuffer.wrap(envelope)
            val ivLength = buffer.int
            require(ivLength in 12..16 && buffer.remaining() > ivLength)
            val iv = ByteArray(ivLength).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            }
            cipher.doFinal(ciphertext).decodeToString().also(::validateSnapshot)
        } catch (error: Exception) {
            throw ConversationStoreException(error)
        }
    }

    @Synchronized
    fun write(snapshot: String) {
        try {
            validateSnapshot(snapshot)
            val plaintext = snapshot.encodeToByteArray()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, encryptionKey())
            }
            val ciphertext = cipher.doFinal(plaintext)
            val envelope = ByteBuffer.allocate(Int.SIZE_BYTES + cipher.iv.size + ciphertext.size)
                .putInt(cipher.iv.size)
                .put(cipher.iv)
                .put(ciphertext)
                .array()
            require(envelope.size <= MAX_ENCRYPTED_BYTES)
            temporaryFile.parentFile?.mkdirs()
            FileOutputStream(temporaryFile).use { output ->
                output.write(envelope)
                output.fd.sync()
            }
            check(temporaryFile.renameTo(file)) { "conversation snapshot atomic replace failed" }
        } catch (error: Exception) {
            temporaryFile.delete()
            throw ConversationStoreException(error)
        }
    }

    @Synchronized
    fun clear() {
        try {
            temporaryFile.delete()
            if (file.exists()) check(file.delete()) { "conversation snapshot delete failed" }
        } catch (error: Exception) {
            throw ConversationStoreException(error)
        }
    }

    private fun validateSnapshot(snapshot: String) {
        val bytes = snapshot.encodeToByteArray()
        require(bytes.isNotEmpty() && bytes.size <= MAX_SNAPSHOT_BYTES)
        val root = JSONObject(snapshot)
        requireExactKeys(root, setOf("schemaVersion", "records"))
        require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION)
        val records = root.optJSONArray("records") ?: error("records missing")
        require(records.length() <= MAX_CONVERSATIONS)
        val ids = mutableSetOf<String>()
        var totalCharacters = 0
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: error("record invalid")
            requireExactKeys(
                record,
                setOf("id", "provider", "model", "updatedAtMillis", "messages"),
            )
            val id = record.optString("id")
            require(REQUEST_ID.matches(id) && ids.add(id))
            require(record.optString("provider") in PROVIDERS)
            require(MODEL.matches(record.optString("model")))
            require(record.optLong("updatedAtMillis", 0) > 0)
            val messages = record.optJSONArray("messages") ?: error("messages missing")
            require(messages.length() in 1..MAX_MESSAGES_PER_CONVERSATION)
            for (messageIndex in 0 until messages.length()) {
                val message = messages.optJSONObject(messageIndex) ?: error("message invalid")
                requireExactKeys(message, setOf("role", "content", "createdAtMillis"))
                val role = message.optString("role")
                require(role == "user" || role == "assistant")
                if (messageIndex == 0) require(role == "user")
                val content = message.optString("content")
                require(content.isNotBlank() && content.length <= MAX_MESSAGE_CHARACTERS)
                require(message.optLong("createdAtMillis", 0) > 0)
                totalCharacters += content.length
                require(totalCharacters <= MAX_SNAPSHOT_CHARACTERS)
            }
        }
    }

    private fun requireExactKeys(value: JSONObject, expected: Set<String>) {
        val keys = value.keys().asSequence().toSet()
        require(keys == expected)
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

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_CONVERSATIONS = 20
        const val MAX_MESSAGES_PER_CONVERSATION = 64
        const val MAX_MESSAGE_CHARACTERS = 32 * 1024
        const val MAX_SNAPSHOT_CHARACTERS = 512 * 1024
        const val MAX_SNAPSHOT_BYTES = 1024 * 1024
        const val MAX_ENCRYPTED_BYTES = MAX_SNAPSHOT_BYTES + 1024
        const val FILE_NAME = "mobile_agent_conversations_v1.bin"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mobile_agent_conversation_vault_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val REQUEST_ID = Regex("^[A-Za-z0-9_-]{8,80}$")
        val MODEL = Regex("^[A-Za-z0-9._:/-]{1,120}$")
        val PROVIDERS = setOf("openai", "anthropic", "xai")
    }
}

internal class ConversationStoreException(cause: Throwable) : Exception(cause)
