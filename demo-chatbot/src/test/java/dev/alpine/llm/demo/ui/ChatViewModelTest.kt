package dev.alpine.llm.demo.ui

import android.app.Activity
import dev.alpine.chat.routing.ChatExecutionMode
import dev.alpine.llm.HostLlmStreamEvent
import dev.alpine.llm.HostLlmStreamResult
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.OAuthException
import dev.alpine.llm.OAuthFailureKind
import dev.alpine.llm.ProviderCircuitOpenException
import dev.alpine.llm.ProviderStreamException
import dev.alpine.llm.demo.llm.ChatCompletionSession
import dev.alpine.llm.demo.llm.ProviderConnection
import dev.alpine.llm.demo.llm.ProviderConnectionState
import dev.alpine.llm.demo.model.AssistantSelection
import dev.alpine.llm.demo.model.ChatMessageState
import dev.alpine.llm.demo.model.ChatRole
import dev.alpine.llm.demo.model.ProviderProfile
import dev.alpine.llm.demo.model.ProviderType
import dev.alpine.llm.demo.ui.state.ChatFailureKind
import dev.alpine.llm.demo.ui.state.ChatRecoveryAction
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
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
    fun `execution mode is conversation scoped and inherited by a new chat`() =
        runTest(dispatcher) {
            val viewModel = ChatViewModel()

            viewModel.selectExecutionMode(ChatExecutionMode.ALPINE_WORKSPACE)
            assertEquals(ChatExecutionMode.ALPINE_WORKSPACE, viewModel.state.value.executionMode)
            viewModel.updateDraft("workspace conversation")
            viewModel.newConversation()

            assertEquals(ChatExecutionMode.ALPINE_WORKSPACE, viewModel.state.value.executionMode)
            viewModel.selectExecutionMode(ChatExecutionMode.FAST_CHAT)
            assertEquals(ChatExecutionMode.FAST_CHAT, viewModel.state.value.executionMode)
        }

    @Test
    fun `provider switch sends only through selected session and fixes assistant metadata`() =
        runTest(dispatcher) {
            val first = FakeSession.success(profile("first", ProviderType.ANTHROPIC), "Claude answer")
            val second = FakeSession.success(profile("second", ProviderType.GEMINI), "Gemini answer")
            val viewModel = ChatViewModel()
            viewModel.updateConnections(listOf(first.connected(), second.connected()))

            viewModel.send("Question one", first)
            advanceUntilIdle()
            viewModel.selectProvider(second.profile.id)
            viewModel.send("Question two", second)
            advanceUntilIdle()

            assertEquals(1, first.requests.size)
            assertEquals(1, second.requests.size)
            val assistants = viewModel.state.value.messages.filter { it.role == ChatRole.ASSISTANT }
            assertEquals(listOf("first", "second"), assistants.map { it.providerProfileId })
            assertEquals(listOf("Claude answer", "Gemini answer"), assistants.map { it.text })
            assertTrue(assistants.all { it.state == ChatMessageState.COMPLETE })
            assertFalse(viewModel.state.value.isStreaming)
        }

    @Test
    fun `assistant selection is conversation scoped and saved default applies to new chats`() =
        runTest(dispatcher) {
            var persisted: AssistantSelection? = null
            val viewModel = ChatViewModel(
                initialAssistantSelection = AssistantSelection("coding", "expert_engineer"),
                persistAssistantDefaults = { persisted = it },
            )

            assertEquals("coding", viewModel.state.value.selectedSkillId)
            assertEquals("expert_engineer", viewModel.state.value.defaultPersonaId)
            viewModel.selectAssistantMode("debugging", "concise", saveAsDefault = true)

            assertEquals(AssistantSelection("debugging", "concise"), persisted)
            assertEquals("debugging", viewModel.state.value.defaultSkillId)
            viewModel.updateDraft("create another conversation")
            viewModel.newConversation()

            assertEquals("debugging", viewModel.state.value.selectedSkillId)
            assertEquals("concise", viewModel.state.value.selectedPersonaId)
        }

    @Test
    fun `stream captures assistant mode while next message selection remains editable`() =
        runTest(dispatcher) {
            val session = FakeSession.slow(
                profile("assistant-mode-stream", ProviderType.OPENAI_COMPATIBLE),
                "partial",
            )
            val viewModel = connectedViewModel(session)
            viewModel.selectAssistantMode("coding", "concise")

            viewModel.send("Implement this", session)
            runCurrent()
            viewModel.selectAssistantMode("learning", "beginner_friendly")

            val request = JSONObject(session.requests.single())
            assertTrue(request.getString("system").contains("maintainable code"))
            assertTrue(request.getString("system").contains("Be concise"))
            assertEquals("learning", viewModel.state.value.selectedSkillId)
            assertEquals("coding", viewModel.state.value.messages.last().assistantSkillId)
            assertEquals("concise", viewModel.state.value.messages.last().assistantPersonaId)
            viewModel.stopStreaming()
            runCurrent()
        }

    @Test
    fun `parallel conversations keep independent assistant instructions and metadata`() =
        runTest(dispatcher) {
            val first = FakeSession.slow(profile("mode-a", ProviderType.GEMINI), "A")
            val second = FakeSession.slow(
                profile("mode-b", ProviderType.OPENAI_COMPATIBLE),
                "B",
            )
            val viewModel = ChatViewModel()
            viewModel.updateConnections(listOf(first.connected(), second.connected()))
            viewModel.selectAssistantMode("coding", "concise")
            val firstId = viewModel.state.value.activeConversationId
            viewModel.send("First", first)
            runCurrent()

            viewModel.newConversation()
            viewModel.selectProvider(second.profile.id)
            viewModel.selectAssistantMode("learning", "beginner_friendly")
            val secondId = viewModel.state.value.activeConversationId
            viewModel.send("Second", second)
            runCurrent()

            assertTrue(JSONObject(first.requests.single()).getString("system").contains("maintainable code"))
            assertTrue(JSONObject(second.requests.single()).getString("system").contains("Teach progressively"))
            assertEquals("learning", viewModel.state.value.messages.last().assistantSkillId)
            viewModel.selectConversation(firstId)
            assertEquals("coding", viewModel.state.value.messages.last().assistantSkillId)
            viewModel.stopStreaming()
            runCurrent()
            viewModel.selectConversation(secondId)
            viewModel.stopStreaming()
            runCurrent()
        }

    @Test
    fun `retry reuses original assistant mode after conversation selection changes`() =
        runTest(dispatcher) {
            val session = FakeSession(
                profile("assistant-mode-retry", ProviderType.GEMINI),
                Step.Failure(IOException("temporary")),
                Step.Result(HostLlmStreamResult(events = flowOf(HostLlmStreamEvent.delta("ok")))),
            )
            val viewModel = connectedViewModel(session)
            viewModel.selectAssistantMode("code_review", "critical_reviewer")
            viewModel.send("Review this", session)
            advanceUntilIdle()

            viewModel.selectAssistantMode("learning", "beginner_friendly")
            viewModel.retry(session)
            advanceUntilIdle()

            val systems = session.requests.map { JSONObject(it).getString("system") }
            assertEquals(systems.first(), systems.last())
            assertTrue(systems.last().contains("Review for concrete correctness"))
            assertEquals("code_review", viewModel.state.value.messages.last().assistantSkillId)
            assertEquals(
                "critical_reviewer",
                viewModel.state.value.messages.last().assistantPersonaId,
            )
        }

    @Test
    fun `measurable word limit is validated and corrected only once`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("constraint-correct", ProviderType.CODEX),
            Step.Result(
                HostLlmStreamResult(
                    events = flowOf(HostLlmStreamEvent.delta("one two three four five six")),
                ),
            ),
            Step.Result(
                HostLlmStreamResult(
                    events = flowOf(HostLlmStreamEvent.delta("one two three four")),
                ),
            ),
        )
        val viewModel = connectedViewModel(session)

        viewModel.send("Reply in under five words.", session)
        advanceUntilIdle()

        assertEquals(2, session.requests.size)
        assertTrue(JSONObject(session.requests[0]).getString("system").contains("at most 4 words"))
        assertTrue(JSONObject(session.requests[1]).getString("system").contains("previous draft"))
        assertEquals("one two three four", viewModel.state.value.messages.last().text)
        assertEquals(ChatMessageState.COMPLETE, viewModel.state.value.messages.last().state)
        assertEquals(
            "Response corrected to match the requested format.",
            viewModel.state.value.statusMessage,
        )
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `second constraint violation is kept without a third provider call`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("constraint-still-invalid", ProviderType.GEMINI),
            Step.Result(
                HostLlmStreamResult(events = flowOf(HostLlmStreamEvent.delta("one two three"))),
            ),
            Step.Result(
                HostLlmStreamResult(events = flowOf(HostLlmStreamEvent.delta("four five six"))),
            ),
        )
        val viewModel = connectedViewModel(session)

        viewModel.send("Use at most two words.", session)
        advanceUntilIdle()

        assertEquals(2, session.requests.size)
        assertEquals("four five six", viewModel.state.value.messages.last().text)
        assertEquals(
            "Response format may not match the requested limits.",
            viewModel.state.value.statusMessage,
        )
        assertFalse(viewModel.state.value.isStreaming)
    }

    @Test
    fun `correction failure restores original response without exposing provider error`() =
        runTest(dispatcher) {
            val original = "one two three"
            val session = FakeSession(
                profile("constraint-fallback", ProviderType.OPENAI_COMPATIBLE),
                Step.Result(
                    HostLlmStreamResult(events = flowOf(HostLlmStreamEvent.delta(original))),
                ),
                Step.Failure(IOException("private correction failure")),
            )
            val viewModel = connectedViewModel(session)

            viewModel.send("Use at most two words.", session)
            advanceUntilIdle()

            assertEquals(2, session.requests.size)
            assertEquals(original, viewModel.state.value.messages.last().text)
            assertEquals(ChatMessageState.COMPLETE, viewModel.state.value.messages.last().state)
            assertNull(viewModel.state.value.failure)
            assertFalse(viewModel.state.value.statusMessage.orEmpty().contains("private"))
            assertEquals(
                "Response format could not be corrected; the original response was kept.",
                viewModel.state.value.statusMessage,
            )
        }

    @Test
    fun `stopping constraint correction keeps its partial response`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("constraint-stop", ProviderType.OPENAI_COMPATIBLE),
            Step.Result(
                HostLlmStreamResult(
                    events = flowOf(HostLlmStreamEvent.delta("one two three")),
                ),
            ),
            Step.Result(
                HostLlmStreamResult(
                    events = flow {
                        emit(HostLlmStreamEvent.delta("partial"))
                        awaitCancellation()
                    },
                ),
            ),
        )
        val viewModel = connectedViewModel(session)

        viewModel.send("Use at most two words.", session)
        runCurrent()
        assertEquals(2, session.requests.size)
        assertEquals("partial", viewModel.state.value.messages.last().text)

        viewModel.stopStreaming()
        runCurrent()

        assertEquals("partial", viewModel.state.value.messages.last().text)
        assertEquals(ChatMessageState.CANCELLED, viewModel.state.value.messages.last().state)
        assertFalse(viewModel.state.value.isStreaming)
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `unsupported web verification claim is corrected only once`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("freshness-correct", ProviderType.CODEX),
            Step.Result(
                HostLlmStreamResult(
                    events = flowOf(
                        HostLlmStreamEvent.delta(
                            "I checked the web today and verified the temperature.",
                        ),
                    ),
                ),
            ),
            Step.Result(
                HostLlmStreamResult(
                    events = flowOf(
                        HostLlmStreamEvent.delta(
                            "I cannot access live web data here, so I cannot verify today's temperature.",
                        ),
                    ),
                ),
            ),
        )
        val viewModel = connectedViewModel(session)

        viewModel.send("Search the web and verify today's Seoul temperature.", session)
        advanceUntilIdle()

        assertEquals(2, session.requests.size)
        val systems = session.requests.map { JSONObject(it).getString("system") }
        assertTrue(systems.first().contains("has no web, browser, search, or live-data tool"))
        assertTrue(systems.last().contains("previous draft claimed external verification"))
        assertEquals(
            "I cannot access live web data here, so I cannot verify today's temperature.",
            viewModel.state.value.messages.last().text,
        )
        assertEquals(
            "Response corrected to remove an unsupported verification claim.",
            viewModel.state.value.statusMessage,
        )
    }

    @Test
    fun `format and verification checks share a single correction budget`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("freshness-bounded", ProviderType.GEMINI),
            Step.Result(
                HostLlmStreamResult(
                    events = flowOf(
                        HostLlmStreamEvent.delta("I checked the web and verified one two three."),
                    ),
                ),
            ),
            Step.Result(
                HostLlmStreamResult(
                    events = flowOf(
                        HostLlmStreamEvent.delta("I checked the web and verified four five six."),
                    ),
                ),
            ),
        )
        val viewModel = connectedViewModel(session)

        viewModel.send("Search the web. Reply in at most two words.", session)
        advanceUntilIdle()

        assertEquals(2, session.requests.size)
        assertEquals(ChatMessageState.COMPLETE, viewModel.state.value.messages.last().state)
        assertEquals(
            "Response may not match the requested limits or verification boundary.",
            viewModel.state.value.statusMessage,
        )
    }

    @Test
    fun `stop keeps partial response and creates no failure`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("slow", ProviderType.OPENAI_COMPATIBLE),
            Step.Result(
                HostLlmStreamResult(events = flow {
                    emit(HostLlmStreamEvent.delta("partial"))
                    awaitCancellation()
                }),
            ),
        )
        val viewModel = connectedViewModel(session)

        viewModel.send("Long request", session)
        runCurrent()
        viewModel.stopStreaming()
        runCurrent()

        val assistant = viewModel.state.value.messages.last()
        assertEquals("partial", assistant.text)
        assertEquals(ChatMessageState.CANCELLED, assistant.state)
        assertFalse(viewModel.state.value.isStreaming)
        assertNull(viewModel.state.value.failure)
        assertNull(viewModel.state.value.retryTarget)
    }

    @Test
    fun `next provider can be selected while current stream keeps original metadata`() =
        runTest(dispatcher) {
            val currentSession = FakeSession(
                profile("current", ProviderType.OPENAI_COMPATIBLE),
                Step.Result(
                    HostLlmStreamResult(events = flow {
                        emit(HostLlmStreamEvent.delta("partial"))
                        awaitCancellation()
                    }),
                ),
            )
            val nextSession = FakeSession.success(
                profile("next", ProviderType.GEMINI),
                "next answer",
            )
            val viewModel = ChatViewModel()
            viewModel.updateConnections(listOf(currentSession.connected(), nextSession.connected()))

            viewModel.send("current request", currentSession)
            runCurrent()
            viewModel.selectProvider(nextSession.profile.id)
            viewModel.send("must wait", nextSession)

            val streaming = viewModel.state.value
            assertTrue(streaming.isStreaming)
            assertEquals(nextSession.profile.id, streaming.selectedProfileId)
            assertEquals(currentSession.profile.id, streaming.messages.last().providerProfileId)
            assertEquals(currentSession.profile.model, streaming.messages.last().model)
            assertTrue(nextSession.requests.isEmpty())

            viewModel.stopStreaming()
            runCurrent()
            viewModel.send("next request", nextSession)
            advanceUntilIdle()

            assertEquals(1, currentSession.requests.size)
            assertEquals(1, nextSession.requests.size)
            assertEquals("next answer", viewModel.state.value.messages.last().text)
        }

    @Test
    fun `new chat preserves active stream and previous conversation can be reopened`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("clear-active", ProviderType.OPENAI_COMPATIBLE),
            Step.Result(
                HostLlmStreamResult(events = flow {
                    emit(HostLlmStreamEvent.delta("discard me"))
                    awaitCancellation()
                }),
            ),
        )
        val viewModel = connectedViewModel(session)

        viewModel.send("clear this", session)
        runCurrent()
        val firstConversationId = viewModel.state.value.activeConversationId
        viewModel.clearConversation()
        runCurrent()

        val newConversation = viewModel.state.value
        assertTrue(newConversation.messages.isEmpty())
        assertFalse(newConversation.isStreaming)
        assertTrue(newConversation.conversations.any {
            it.id == firstConversationId &&
                it.generationState == dev.alpine.llm.demo.model.ConversationGenerationState.STREAMING
        })

        viewModel.selectConversation(firstConversationId)
        assertTrue(viewModel.state.value.isStreaming)
        assertEquals("discard me", viewModel.state.value.messages.last().text)
        viewModel.stopStreaming()
        runCurrent()
        assertEquals(ChatMessageState.CANCELLED, viewModel.state.value.messages.last().state)
    }

    @Test
    fun `conversation switch restores isolated draft provider model and messages`() =
        runTest(dispatcher) {
            val first = FakeSession.success(profile("conversation-a", ProviderType.GEMINI), "A answer")
            val second = FakeSession.success(
                profile("conversation-b", ProviderType.OPENAI_COMPATIBLE),
                "B answer",
            )
            val viewModel = ChatViewModel()
            viewModel.updateConnections(listOf(first.connected(), second.connected()))
            viewModel.updateDraft("draft-a")
            val firstId = viewModel.state.value.activeConversationId
            viewModel.send("Question A", first)
            advanceUntilIdle()

            viewModel.newConversation()
            viewModel.selectProvider(second.profile.id)
            viewModel.updateDraft("draft-b")
            val secondId = viewModel.state.value.activeConversationId

            viewModel.selectConversation(firstId)
            assertEquals("", viewModel.state.value.draft)
            assertEquals(first.profile.id, viewModel.state.value.selectedProfileId)
            assertEquals("A answer", viewModel.state.value.messages.last().text)

            viewModel.selectConversation(secondId)
            assertEquals("draft-b", viewModel.state.value.draft)
            assertEquals(second.profile.id, viewModel.state.value.selectedProfileId)
            assertTrue(viewModel.state.value.messages.isEmpty())
        }

    @Test
    fun `two conversations may stream while third is rejected before provider call`() =
        runTest(dispatcher) {
            val first = FakeSession.slow(profile("parallel-a", ProviderType.GEMINI), "A partial")
            val second = FakeSession.slow(
                profile("parallel-b", ProviderType.OPENAI_COMPATIBLE),
                "B partial",
            )
            val third = FakeSession.slow(profile("parallel-c", ProviderType.ANTHROPIC), "C partial")
            val viewModel = ChatViewModel()
            viewModel.updateConnections(
                listOf(first.connected(), second.connected(), third.connected()),
            )

            viewModel.send("A", first)
            runCurrent()
            viewModel.newConversation()
            viewModel.selectProvider(second.profile.id)
            viewModel.send("B", second)
            runCurrent()
            viewModel.newConversation()
            viewModel.selectProvider(third.profile.id)
            viewModel.updateDraft("C remains a draft")
            viewModel.send("C", third)
            runCurrent()

            assertEquals(2, viewModel.state.value.activeGenerationCount)
            assertEquals(1, first.requests.size)
            assertEquals(1, second.requests.size)
            assertTrue(third.requests.isEmpty())
            assertEquals("C remains a draft", viewModel.state.value.draft)
            assertEquals("Two conversations are already generating.", viewModel.state.value.statusMessage)

            val firstId = viewModel.state.value.conversations.first {
                it.selectedProfileId == first.profile.id
            }.id
            viewModel.selectConversation(firstId)
            viewModel.stopStreaming()
            runCurrent()
            assertEquals(1, viewModel.state.value.activeGenerationCount)

            val secondId = viewModel.state.value.conversations.first {
                it.selectedProfileId == second.profile.id
            }.id
            viewModel.selectConversation(secondId)
            assertTrue(viewModel.state.value.isStreaming)
            viewModel.stopStreaming()
            runCurrent()
        }

    @Test
    fun `disconnected provider falls back without losing saved conversation content`() =
        runTest(dispatcher) {
            val first = FakeSession.success(
                profile("fallback-a", ProviderType.OPENAI_COMPATIBLE),
                "unused",
            )
            val second = FakeSession.success(
                profile("fallback-b", ProviderType.GEMINI),
                "saved answer",
            )
            val viewModel = ChatViewModel()
            viewModel.updateConnections(listOf(first.connected(), second.connected()))
            viewModel.selectProvider(second.profile.id)
            viewModel.send("Keep this history", second)
            advanceUntilIdle()

            viewModel.updateConnections(listOf(first.connected()))
            assertEquals(first.profile.id, viewModel.state.value.selectedProfileId)
            assertEquals("saved answer", viewModel.state.value.messages.last().text)

            viewModel.updateConnections(emptyList())
            assertNull(viewModel.state.value.selectedProfileId)
            assertEquals("saved answer", viewModel.state.value.messages.last().text)
        }

    @Test
    fun `rapid duplicate send starts only one provider request`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("single-flight", ProviderType.OPENAI_COMPATIBLE),
            Step.Result(
                HostLlmStreamResult(events = flow {
                    emit(HostLlmStreamEvent.delta("partial"))
                    awaitCancellation()
                }),
            ),
        )
        val viewModel = connectedViewModel(session)

        viewModel.send("Only once", session)
        viewModel.send("Only once", session)
        runCurrent()

        assertEquals(1, session.requests.size)
        assertEquals(1, viewModel.state.value.messages.count { it.role == ChatRole.USER })
        viewModel.stopStreaming()
        runCurrent()
    }

    @Test
    fun `blank input is ignored and long input is preserved`() = runTest(dispatcher) {
        val session = FakeSession.success(
            profile("input-boundary", ProviderType.GEMINI),
            "Accepted",
        )
        val viewModel = connectedViewModel(session)
        val longPrompt = "long-input-".repeat(1_000)

        viewModel.send("   ", session)
        viewModel.send(longPrompt, session)
        advanceUntilIdle()

        assertEquals(1, session.requests.size)
        assertEquals(longPrompt, viewModel.state.value.messages.first().text)
        assertTrue(session.requests.single().contains(longPrompt))
    }

    @Test
    fun `401 and invalid grant require reconnect and cannot be retried`() = runTest(dispatcher) {
        val unauthorized = FakeSession(
            profile("unauthorized", ProviderType.ANTHROPIC),
            Step.Result(HostLlmStreamResult(statusCode = 401)),
        )
        val viewModel = connectedViewModel(unauthorized)
        viewModel.send("Request", unauthorized)
        advanceUntilIdle()
        assertReauthenticationRequired(viewModel)

        val invalidGrant = FakeSession(
            profile("invalid-grant", ProviderType.GEMINI),
            Step.Failure(OAuthException("provider-secret", OAuthFailureKind.INVALID_GRANT)),
        )
        val invalidGrantViewModel = connectedViewModel(invalidGrant)
        invalidGrantViewModel.send("Request", invalidGrant)
        advanceUntilIdle()
        assertReauthenticationRequired(invalidGrantViewModel)
    }

    @Test
    fun `429 preserves only safe retry metadata`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("overloaded", ProviderType.GEMINI),
            Step.Result(HostLlmStreamResult(statusCode = 429)),
        )
        val viewModel = connectedViewModel(session)
        viewModel.send("Request", session)
        advanceUntilIdle()

        val failure = requireNotNull(viewModel.state.value.failure)
        assertEquals(ChatFailureKind.OVERLOADED, failure.kind)
        assertEquals(ChatRecoveryAction.RETRY, failure.recoveryAction)
        assertNull(failure.retryAfterSeconds)
        assertNotNull(viewModel.state.value.retryTarget)
    }

    @Test
    fun `500 and 503 become provider unavailable`() = runTest(dispatcher) {
        listOf(500, 503).forEach { statusCode ->
            val session = FakeSession(
                profile("server-$statusCode", ProviderType.OPENAI_COMPATIBLE),
                Step.Result(HostLlmStreamResult(statusCode = statusCode)),
            )
            val viewModel = connectedViewModel(session)
            viewModel.send("Request", session)
            advanceUntilIdle()
            assertEquals(ChatFailureKind.PROVIDER_UNAVAILABLE, viewModel.state.value.failure?.kind)
        }
    }

    @Test
    fun `circuit open network timeout and malformed stream are classified without raw details`() =
        runTest(dispatcher) {
            val rawSecret = "https://provider.invalid/v1 token=super-secret header=Bearer-nope"
            val cases = listOf(
                ProviderCircuitOpenException() to ChatFailureKind.CIRCUIT_OPEN,
                IOException(rawSecret) to ChatFailureKind.NETWORK,
                SocketTimeoutException(rawSecret) to ChatFailureKind.TIMEOUT,
                ProviderStreamException(rawSecret) to ChatFailureKind.INVALID_RESPONSE,
            )
            cases.forEachIndexed { index, (error, expectedKind) ->
                val session = FakeSession(
                    profile("failure-$index", ProviderType.ANTHROPIC),
                    Step.Failure(error),
                )
                val viewModel = connectedViewModel(session)
                viewModel.send("Request", session)
                advanceUntilIdle()

                val state = viewModel.state.value
                assertEquals(expectedKind, state.failure?.kind)
                assertEquals(ChatMessageState.FAILED, state.messages.last().state)
                assertFalse(state.toString().contains(rawSecret))
                assertFalse(requireNotNull(state.failure).toString().contains(rawSecret))
            }
        }

    @Test
    fun `coroutine timeout is a retryable failure rather than a user cancellation`() =
        runTest(dispatcher) {
            val session = FakeSession(
                profile("coroutine-timeout", ProviderType.OPENAI_COMPATIBLE),
                Step.Result(
                    HostLlmStreamResult(events = flow {
                        withTimeout(1) {
                            awaitCancellation()
                        }
                    }),
                ),
            )
            val viewModel = connectedViewModel(session)

            viewModel.send("Wait for timeout", session)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(ChatFailureKind.TIMEOUT, state.failure?.kind)
            assertEquals(ChatRecoveryAction.RETRY, state.failure?.recoveryAction)
            assertEquals(ChatMessageState.FAILED, state.messages.last().state)
            assertNotNull(state.retryTarget)
            assertFalse(state.isStreaming)
        }

    @Test
    fun `retry succeeds once without adding the original user message twice`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("retry", ProviderType.GEMINI),
            Step.Failure(IOException("temporary-provider-secret")),
            Step.Result(HostLlmStreamResult(events = flowOf(HostLlmStreamEvent.delta("Recovered")))),
        )
        val viewModel = connectedViewModel(session)

        viewModel.send("Retry this", session)
        advanceUntilIdle()
        viewModel.retry(session)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, session.requests.size)
        assertEquals(1, state.messages.count { it.role == ChatRole.USER && it.text == "Retry this" })
        assertEquals(1, state.messages.count { it.role == ChatRole.ASSISTANT })
        assertEquals("Recovered", state.messages.last().text)
        assertEquals(ChatMessageState.COMPLETE, state.messages.last().state)
        assertNull(state.failure)
        assertNull(state.retryTarget)
    }

    @Test
    fun `second retry is ignored while first retry is streaming`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("one-retry", ProviderType.OPENAI_COMPATIBLE),
            Step.Failure(IOException("temporary")),
            Step.Result(
                HostLlmStreamResult(events = flow {
                    emit(HostLlmStreamEvent.delta("retry partial"))
                    awaitCancellation()
                }),
            ),
        )
        val viewModel = connectedViewModel(session)
        viewModel.send("Retry once", session)
        advanceUntilIdle()

        viewModel.retry(session)
        runCurrent()
        viewModel.retry(session)

        assertEquals(2, session.requests.size)
        viewModel.stopStreaming()
        runCurrent()
    }

    @Test
    fun `partial failure is replaced by retry result without duplicate user message`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("partial", ProviderType.ANTHROPIC),
            Step.Result(
                HostLlmStreamResult(events = flow {
                    emit(HostLlmStreamEvent.delta("partial response"))
                    throw IOException("provider-body-secret")
                }),
            ),
            Step.Result(HostLlmStreamResult(events = flowOf(HostLlmStreamEvent.delta("complete response")))),
        )
        val viewModel = connectedViewModel(session)
        viewModel.send("Keep one user", session)
        advanceUntilIdle()

        assertEquals("partial response", viewModel.state.value.messages.last().text)
        assertEquals(ChatMessageState.FAILED, viewModel.state.value.messages.last().state)
        viewModel.retry(session)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(1, state.messages.count { it.role == ChatRole.USER && it.text == "Keep one user" })
        assertEquals(1, state.messages.count { it.role == ChatRole.ASSISTANT })
        assertEquals("complete response", state.messages.last().text)
    }

    private fun assertReauthenticationRequired(viewModel: ChatViewModel) {
        val state = viewModel.state.value
        assertEquals(ChatFailureKind.REAUTHENTICATION_REQUIRED, state.failure?.kind)
        assertEquals(ChatRecoveryAction.RECONNECT, state.failure?.recoveryAction)
        assertNull(state.retryTarget)
        assertEquals(ChatMessageState.FAILED, state.messages.last().state)
    }

    private fun connectedViewModel(session: FakeSession): ChatViewModel = ChatViewModel().also {
        it.updateConnections(listOf(session.connected()))
    }

    private fun profile(id: String, type: ProviderType): ProviderProfile = ProviderProfile(
        id = id,
        label = id,
        type = type,
        authorizationEndpoint = "https://identity.example.test/auth",
        tokenEndpoint = "https://identity.example.test/token",
        inferenceEndpoint = type.inferenceEndpointPlaceholder,
        clientId = "public-client",
        scopes = listOf("openid"),
        model = "$id-model",
    )

    private fun FakeSession.connected(): ProviderConnection = ProviderConnection(
        profile = profile,
        state = ProviderConnectionState.AUTHENTICATED,
        session = this,
    )

    private sealed interface Step {
        data class Result(val result: HostLlmStreamResult) : Step
        data class Failure(val error: Throwable) : Step
    }

    private class FakeSession(
        override val profile: ProviderProfile,
        vararg scriptedSteps: Step,
    ) : ChatCompletionSession {
        private val steps = ArrayDeque(scriptedSteps.toList())
        val requests = mutableListOf<String>()

        override fun authenticationState(): OAuthAuthenticationState =
            OAuthAuthenticationState.Authenticated(null, emptyMap())

        override suspend fun authorize(activity: Activity) = Unit

        override suspend fun stream(requestJson: String): HostLlmStreamResult {
            requests += requestJson
            return when (val step = steps.removeFirst()) {
                is Step.Result -> step.result
                is Step.Failure -> throw step.error
            }
        }

        override fun logout() = Unit
        override fun cancelAuthorization() = Unit

        companion object {
            fun success(profile: ProviderProfile, text: String) = FakeSession(
                profile,
                Step.Result(HostLlmStreamResult(events = flowOf(HostLlmStreamEvent.delta(text)))),
            )

            fun slow(profile: ProviderProfile, partial: String) = FakeSession(
                profile,
                Step.Result(
                    HostLlmStreamResult(events = flow {
                        emit(HostLlmStreamEvent.delta(partial))
                        awaitCancellation()
                    }),
                ),
            )
        }
    }
}
