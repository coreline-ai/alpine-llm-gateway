package dev.alpine.chat.provider.android.session

import android.app.Activity
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.llm.OAuthAuthenticationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderPostSaveLoginPolicyTest {
    @Test
    fun selectsOnlyRequestedSignedOutProfile() {
        val signedOut = connection("signed-out", ProviderConnectionState.SIGNED_OUT)
        val connected = connection("connected", ProviderConnectionState.AUTHENTICATED)
        val connections = listOf(signedOut, connected)

        assertEquals(
            signedOut,
            ProviderPostSaveLoginPolicy.select(connections, "signed-out", requestLogin = true),
        )
        assertNull(
            ProviderPostSaveLoginPolicy.select(connections, "signed-out", requestLogin = false),
        )
        assertNull(
            ProviderPostSaveLoginPolicy.select(connections, "connected", requestLogin = true),
        )
        assertNull(
            ProviderPostSaveLoginPolicy.select(connections, "missing", requestLogin = true),
        )
    }

    private fun connection(
        id: String,
        state: ProviderConnectionState,
    ): ProviderConnection {
        val profile = ProviderProfile.draft(ProviderType.GEMINI, id).copy(
            id = id,
            clientId = "owned-client-id",
        )
        val session = object : ChatCompletionSession {
            override val profile = profile
            override fun authenticationState(): OAuthAuthenticationState =
                OAuthAuthenticationState.SignedOut
            override suspend fun authorize(activity: Activity) = Unit
            override suspend fun stream(requestJson: String): ChatBackendStreamResult =
                ChatBackendStreamResult()
            override fun logout() = Unit
            override fun cancelAuthorization() = Unit
        }
        return ProviderConnection(profile, state, session)
    }
}
