package dev.alpine.chat.provider.android.session

import android.app.Activity
import dev.alpine.chat.feature.backend.ChatBackendDelta
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderModelCandidate
import dev.alpine.chat.provider.android.model.ProviderModelSource
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.llm.OAuthAuthenticationState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCompletionSessionTest {
    @Test
    fun `descriptor exposes enabled catalog candidates in stored order`() {
        val profile = ProviderProfile.draft(ProviderType.OPENAI_COMPATIBLE, "Custom").copy(
            authorizationEndpoint = "https://identity.example.test/authorize",
            tokenEndpoint = "https://identity.example.test/token",
            inferenceEndpoint = "https://api.example.test/v1/chat/completions",
            clientId = "owned-client",
            scopes = listOf("openid"),
            model = "model-a",
            modelCatalog = listOf(
                ProviderModelCandidate("model-a", ProviderModelSource.USER_ADDED),
                ProviderModelCandidate("model-b", ProviderModelSource.USER_ADDED, enabled = false),
                ProviderModelCandidate("model-c", ProviderModelSource.USER_ADDED),
            ),
        )

        assertEquals(listOf("model-a", "model-c"), profile.toChatBackendDescriptor().modelOptions)
    }

    @Test
    fun `default host bridge stream contains normalized deltas only`() = runTest {
        val session = object : ChatCompletionSession {
            override val profile: ProviderProfile = ProviderProfile.draft(ProviderType.CODEX, "Codex")
            override fun authenticationState() = OAuthAuthenticationState.Authenticated(null, emptyMap())
            override suspend fun authorize(activity: Activity) = Unit
            override suspend fun stream(requestJson: String) = ChatBackendStreamResult(
                events = flowOf(ChatBackendDelta("one"), ChatBackendDelta(" two")),
            )
            override fun logout() = Unit
            override fun cancelAuthorization() = Unit
        }

        val events = session.streamForHostBridge("{}").events.toList()

        assertEquals(listOf("one", " two"), events.map { JSONObject(it.dataJson).getString("text") })
        assertEquals(listOf("delta", "delta"), events.map { JSONObject(it.dataJson).getString("type") })
    }
}
