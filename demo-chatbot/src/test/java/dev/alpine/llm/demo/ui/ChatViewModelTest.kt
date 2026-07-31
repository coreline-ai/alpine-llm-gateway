package dev.alpine.llm.demo.ui

import android.app.Activity
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
        }
    }
}
