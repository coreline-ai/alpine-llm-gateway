package dev.alpine.llm.demo.llm

import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.demo.model.ProviderProfile

enum class ProviderConnectionState {
    AUTHENTICATED,
    SIGNED_OUT,
    REAUTHENTICATION_REQUIRED,
}

data class ProviderConnection(
    val profile: ProviderProfile,
    val state: ProviderConnectionState,
    val session: ChatCompletionSession,
)

class ConnectedProviderRegistry(
    private val sessionFactory: (ProviderProfile) -> ChatCompletionSession,
) {
    fun snapshot(profiles: List<ProviderProfile>): List<ProviderConnection> =
        profiles.map { profile ->
            val session = sessionFactory(profile)
            val state = when (session.authenticationState()) {
                is OAuthAuthenticationState.Authenticated ->
                    ProviderConnectionState.AUTHENTICATED
                OAuthAuthenticationState.SignedOut ->
                    ProviderConnectionState.SIGNED_OUT
                is OAuthAuthenticationState.ReauthenticationRequired ->
                    ProviderConnectionState.REAUTHENTICATION_REQUIRED
            }
            ProviderConnection(profile, state, session)
        }
}
