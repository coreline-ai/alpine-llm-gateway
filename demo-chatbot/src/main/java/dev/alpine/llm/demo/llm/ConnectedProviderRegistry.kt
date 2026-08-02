package dev.alpine.llm.demo.llm

import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.chat.feature.backend.ChatBackendConnection
import dev.alpine.chat.feature.backend.ChatBackendConnectionState
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
) {
    fun asChatBackendConnection(): ChatBackendConnection = ChatBackendConnection(
        descriptor = session.descriptor,
        state = when (state) {
            ProviderConnectionState.AUTHENTICATED -> ChatBackendConnectionState.AVAILABLE
            ProviderConnectionState.SIGNED_OUT -> ChatBackendConnectionState.SIGNED_OUT
            ProviderConnectionState.REAUTHENTICATION_REQUIRED ->
                ChatBackendConnectionState.REAUTHENTICATION_REQUIRED
        },
        session = session,
    )
}

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
