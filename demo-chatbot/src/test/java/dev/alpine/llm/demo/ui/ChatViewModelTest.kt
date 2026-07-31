package dev.alpine.llm.demo.ui

import android.app.Activity
import dev.alpine.llm.HostLlmStreamEvent
import dev.alpine.llm.HostLlmStreamResult
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.demo.llm.ChatCompletionSession
import dev.alpine.llm.demo.llm.ProviderConnection
import dev.alpine.llm.demo.llm.ProviderConnectionState
import dev.alpine.llm.demo.model.ChatMessageState
import dev.alpine.llm.demo.model.ChatRole
import dev.alpine.llm.demo.model.ProviderProfile
import dev.alpine.llm.demo.model.ProviderType
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `provider switch sends only through the selected session and fixes metadata`() =
        runTest(dispatcher) {
        val first = FakeSession(
            profile("first", ProviderType.ANTHROPIC),
            flowOf(HostLlmStreamEvent.delta("Claude answer")),
        )
        val second = FakeSession(
            profile("second", ProviderType.GEMINI),
            flowOf(HostLlmStreamEvent.delta("Gemini answer")),
        )
        val viewModel = ChatViewModel()
        viewModel.updateConnections(listOf(first.connected(), second.connected()))

        viewModel.send("Question one", first)
        advanceUntilIdle()
        viewModel.selectProvider(second.profile.id)
        viewModel.send("Question two", second)
        advanceUntilIdle()

        assertEquals(1, first.requests.size)
        assertEquals(1, second.requests.size)
        val assistants = viewModel.state.value.messages.filter {
            it.role == ChatRole.ASSISTANT
        }
        assertEquals(listOf("first", "second"), assistants.map { it.providerProfileId })
        assertEquals(listOf("Claude answer", "Gemini answer"), assistants.map { it.text })
        assertTrue(assistants.all { it.state == ChatMessageState.COMPLETE })
        assertFalse(viewModel.state.value.isStreaming)
    }

    @Test
    fun `stop keeps partial response and unlocks the composer`() = runTest(dispatcher) {
        val session = FakeSession(
            profile("slow", ProviderType.OPENAI_COMPATIBLE),
            flow {
                emit(HostLlmStreamEvent.delta("partial"))
                awaitCancellation()
            },
        )
        val viewModel = ChatViewModel()
        viewModel.updateConnections(listOf(session.connected()))

        viewModel.send("Long request", session)
        runCurrent()
        viewModel.stopStreaming()
        runCurrent()

        val assistant = viewModel.state.value.messages.last()
        assertEquals("partial", assistant.text)
        assertEquals(ChatMessageState.CANCELLED, assistant.state)
        assertFalse(viewModel.state.value.isStreaming)
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

    private class FakeSession(
        override val profile: ProviderProfile,
        private val events: Flow<HostLlmStreamEvent>,
    ) : ChatCompletionSession {
        val requests = mutableListOf<String>()

        override fun authenticationState(): OAuthAuthenticationState =
            OAuthAuthenticationState.Authenticated(null, emptyMap())

        override suspend fun authorize(activity: Activity) = Unit

        override suspend fun stream(requestJson: String): HostLlmStreamResult {
            requests += requestJson
            return HostLlmStreamResult(events = events)
        }

        override fun logout() = Unit
        override fun cancelAuthorization() = Unit
    }
}
