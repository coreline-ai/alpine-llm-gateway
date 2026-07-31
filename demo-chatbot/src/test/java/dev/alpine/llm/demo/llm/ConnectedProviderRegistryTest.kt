package dev.alpine.llm.demo.llm

import android.app.Activity
import dev.alpine.llm.HostLlmStreamResult
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.OAuthTokenStore
import dev.alpine.llm.demo.model.ProviderProfile
import dev.alpine.llm.demo.model.ProviderType
import dev.alpine.llm.demo.ui.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectedProviderRegistryTest {
    @Test
    fun `registry preserves per-profile auth state and selector only exposes authenticated`() {
        val profiles = listOf(
            profile("claude", ProviderType.ANTHROPIC),
            profile("gemini", ProviderType.GEMINI),
            profile("openai", ProviderType.OPENAI_COMPATIBLE),
        )
        val states = mapOf(
            "claude" to OAuthAuthenticationState.Authenticated(
                expiresAtMs = null,
                metadata = emptyMap(),
            ),
            "gemini" to OAuthAuthenticationState.SignedOut,
            "openai" to OAuthAuthenticationState.ReauthenticationRequired(
                OAuthTokenStore.InvalidationReason.DECRYPTION_FAILED,
            ),
        )
        val registry = ConnectedProviderRegistry { profile ->
            FakeSession(profile, requireNotNull(states[profile.id]))
        }

        val connections = registry.snapshot(profiles)
        assertEquals(
            listOf(
                ProviderConnectionState.AUTHENTICATED,
                ProviderConnectionState.SIGNED_OUT,
                ProviderConnectionState.REAUTHENTICATION_REQUIRED,
            ),
            connections.map(ProviderConnection::state),
        )

        val viewModel = ChatViewModel()
        viewModel.updateConnections(connections)
        assertEquals(listOf("claude"), viewModel.state.value.providers.map { it.profileId })
        assertEquals("claude", viewModel.state.value.selectedProfileId)
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
        model = "test-model",
    )

    private class FakeSession(
        override val profile: ProviderProfile,
        private val state: OAuthAuthenticationState,
    ) : ChatCompletionSession {
        override fun authenticationState(): OAuthAuthenticationState = state
        override suspend fun authorize(activity: Activity) = Unit
        override suspend fun stream(requestJson: String): HostLlmStreamResult =
            HostLlmStreamResult()
        override fun logout() = Unit
        override fun cancelAuthorization() = Unit
    }
}
