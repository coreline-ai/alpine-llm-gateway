package dev.alpine.chat.feature.data

import android.content.Context
import android.util.AtomicFile
import dev.alpine.chat.feature.model.ChatConversation
import java.io.File
import java.io.FileOutputStream

data class ConversationLoadResult(
    val conversations: List<ChatConversation>,
    val activeConversationId: String?,
    val failedFileCount: Int = 0,
)

interface ConversationStorage {
    fun load(): ConversationLoadResult
    fun writeConversation(conversation: ChatConversation)
    fun writeIndex(index: ConversationIndex)
    fun deleteConversation(id: String)
}

class ConversationStore(
    context: Context,
    private val cipher: ConversationCipher = ConversationCrypto(),
) : ConversationStorage {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    @Synchronized
    override fun load(): ConversationLoadResult {
        directory.mkdirs()
        var failedFiles = 0
        val index = indexFile().takeIf { it.exists() }?.let { file ->
            runCatching { ConversationCodec.decodeIndex(readDecrypted(file, MAX_ENCRYPTED_INDEX_BYTES)) }
                .onFailure {
                    failedFiles += 1
                    quarantine(file)
                }
                .getOrNull()
        }
        val indexedIds = index?.summaries.orEmpty().map { it.id }
        val scannedIds = directory.listFiles()
            .orEmpty()
            .mapNotNull(::conversationIdFromFile)
        val ids = (indexedIds + scannedIds).distinct().take(ConversationCodec.MAX_CONVERSATIONS)
        val conversations = ids.mapNotNull { id ->
            val file = conversationFile(id)
            if (!file.exists()) return@mapNotNull null
            runCatching {
                ConversationCodec.decodeConversation(
                    readDecrypted(file, MAX_ENCRYPTED_CONVERSATION_BYTES),
                )
            }.onFailure {
                failedFiles += 1
                quarantine(file)
            }.getOrNull()
        }
        val activeId = index?.activeConversationId?.takeIf { candidate ->
            conversations.any { it.id == candidate }
        }
        return ConversationLoadResult(
            conversations = conversations,
            activeConversationId = activeId,
            failedFileCount = failedFiles,
        )
    }

    @Synchronized
    override fun writeConversation(conversation: ChatConversation) {
        directory.mkdirs()
        ConversationCodec.requireValidId(conversation.id)
        writeEncrypted(
            file = conversationFile(conversation.id),
            plaintext = ConversationCodec.encodeConversation(conversation),
            maxEncryptedBytes = MAX_ENCRYPTED_CONVERSATION_BYTES,
        )
    }

    @Synchronized
    override fun writeIndex(index: ConversationIndex) {
        directory.mkdirs()
        writeEncrypted(
            file = indexFile(),
            plaintext = ConversationCodec.encodeIndex(index),
            maxEncryptedBytes = MAX_ENCRYPTED_INDEX_BYTES,
        )
    }

    @Synchronized
    override fun deleteConversation(id: String) {
        ConversationCodec.requireValidId(id)
        AtomicFile(conversationFile(id)).delete()
    }

    @Synchronized
    fun clear() {
        directory.deleteRecursively()
        directory.mkdirs()
    }

    /** Test-only inspection hook used by host instrumentation recovery tests. */
    fun storageDirectoryForTests(): File = directory

    private fun readDecrypted(file: File, maxEncryptedBytes: Int): ByteArray {
        require(file.isFile && file.length() in 1L..maxEncryptedBytes.toLong()) {
            "Conversation storage file size is invalid"
        }
        val encrypted = AtomicFile(file).readFully()
        require(encrypted.size <= maxEncryptedBytes) { "Conversation storage file is too large" }
        return cipher.decrypt(encrypted)
    }

    private fun writeEncrypted(
        file: File,
        plaintext: ByteArray,
        maxEncryptedBytes: Int,
    ) {
        val encrypted = cipher.encrypt(plaintext)
        require(encrypted.size <= maxEncryptedBytes) { "Encrypted conversation is too large" }
        val atomicFile = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(encrypted)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }

    private fun indexFile(): File = File(directory, INDEX_FILE_NAME)

    private fun conversationFile(id: String): File = File(
        directory,
        "$CONVERSATION_FILE_PREFIX$id$CONVERSATION_FILE_SUFFIX",
    )

    private fun conversationIdFromFile(file: File): String? {
        val name = file.name
        if (
            !file.isFile ||
            !name.startsWith(CONVERSATION_FILE_PREFIX) ||
            !name.endsWith(CONVERSATION_FILE_SUFFIX)
        ) {
            return null
        }
        val id = name.removePrefix(CONVERSATION_FILE_PREFIX)
            .removeSuffix(CONVERSATION_FILE_SUFFIX)
        return runCatching { ConversationCodec.requireValidId(id); id }.getOrNull()
    }

    private fun quarantine(file: File) {
        val quarantine = File(file.parentFile, "${file.name}.unreadable")
        quarantine.delete()
        file.renameTo(quarantine)
    }

    companion object {
        const val DIRECTORY_NAME = "encrypted-conversations-v1"
        private const val INDEX_FILE_NAME = "index.enc"
        private const val CONVERSATION_FILE_PREFIX = "conversation_"
        private const val CONVERSATION_FILE_SUFFIX = ".enc"
        private const val ENCRYPTION_OVERHEAD_BYTES = 64
        private const val MAX_ENCRYPTED_CONVERSATION_BYTES =
            ConversationCodec.MAX_CONVERSATION_BYTES + ENCRYPTION_OVERHEAD_BYTES
        private const val MAX_ENCRYPTED_INDEX_BYTES =
            ConversationCodec.MAX_INDEX_BYTES + ENCRYPTION_OVERHEAD_BYTES
    }
}
