package dev.alpine.chat.feature.ui

import dev.alpine.chat.feature.backend.ChatBackendConnection
import dev.alpine.chat.feature.backend.ChatBackendConnectionState
import dev.alpine.chat.feature.backend.ChatBackendDelta
import dev.alpine.chat.feature.backend.ChatBackendDescriptor
import dev.alpine.chat.feature.backend.ChatBackendRequestContext
import dev.alpine.chat.feature.backend.ChatBackendRequestPolicy
import dev.alpine.chat.feature.backend.ChatBackendSession
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.chat.feature.backend.ContextualChatBackendSession
import dev.alpine.chat.feature.data.ConversationRepository
import dev.alpine.chat.feature.model.ConversationGenerationState
import dev.alpine.chat.feature.model.ChatRole
import dev.alpine.chat.feature.model.ChatMessageState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatGenerationStateTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `background generations stay independent and capacity follows active jobs`() = runTest(
        context = dispatcher,
    ) {
        val ids = listOf("conversation-a", "conversation-b", "conversation-c").iterator()
        val repository = ConversationRepository(
            clock = { 100L },
            idFactory = ids::next,
        )
        val session = BlockingSession()
        val viewModel = ChatViewModel(repository)
        viewModel.updateConnections(
            listOf(
                ChatBackendConnection(
                    descriptor = session.descriptor,
                    state = ChatBackendConnectionState.AVAILABLE,
                    session = session,
                ),
            ),
        )

        viewModel.send("first request", session)
        runCurrent()
        assertEquals(1, viewModel.state.value.activeGenerationCount)
        assertEquals(0, viewModel.state.value.backgroundGenerationCount)
        assertTrue(viewModel.state.value.isStreaming)

        viewModel.newConversation()
        assertEquals("conversation-b", viewModel.state.value.activeConversationId)
        assertFalse(viewModel.state.value.isStreaming)
        assertEquals(1, viewModel.state.value.backgroundGenerationCount)
        assertTrue(viewModel.state.value.generationCapacityAvailable)

        viewModel.send("second request", session)
        runCurrent()
        assertEquals(2, viewModel.state.value.activeGenerationCount)
        assertEquals(1, viewModel.state.value.backgroundGenerationCount)
        assertFalse(viewModel.state.value.generationCapacityAvailable)

        viewModel.newConversation()
        viewModel.updateDraft("third request stays as a draft")
        assertEquals("conversation-c", viewModel.state.value.activeConversationId)
        assertFalse(viewModel.state.value.isStreaming)
        assertEquals(2, viewModel.state.value.backgroundGenerationCount)
        assertFalse(viewModel.state.value.generationCapacityAvailable)
        assertEquals(
            "동시에 최대 2개 대화까지 답변을 생성할 수 있습니다.",
            viewModel.state.value.statusMessage,
        )

        viewModel.send("must not dispatch", session)
        runCurrent()
        assertEquals(2, session.requestCount.get())
        assertEquals("third request stays as a draft", viewModel.state.value.draft)

        viewModel.stopStreaming("conversation-a")
        runCurrent()
        val afterFirstStop = viewModel.state.value
        assertEquals("conversation-c", afterFirstStop.activeConversationId)
        assertEquals(1, afterFirstStop.activeGenerationCount)
        assertEquals(1, afterFirstStop.backgroundGenerationCount)
        assertTrue(afterFirstStop.generationCapacityAvailable)
        assertEquals(
            ConversationGenerationState.CANCELLED,
            afterFirstStop.conversations.single { it.id == "conversation-a" }.generationState,
        )
        assertEquals(
            ConversationGenerationState.STREAMING,
            afterFirstStop.conversations.single { it.id == "conversation-b" }.generationState,
        )

        viewModel.stopStreaming("conversation-b")
        runCurrent()
        assertEquals(0, viewModel.state.value.activeGenerationCount)
        assertEquals(0, viewModel.state.value.backgroundGenerationCount)
    }

    @Test
    fun `profile stop waits for matching cleanup and preserves unrelated background stream`() = runTest(
        context = dispatcher,
    ) {
        val ids = listOf("conversation-a", "conversation-b").iterator()
        val repository = ConversationRepository(clock = { 100L }, idFactory = ids::next)
        val codex = BlockingSession(profileId = "codex")
        val direct = BlockingSession(profileId = "direct")
        val viewModel = ChatViewModel(repository)
        viewModel.updateConnections(
            listOf(
                ChatBackendConnection(codex.descriptor, ChatBackendConnectionState.AVAILABLE, codex),
                ChatBackendConnection(direct.descriptor, ChatBackendConnectionState.AVAILABLE, direct),
            ),
        )

        viewModel.send("codex request", codex)
        viewModel.newConversation()
        viewModel.selectProvider("direct")
        viewModel.send("direct request", direct)
        runCurrent()
        assertEquals(2, viewModel.state.value.activeGenerationCount)

        var cleanupFinished = false
        viewModel.stopStreamingByProfile("codex") { cleanupFinished = true }
        assertFalse(cleanupFinished)
        runCurrent()

        assertTrue(cleanupFinished)
        assertEquals(1, viewModel.state.value.activeGenerationCount)
        assertEquals(
            ConversationGenerationState.CANCELLED,
            viewModel.state.value.conversations.single { it.id == "conversation-a" }.generationState,
        )
        assertEquals(
            ConversationGenerationState.STREAMING,
            viewModel.state.value.conversations.single { it.id == "conversation-b" }.generationState,
        )
        viewModel.stopStreamingByProfile("direct") {}
        runCurrent()
    }

    @Test
    fun `model changes during a stream apply only to the next request`() = runTest(
        context = dispatcher,
    ) {
        val repository = ConversationRepository(
            clock = { 100L },
            idFactory = { "conversation" },
        )
        val session = BlockingSession()
        val viewModel = ChatViewModel(repository)
        viewModel.updateConnections(
            listOf(
                ChatBackendConnection(
                    descriptor = session.descriptor,
                    state = ChatBackendConnectionState.AVAILABLE,
                    session = session,
                ),
            ),
        )

        viewModel.send("request", session)
        runCurrent()
        viewModel.selectModel("provider", "model-b")
        viewModel.selectAssistantMode("coding", "concise")

        assertEquals("model-b", viewModel.state.value.selectedModel)
        assertEquals("coding", viewModel.state.value.selectedSkillId)
        assertEquals("concise", viewModel.state.value.selectedPersonaId)
        val streamingAssistant =
            viewModel.state.value.messages.single { it.role == ChatRole.ASSISTANT }
        assertEquals("model-a", streamingAssistant.model)
        assertEquals("general", streamingAssistant.assistantSkillId)
        assertEquals("balanced", streamingAssistant.assistantPersonaId)
        assertTrue(viewModel.state.value.isStreaming)

        viewModel.stopStreaming()
        runCurrent()
        assertFalse(viewModel.state.value.isStreaming)
    }

    @Test
    fun `unavailable model is preserved and blocks dispatch until explicit selection`() = runTest(
        context = dispatcher,
    ) {
        val repository = ConversationRepository(
            clock = { 100L },
            idFactory = { "conversation" },
        )
        val session = ScriptedSession("model-a")
        val viewModel = ChatViewModel(repository)
        viewModel.updateConnections(
            listOf(
                ChatBackendConnection(
                    descriptor = session.descriptor,
                    state = ChatBackendConnectionState.AVAILABLE,
                    session = session,
                ),
            ),
        )
        viewModel.selectModel("provider", "model-b")
        assertEquals("model-b", viewModel.state.value.selectedModel)

        val modelARemains = session.descriptor.copy(
            model = "model-a",
            modelOptions = listOf("model-a"),
        )
        session.updateDescriptor(modelARemains)
        viewModel.updateConnections(
            listOf(
                ChatBackendConnection(
                    descriptor = modelARemains,
                    state = ChatBackendConnectionState.AVAILABLE,
                    session = session,
                ),
            ),
        )

        assertEquals("model-b", viewModel.state.value.selectedModel)
        assertEquals("model-b", viewModel.state.value.unavailableModel)
        viewModel.send("must not dispatch", session)
        runCurrent()
        assertEquals(0, session.requestCount.get())

        viewModel.selectModel("provider", "model-a")
        assertEquals(null, viewModel.state.value.unavailableModel)
        viewModel.send("explicitly selected", session)
        runCurrent()
        assertEquals(1, session.requestCount.get())
    }

    @Test
    fun `unavailable retry target is not replayed and replacement model sends only a new request`() = runTest(
        context = dispatcher,
    ) {
        val repository = ConversationRepository(
            clock = { 100L },
            idFactory = { "conversation" },
        )
        val session = ScriptedSession("model-b")
        val viewModel = ChatViewModel(repository)
        viewModel.updateConnections(
            listOf(
                ChatBackendConnection(
                    descriptor = session.descriptor,
                    state = ChatBackendConnectionState.AVAILABLE,
                    session = session,
                ),
            ),
        )
        viewModel.send("turn-6", session)
        runCurrent()
        assertEquals(1, session.requestCount.get())
        assertTrue(viewModel.state.value.failure != null)

        val modelARemains = session.descriptor.copy(
            model = "model-a",
            modelOptions = listOf("model-a"),
        )
        session.updateDescriptor(modelARemains)
        viewModel.updateConnections(
            listOf(
                ChatBackendConnection(
                    descriptor = modelARemains,
                    state = ChatBackendConnectionState.AVAILABLE,
                    session = session,
                ),
            ),
        )
        assertEquals("model-b", viewModel.state.value.unavailableModel)

        viewModel.retry()
        runCurrent()
        assertEquals(1, session.requestCount.get())

        viewModel.selectModel("provider", "model-a")
        viewModel.send("replacement request", session)
        runCurrent()
        assertEquals(2, session.requestCount.get())
    }

    @Test
    fun `missing profile still falls back while unavailable model does not`() = runTest(
        context = dispatcher,
    ) {
        val repository = ConversationRepository(
            clock = { 100L },
            idFactory = { "conversation" },
        )
        val first = CompletingSession("profile-a", "model-a")
        val second = CompletingSession("profile-b", "model-b")
        val viewModel = ChatViewModel(repository)
        viewModel.updateConnections(
            listOf(first.connection(), second.connection()),
        )
        viewModel.selectProvider("profile-b")
        assertEquals("profile-b", viewModel.state.value.selectedProfileId)

        viewModel.updateConnections(listOf(first.connection()))

        assertEquals("profile-a", viewModel.state.value.selectedProfileId)
        assertEquals("model-a", viewModel.state.value.selectedModel)
        assertEquals(null, viewModel.state.value.unavailableModel)
    }

    @Test
    fun `background completion becomes unread until its conversation is selected`() = runTest(
        context = dispatcher,
    ) {
        val ids = listOf("conversation-a", "conversation-b").iterator()
        val repository = ConversationRepository(
            clock = { 100L },
            idFactory = ids::next,
        )
        val session = CompletingSession()
        val viewModel = ChatViewModel(repository)
        viewModel.updateConnections(
            listOf(
                ChatBackendConnection(
                    descriptor = session.descriptor,
                    state = ChatBackendConnectionState.AVAILABLE,
                    session = session,
                ),
            ),
        )

        viewModel.send("finish in background", session)
        viewModel.newConversation()
        runCurrent()

        val completed = viewModel.state.value.conversations.single { it.id == "conversation-a" }
        assertEquals(ConversationGenerationState.COMPLETE, completed.generationState)
        assertTrue(completed.hasUnreadCompletion)
        assertEquals("conversation-b", viewModel.state.value.activeConversationId)

        viewModel.selectConversation("conversation-a")
        assertFalse(
            viewModel.state.value.conversations
                .single { it.id == "conversation-a" }
                .hasUnreadCompletion,
        )
    }

    @Test
    fun `contextual backend gets conversation identity and opts out of correction replay`() = runTest(
        context = dispatcher,
    ) {
        val repository = ConversationRepository(
            clock = { 100L },
            idFactory = { "conversation-context" },
        )
        val session = NoReplayContextualSession()
        val viewModel = ChatViewModel(repository)
        viewModel.updateConnections(listOf(session.connection()))

        viewModel.send("Reply in one sentence.", session)
        runCurrent()

        assertEquals(1, session.requests.size)
        assertEquals("conversation-context", session.requests.single().conversationId)
        assertTrue(session.requests.single().actionId.isNotBlank())
        assertEquals(ConversationGenerationState.COMPLETE, activeConversation(viewModel).generationState)
    }

    @Test
    fun `ten turn fake provider regression preserves model assistant selection stop retry and history`() = runTest(
        context = dispatcher,
    ) {
        val ids = (1..8).map { "conversation-$it" }.iterator()
        val repository = ConversationRepository(clock = { 100L }, idFactory = ids::next)
        val modelA = ScriptedSession("model-a")
        val modelB = ScriptedSession("model-b")
        val viewModel = ChatViewModel(repository)
        viewModel.updateConnections(
            listOf(
                ChatBackendConnection(modelA.descriptor, ChatBackendConnectionState.AVAILABLE, modelA),
                ChatBackendConnection(modelB.descriptor, ChatBackendConnectionState.AVAILABLE, modelB),
            ),
        )
        val firstConversationId = viewModel.state.value.activeConversationId

        (1..10).forEach { turn ->
            val useModelB = turn % 2 == 0
            val model = if (useModelB) "model-b" else "model-a"
            val session = if (useModelB) modelB else modelA
            viewModel.selectModel("provider", model)
            if (turn % 2 == 0) {
                viewModel.selectAssistantMode("coding", "concise")
            } else {
                viewModel.selectAssistantMode("general", "balanced")
            }
            val prompt = "turn-$turn"
            viewModel.send(prompt, session)
            runCurrent()
            when (turn) {
                4 -> {
                    viewModel.stopStreaming()
                    runCurrent()
                    assertEquals(ConversationGenerationState.CANCELLED, activeConversation(viewModel).generationState)
                    // A user can leave an interrupted conversation immediately and continue a
                    // new task while the cancelled stream finishes its cooperative cleanup.
                    viewModel.newConversation()
                }
                6 -> {
                    assertTrue(viewModel.state.value.failure != null)
                    viewModel.retry()
                    runCurrent()
                    assertEquals(ConversationGenerationState.COMPLETE, activeConversation(viewModel).generationState)
                }
                else -> assertEquals(
                    "turn $turn",
                    ConversationGenerationState.COMPLETE,
                    activeConversation(viewModel).generationState,
                )
            }
            if (turn in setOf(3, 6, 9)) viewModel.newConversation()
        }

        val conversationIds = viewModel.state.value.conversations.map { it.id }
        val allUsers = conversationIds.sumOf { conversationId ->
            // Selecting each historical row proves that history navigation does not discard the
            // per-conversation messages while a later turn changes model/persona/skill.
            viewModel.selectConversation(conversationId)
            viewModel.state.value.messages.count { it.role == ChatRole.USER }
        }
        assertEquals(10, allUsers)
        assertEquals(11, modelA.requestCount.get() + modelB.requestCount.get()) // turn 6 retry
        assertTrue(modelA.requestJson.any { it.contains("turn-1") })
        assertTrue(modelB.requestJson.any { it.contains("turn-10") })

        viewModel.selectConversation(firstConversationId)
        assertEquals(firstConversationId, viewModel.state.value.activeConversationId)
        assertTrue(viewModel.state.value.messages.any { it.text == "turn-1" && it.role == ChatRole.USER })
        assertEquals(ChatMessageState.COMPLETE, viewModel.state.value.messages.last().state)

        // The active conversation still records the assistant policy that was selected for its
        // own request; later mode changes did not mutate historic messages.
        val assistant = viewModel.state.value.messages.last()
        assertEquals("general", assistant.assistantSkillId)
        assertEquals("balanced", assistant.assistantPersonaId)
    }

    private class BlockingSession(profileId: String = "provider") : ChatBackendSession {
        override val descriptor = ChatBackendDescriptor(
            profileId = profileId,
            label = "Provider",
            model = "model-a",
            modelOptions = listOf("model-a", "model-b"),
        )
        val requestCount = AtomicInteger()

        override suspend fun stream(requestJson: String): ChatBackendStreamResult {
            requestCount.incrementAndGet()
            return ChatBackendStreamResult(
                events = flow {
                    emit(ChatBackendDelta("partial"))
                    awaitCancellation()
                },
            )
        }
    }

    private class NoReplayContextualSession :
        ContextualChatBackendSession,
        ChatBackendRequestPolicy {
        override val descriptor = ChatBackendDescriptor(
            profileId = "contextual",
            label = "Contextual",
            model = "model",
        )
        override val allowsAutomaticCorrection = false
        val requests = mutableListOf<ChatBackendRequestContext>()

        fun connection() = ChatBackendConnection(
            descriptor,
            ChatBackendConnectionState.AVAILABLE,
            this,
        )

        override suspend fun stream(request: ChatBackendRequestContext): ChatBackendStreamResult {
            requests += request
            return ChatBackendStreamResult(
                events = flowOf(ChatBackendDelta("First. Second.")),
            )
        }
    }

    private class CompletingSession(
        profileId: String = "provider",
        model: String = "model",
    ) : ChatBackendSession {
        override val descriptor = ChatBackendDescriptor(
            profileId = profileId,
            label = "Provider",
            model = model,
        )

        fun connection() = ChatBackendConnection(
            descriptor = descriptor,
            state = ChatBackendConnectionState.AVAILABLE,
            session = this,
        )

        override suspend fun stream(requestJson: String) = ChatBackendStreamResult(
            events = flowOf(ChatBackendDelta("complete")),
        )
    }

    private class ScriptedSession(model: String) : ChatBackendSession {
        override var descriptor = ChatBackendDescriptor(
            profileId = "provider",
            label = "Fake Provider",
            model = model,
            modelOptions = listOf("model-a", "model-b"),
        )
        val requestCount = AtomicInteger()
        val requestJson = mutableListOf<String>()
        private var failedTurnSixOnce = false

        fun updateDescriptor(descriptor: ChatBackendDescriptor) {
            this.descriptor = descriptor
        }

        override suspend fun stream(requestJson: String): ChatBackendStreamResult {
            this.requestJson += requestJson
            requestCount.incrementAndGet()
            return when {
                requestJson.contains("turn-4") -> ChatBackendStreamResult(
                    events = flow {
                        emit(ChatBackendDelta("turn-4 partial"))
                        awaitCancellation()
                    },
                )
                requestJson.contains("turn-6") && !failedTurnSixOnce -> {
                    failedTurnSixOnce = true
                    ChatBackendStreamResult(statusCode = 503)
                }
                else -> ChatBackendStreamResult(events = flowOf(ChatBackendDelta("fake complete")))
            }
        }
    }

    private fun activeConversation(viewModel: ChatViewModel) = viewModel.state.value.conversations.single {
        it.id == viewModel.state.value.activeConversationId
    }
}
