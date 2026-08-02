package dev.alpine.llm.demo.data

import dev.alpine.llm.demo.assistant.AssistantCatalog
import dev.alpine.llm.demo.model.AssistantSelection
import dev.alpine.llm.demo.model.ChatConversation
import dev.alpine.llm.demo.model.ConversationSummary
import dev.alpine.llm.demo.model.ConversationText
import dev.alpine.chat.routing.ChatExecutionMode
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ConversationSnapshot(
    val conversations: List<ChatConversation>,
    val activeConversationId: String,
    val recoveredFileCount: Int = 0,
) {
    init {
        require(conversations.isNotEmpty()) { "At least one conversation is required" }
        require(conversations.any { it.id == activeConversationId }) {
            "Active conversation is missing"
        }
    }

    val activeConversation: ChatConversation
        get() = conversations.first { it.id == activeConversationId }

    val summaries: List<ConversationSummary>
        get() = conversations
            .sortedWith(compareByDescending<ChatConversation> { it.updatedAtMs }.thenBy { it.id })
            .map(ChatConversation::summary)
}

class ConversationRepository(
    private val storage: ConversationStorage? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    val isPersistent: Boolean
        get() = storage != null

    private val persistenceMutex = Mutex()
    private val revisionLock = Any()
    private var latestRequestedRevision = 0L
    private var persistedConversationHashes = emptyMap<String, String>()
    private var persistedIndexHash: String? = null

    fun initialSnapshot(
        selectedProfileId: String? = null,
        selectedModel: String? = null,
        executionMode: ChatExecutionMode = ChatExecutionMode.FAST_CHAT,
        assistantSelection: AssistantSelection = AssistantSelection.DEFAULT,
    ): ConversationSnapshot {
        val now = clock()
        val resolvedAssistant = AssistantCatalog.resolve(assistantSelection)
        val conversation = ChatConversation(
            id = idFactory(),
            selectedProfileId = selectedProfileId,
            selectedModel = selectedModel,
            executionMode = executionMode,
            selectedSkillId = resolvedAssistant.skillId,
            selectedPersonaId = resolvedAssistant.personaId,
            createdAtMs = now,
            updatedAtMs = now,
        )
        return ConversationSnapshot(listOf(conversation), conversation.id)
    }

    suspend fun load(
        fallbackProfileId: String? = null,
        fallbackModel: String? = null,
        fallbackAssistantSelection: AssistantSelection = AssistantSelection.DEFAULT,
    ): ConversationSnapshot {
        val source = storage ?: return initialSnapshot(
            fallbackProfileId,
            fallbackModel,
            ChatExecutionMode.FAST_CHAT,
            fallbackAssistantSelection,
        )
        return withContext(ioDispatcher) {
            val loaded = source.load()
            val now = clock()
            val conversations = loaded.conversations
                .distinctBy { it.id }
                .map(::normalizeAssistantMode)
                .map { it.normalizeInterrupted(now) }
                .take(ConversationCodec.MAX_CONVERSATIONS)
            val snapshot = if (conversations.isEmpty()) {
                initialSnapshot(
                    fallbackProfileId,
                    fallbackModel,
                    ChatExecutionMode.FAST_CHAT,
                    fallbackAssistantSelection,
                ).copy(
                    recoveredFileCount = loaded.failedFileCount,
                )
            } else {
                val activeId = loaded.activeConversationId
                    ?.takeIf { id -> conversations.any { it.id == id } }
                    ?: conversations.maxBy { it.updatedAtMs }.id
                ConversationSnapshot(
                    conversations = conversations,
                    activeConversationId = activeId,
                    recoveredFileCount = loaded.failedFileCount,
                )
            }
            persistedConversationHashes = loaded.conversations.associate { conversation ->
                conversation.id to sha256(ConversationCodec.encodeConversation(conversation))
            }
            // The encrypted index is intentionally rewritten after load so recovered files,
            // interrupted STREAMING normalization, and a fallback active id become canonical.
            persistedIndexHash = null
            snapshot
        }
    }

    fun create(
        snapshot: ConversationSnapshot,
        selectedProfileId: String?,
        selectedModel: String?,
        executionMode: ChatExecutionMode = snapshot.activeConversation.executionMode,
        assistantSelection: AssistantSelection = AssistantSelection.DEFAULT,
    ): ConversationSnapshot {
        if (snapshot.activeConversation.isCompletelyEmpty()) {
            return select(snapshot, snapshot.activeConversationId)
        }
        val now = clock()
        val resolvedAssistant = AssistantCatalog.resolve(assistantSelection)
        val conversation = ChatConversation(
            id = idFactory(),
            selectedProfileId = selectedProfileId,
            selectedModel = selectedModel,
            executionMode = executionMode,
            selectedSkillId = resolvedAssistant.skillId,
            selectedPersonaId = resolvedAssistant.personaId,
            createdAtMs = now,
            updatedAtMs = now,
        )
        return snapshot.copy(
            conversations = snapshot.conversations + conversation,
            activeConversationId = conversation.id,
        )
    }

    fun list(snapshot: ConversationSnapshot): List<ConversationSummary> = snapshot.summaries

    fun get(snapshot: ConversationSnapshot, id: String): ChatConversation? =
        snapshot.conversations.firstOrNull { it.id == id }

    fun update(
        snapshot: ConversationSnapshot,
        conversation: ChatConversation,
    ): ConversationSnapshot {
        require(snapshot.conversations.any { it.id == conversation.id }) {
            "Conversation does not exist"
        }
        return snapshot.copy(
            conversations = snapshot.conversations.map { current ->
                if (current.id == conversation.id) conversation else current
            },
        )
    }

    fun rename(
        snapshot: ConversationSnapshot,
        id: String,
        title: String,
    ): ConversationSnapshot {
        val conversation = get(snapshot, id) ?: return snapshot
        return update(
            snapshot,
            conversation.copy(
                title = ConversationText.renamedTitle(title),
                updatedAtMs = clock(),
            ),
        )
    }

    fun select(snapshot: ConversationSnapshot, id: String): ConversationSnapshot {
        val selected = get(snapshot, id) ?: return snapshot
        val updated = if (selected.hasUnreadCompletion) {
            update(snapshot, selected.copy(hasUnreadCompletion = false))
        } else {
            snapshot
        }
        return updated.copy(activeConversationId = id)
    }

    fun delete(snapshot: ConversationSnapshot, id: String): ConversationSnapshot {
        if (snapshot.conversations.none { it.id == id }) return snapshot
        val remaining = snapshot.conversations.filterNot { it.id == id }
        if (remaining.isEmpty()) return initialSnapshot()
        val nextActiveId = if (snapshot.activeConversationId == id) {
            remaining.maxBy { it.updatedAtMs }.id
        } else {
            snapshot.activeConversationId
        }
        return snapshot.copy(
            conversations = remaining,
            activeConversationId = nextActiveId,
        )
    }

    fun requestPersistence(revision: Long) {
        synchronized(revisionLock) {
            if (revision > latestRequestedRevision) latestRequestedRevision = revision
        }
    }

    suspend fun persist(snapshot: ConversationSnapshot, revision: Long) {
        val target = storage ?: return
        withContext(ioDispatcher) {
            persistenceMutex.withLock {
                if (revision < requestedRevision()) return@withLock
                val currentHashes = snapshot.conversations.associate { conversation ->
                    conversation.id to sha256(ConversationCodec.encodeConversation(conversation))
                }
                snapshot.conversations.forEach { conversation ->
                    if (persistedConversationHashes[conversation.id] != currentHashes[conversation.id]) {
                        target.writeConversation(conversation)
                    }
                }
                (persistedConversationHashes.keys - currentHashes.keys).forEach(
                    target::deleteConversation,
                )
                val index = snapshot.toIndex()
                val indexHash = sha256(ConversationCodec.encodeIndex(index))
                if (persistedIndexHash != indexHash) target.writeIndex(index)
                persistedConversationHashes = currentHashes
                persistedIndexHash = indexHash
            }
        }
    }

    private fun requestedRevision(): Long = synchronized(revisionLock) {
        latestRequestedRevision
    }

    private fun ConversationSnapshot.toIndex(): ConversationIndex = ConversationIndex(
        activeConversationId = activeConversationId,
        summaries = summaries,
    )

    private fun normalizeAssistantMode(conversation: ChatConversation): ChatConversation {
        val selection = AssistantCatalog.resolve(conversation.assistantSelection)
        val messages = conversation.messages.map { message ->
            message.copy(
                assistantSkillId = message.assistantSkillId?.let(AssistantCatalog::skill)?.id,
                assistantPersonaId = message.assistantPersonaId
                    ?.let(AssistantCatalog::persona)
                    ?.id,
            )
        }
        return conversation.copy(
            messages = messages,
            selectedSkillId = selection.skillId,
            selectedPersonaId = selection.personaId,
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
