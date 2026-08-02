package dev.alpine.chat.feature.data

import dev.alpine.chat.routing.ChatExecutionMode
import dev.alpine.chat.feature.model.AssistantSelection
import dev.alpine.chat.feature.model.ChatConversation
import dev.alpine.chat.feature.model.ChatMessage
import dev.alpine.chat.feature.model.ChatMessageState
import dev.alpine.chat.feature.model.ChatRole
import dev.alpine.chat.feature.model.ConversationGenerationState
import dev.alpine.chat.feature.model.ConversationText
import javax.crypto.KeyGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationDataTest {
    @Test
    fun `conversation and message codec round trip all persisted fields`() {
        val conversation = ChatConversation(
            id = "conversation-1",
            title = "Saved title",
            messages = listOf(
                ChatMessage(
                    id = "message-1",
                    role = ChatRole.USER,
                    text = "hello",
                    createdAtMs = 11,
                ),
                ChatMessage(
                    id = "message-2",
                    role = ChatRole.ASSISTANT,
                    text = "answer",
                    state = ChatMessageState.CANCELLED,
                    providerProfileId = "provider-1",
                    providerLabel = "Provider",
                    model = "model-1",
                    assistantSkillId = "coding",
                    assistantPersonaId = "expert_engineer",
                    createdAtMs = 12,
                ),
            ),
            draft = "next draft",
            selectedProfileId = "provider-1",
            selectedModel = "model-2",
            executionMode = ChatExecutionMode.ALPINE_WORKSPACE,
            selectedSkillId = "debugging",
            selectedPersonaId = "step_by_step",
            generationState = ConversationGenerationState.CANCELLED,
            hasUnreadCompletion = true,
            createdAtMs = 10,
            updatedAtMs = 20,
        )

        val decoded = ConversationCodec.decodeConversation(
            ConversationCodec.encodeConversation(conversation),
        )

        assertEquals(conversation, decoded)
        val index = ConversationIndex(conversation.id, listOf(conversation.summary()))
        assertEquals(index, ConversationCodec.decodeIndex(ConversationCodec.encodeIndex(index)))
    }

    @Test
    fun `legacy schema one migrates to safe assistant defaults`() {
        val current = ChatConversation(
            id = "legacy-conversation",
            messages = listOf(
                ChatMessage(
                    id = "legacy-assistant",
                    role = ChatRole.ASSISTANT,
                    text = "saved",
                    assistantSkillId = "coding",
                    assistantPersonaId = "concise",
                    createdAtMs = 2,
                ),
            ),
            selectedSkillId = "coding",
            selectedPersonaId = "concise",
            createdAtMs = 1,
            updatedAtMs = 2,
        )
        val legacy = JSONObject(
            ConversationCodec.encodeConversation(current).toString(Charsets.UTF_8),
        ).apply {
            put("schema", 1)
            remove("selectedSkillId")
            remove("selectedPersonaId")
            getJSONArray("messages").getJSONObject(0).apply {
                remove("assistantSkillId")
                remove("assistantPersonaId")
            }
        }

        val migrated = ConversationCodec.decodeConversation(legacy.toString().toByteArray())

        assertEquals(AssistantSelection.DEFAULT_SKILL_ID, migrated.selectedSkillId)
        assertEquals(AssistantSelection.DEFAULT_PERSONA_ID, migrated.selectedPersonaId)
        assertNull(migrated.messages.single().assistantSkillId)
        assertNull(migrated.messages.single().assistantPersonaId)
        assertEquals(ChatExecutionMode.FAST_CHAT, migrated.executionMode)
    }

    @Test
    fun `schema two and legacy index migrate execution mode to fast chat`() {
        val current = ChatConversation(
            id = "legacy-mode",
            selectedSkillId = "coding",
            selectedPersonaId = "concise",
            executionMode = ChatExecutionMode.ALPINE_WORKSPACE,
            createdAtMs = 1,
            updatedAtMs = 2,
        )
        val legacyConversation = JSONObject(
            ConversationCodec.encodeConversation(current).toString(Charsets.UTF_8),
        ).apply {
            put("schema", 2)
            remove("executionMode")
        }
        val legacyIndex = JSONObject(
            ConversationCodec.encodeIndex(
                ConversationIndex(current.id, listOf(current.summary())),
            ).toString(Charsets.UTF_8),
        ).apply {
            put("schema", 1)
            getJSONArray("summaries").getJSONObject(0).remove("executionMode")
        }

        val migrated = ConversationCodec.decodeConversation(legacyConversation.toString().toByteArray())
        val migratedIndex = ConversationCodec.decodeIndex(legacyIndex.toString().toByteArray())

        assertEquals(ChatExecutionMode.FAST_CHAT, migrated.executionMode)
        assertEquals("coding", migrated.selectedSkillId)
        assertEquals("concise", migrated.selectedPersonaId)
        assertEquals(ChatExecutionMode.FAST_CHAT, migratedIndex.summaries.single().executionMode)
    }

    @Test
    fun `title normalization handles blank whitespace unicode and code point limit`() {
        assertEquals(ChatConversation.DEFAULT_TITLE, ConversationText.automaticTitle(" \n\t "))
        assertEquals("hello world again", ConversationText.automaticTitle(" hello\n world\t again "))
        val emoji = "😀".repeat(80)
        val title = ConversationText.automaticTitle(emoji)
        assertEquals(ConversationText.MAX_TITLE_CODE_POINTS + 1, title.codePointCount(0, title.length))
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `codec rejects unknown enum malformed json and oversized payload`() {
        val valid = ChatConversation(id = "valid-id", createdAtMs = 1, updatedAtMs = 1)
        val unknown = JSONObject(
            ConversationCodec.encodeConversation(valid).toString(Charsets.UTF_8),
        ).put("generationState", "UNKNOWN_FUTURE_STATE")

        assertTrue(
            runCatching {
                ConversationCodec.decodeConversation(unknown.toString().toByteArray())
            }.isFailure,
        )
        assertTrue(runCatching { ConversationCodec.decodeConversation("{".toByteArray()) }.isFailure)
        assertTrue(
            runCatching {
                ConversationCodec.decodeConversation(
                    ByteArray(ConversationCodec.MAX_CONVERSATION_BYTES + 1),
                )
            }.isFailure,
        )
    }

    @Test
    fun `AES GCM uses unique nonce and rejects tamper and wrong key`() {
        val keyGenerator = KeyGenerator.getInstance("AES").apply { init(256) }
        val firstCipher = AesGcmConversationCipher(keyGenerator.generateKey())
        val secondCipher = AesGcmConversationCipher(keyGenerator.generateKey())
        val plaintext = "private conversation payload".toByteArray()

        val first = firstCipher.encrypt(plaintext)
        val second = firstCipher.encrypt(plaintext)

        assertNotEquals(first.toList(), second.toList())
        assertFalse(first.toString(Charsets.UTF_8).contains("private conversation"))
        assertEquals(plaintext.toList(), firstCipher.decrypt(first).toList())
        val tampered = first.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertTrue(runCatching { firstCipher.decrypt(tampered) }.isFailure)
        assertTrue(runCatching { secondCipher.decrypt(first) }.isFailure)
    }

    @Test
    fun `repository isolates conversations drafts providers and deletion boundaries`() {
        var now = 10L
        val ids = ArrayDeque(listOf("conversation-a", "conversation-b", "conversation-c"))
        val repository = ConversationRepository(
            clock = { now++ },
            idFactory = { ids.removeFirst() },
        )
        var snapshot = repository.initialSnapshot("provider-a", "model-a")
        val firstId = snapshot.activeConversationId

        snapshot = repository.create(snapshot, "provider-b", "model-b")
        assertEquals(firstId, snapshot.activeConversationId)
        snapshot = repository.create(snapshot, "provider-b", "model-b")
        assertEquals(listOf(firstId), snapshot.conversations.map { it.id })

        snapshot = repository.update(
            snapshot,
            snapshot.activeConversation.copy(
                messages = listOf(ChatMessage(id = "user-a", role = ChatRole.USER, text = "A")),
                draft = "draft-a",
                executionMode = ChatExecutionMode.ALPINE_WORKSPACE,
            ),
        )
        snapshot = repository.create(snapshot, "provider-b", "model-b")
        val secondId = snapshot.activeConversationId
        snapshot = repository.update(snapshot, snapshot.activeConversation.copy(draft = "draft-b"))
        snapshot = repository.select(snapshot, firstId)

        assertEquals("draft-a", snapshot.activeConversation.draft)
        assertEquals("provider-a", snapshot.activeConversation.selectedProfileId)
        assertEquals(ChatExecutionMode.ALPINE_WORKSPACE, snapshot.activeConversation.executionMode)
        assertEquals("draft-b", repository.get(snapshot, secondId)?.draft)
        snapshot = repository.rename(snapshot, firstId, " Renamed\n chat ")
        assertEquals("Renamed chat", snapshot.activeConversation.title)

        snapshot = repository.delete(snapshot, firstId)
        assertEquals(secondId, snapshot.activeConversationId)
        snapshot = repository.delete(snapshot, secondId)
        assertEquals(1, snapshot.conversations.size)
        assertNotEquals(secondId, snapshot.activeConversationId)
    }

    @Test
    fun `repository normalizes interrupted streams and persists changed files only`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val streaming = ChatConversation(
            id = "interrupted",
            messages = listOf(
                ChatMessage(
                    id = "assistant-stream",
                    role = ChatRole.ASSISTANT,
                    text = "partial",
                    state = ChatMessageState.STREAMING,
                ),
            ),
            generationState = ConversationGenerationState.STREAMING,
            createdAtMs = 1,
            updatedAtMs = 2,
        )
        val storage = FakeStorage(
            ConversationLoadResult(listOf(streaming), streaming.id),
        )
        val repository = ConversationRepository(
            storage = storage,
            ioDispatcher = dispatcher,
            clock = { 100L },
            idFactory = { "fallback" },
        )

        val loaded = repository.load()
        assertEquals(ConversationGenerationState.CANCELLED, loaded.activeConversation.generationState)
        assertEquals(ChatMessageState.CANCELLED, loaded.activeConversation.messages.single().state)

        repository.requestPersistence(1)
        repository.persist(loaded, 1)
        assertEquals(listOf("interrupted"), storage.writtenConversations.map { it.id })
        assertNotNull(storage.writtenIndex)

        repository.requestPersistence(2)
        repository.persist(loaded, 2)
        assertEquals(1, storage.writtenConversations.size)
    }

    @Test
    fun `repository normalizes removed assistant catalog ids on load`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val removed = ChatConversation(
            id = "removed-assistant-mode",
            messages = listOf(
                ChatMessage(
                    id = "removed-mode-message",
                    role = ChatRole.ASSISTANT,
                    text = "saved",
                    assistantSkillId = "removed_skill",
                    assistantPersonaId = "removed_persona",
                    createdAtMs = 1,
                ),
            ),
            selectedSkillId = "removed_skill",
            selectedPersonaId = "removed_persona",
            createdAtMs = 1,
            updatedAtMs = 1,
        )
        val repository = ConversationRepository(
            storage = FakeStorage(ConversationLoadResult(listOf(removed), removed.id)),
            ioDispatcher = dispatcher,
        )

        val loaded = repository.load().activeConversation

        assertEquals(AssistantSelection.DEFAULT_SKILL_ID, loaded.selectedSkillId)
        assertEquals(AssistantSelection.DEFAULT_PERSONA_ID, loaded.selectedPersonaId)
        assertEquals(AssistantSelection.DEFAULT_SKILL_ID, loaded.messages.single().assistantSkillId)
        assertEquals(
            AssistantSelection.DEFAULT_PERSONA_ID,
            loaded.messages.single().assistantPersonaId,
        )
    }

    @Test
    fun `repository persistence restores active conversation draft selection and sort order`() =
        runTest {
            var now = 10L
            val dispatcher = StandardTestDispatcher(testScheduler)
            val ids = ArrayDeque(listOf("persistent-a", "persistent-b"))
            val storage = FakeStorage(ConversationLoadResult(emptyList(), null))
            val repository = ConversationRepository(
                storage = storage,
                ioDispatcher = dispatcher,
                clock = { now++ },
                idFactory = { ids.removeFirst() },
            )
            var snapshot = repository.initialSnapshot("provider-a", "model-a")
            snapshot = repository.update(
                snapshot,
                snapshot.activeConversation.copy(
                    messages = listOf(
                        ChatMessage(id = "persistent-message", role = ChatRole.USER, text = "A"),
                    ),
                    draft = "draft-a",
                    executionMode = ChatExecutionMode.ALPINE_WORKSPACE,
                    updatedAtMs = 20,
                ),
            )
            snapshot = repository.create(snapshot, "provider-b", "model-b")
            val secondId = snapshot.activeConversationId
            snapshot = repository.update(
                snapshot,
                snapshot.activeConversation.copy(draft = "draft-b", updatedAtMs = 30),
            )
            repository.requestPersistence(1)
            repository.persist(snapshot, 1)

            val persisted = storage.writtenConversations.associateBy { it.id }.values.toList()
            val reloaded = ConversationRepository(
                storage = FakeStorage(ConversationLoadResult(persisted, storage.writtenIndex?.activeConversationId)),
                ioDispatcher = dispatcher,
                clock = { 100L },
                idFactory = { "unused-fallback" },
            ).load()

            assertEquals(secondId, reloaded.activeConversationId)
            assertEquals("draft-b", reloaded.activeConversation.draft)
            assertEquals("provider-b", reloaded.activeConversation.selectedProfileId)
            assertEquals("model-b", reloaded.activeConversation.selectedModel)
            assertEquals(ChatExecutionMode.ALPINE_WORKSPACE, reloaded.activeConversation.executionMode)
            assertEquals(listOf(secondId, "persistent-a"), reloaded.summaries.map { it.id })
        }

    private class FakeStorage(
        private val loadResult: ConversationLoadResult,
    ) : ConversationStorage {
        val writtenConversations = mutableListOf<ChatConversation>()
        var writtenIndex: ConversationIndex? = null
        val deletedIds = mutableListOf<String>()

        override fun load(): ConversationLoadResult = loadResult

        override fun writeConversation(conversation: ChatConversation) {
            writtenConversations += conversation
        }

        override fun writeIndex(index: ConversationIndex) {
            writtenIndex = index
        }

        override fun deleteConversation(id: String) {
            deletedIds += id
        }
    }
}
