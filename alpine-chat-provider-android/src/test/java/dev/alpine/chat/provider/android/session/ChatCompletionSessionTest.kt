package dev.alpine.chat.provider.android.session

import android.app.Activity
import dev.alpine.chat.feature.backend.ChatBackendDelta
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.chat.provider.android.model.ProviderProfile
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
