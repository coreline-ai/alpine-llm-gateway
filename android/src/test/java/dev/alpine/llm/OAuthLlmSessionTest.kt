package dev.alpine.llm

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthLlmSessionTest {
    @Test
    fun unauthorizedStreamRefreshesCredentialButDoesNotReplayInference() = runBlocking {
        val credentials = FakeCredentials(
            initial = token("old-access"),
            refreshed = token("new-access"),
        )
        val bridge = RecordingBridge(streamStatuses = mutableListOf(401, 200))
        val session = OAuthLlmSession.forTesting(credentials, bridge)

        val first = session.stream("{}")

        assertEquals(401, first.statusCode)
        assertEquals(listOf("old-access"), bridge.streamAccessTokens)
        assertEquals(listOf("old-access"), credentials.rejectedTokens)

        // A user-initiated retry is the first request allowed to use the refreshed credential.
        val second = session.stream("{}")

        assertEquals(200, second.statusCode)
        assertEquals(listOf("old-access", "new-access"), bridge.streamAccessTokens)
        assertEquals(1, credentials.rejectedTokens.size)
    }

    @Test
    fun unauthorizedCompletionRefreshesCredentialButDoesNotReplayInference() = runBlocking {
        val credentials = FakeCredentials(
            initial = token("old-access"),
            refreshed = token("new-access"),
        )
        val bridge = RecordingBridge(completionStatuses = mutableListOf(401, 200))
        val session = OAuthLlmSession.forTesting(credentials, bridge)

        val first = session.complete("{}")

        assertEquals(401, first.statusCode)
        assertEquals(listOf("old-access"), bridge.completionAccessTokens)
        assertEquals(listOf("old-access"), credentials.rejectedTokens)

        val second = session.complete("{}")

        assertEquals(200, second.statusCode)
        assertEquals(listOf("old-access", "new-access"), bridge.completionAccessTokens)
        assertEquals(1, credentials.rejectedTokens.size)
    }

    @Test
    fun unauthorizedWithoutRefreshDoesNotReplayAndRequiresLogin() = runBlocking {
        val credentials = FakeCredentials(initial = token("old-access"), refreshed = null)
        val bridge = RecordingBridge(streamStatuses = mutableListOf(401, 200))
        val session = OAuthLlmSession.forTesting(credentials, bridge)

        val error = runCatching { session.stream("{}") }.exceptionOrNull()

        assertTrue(error is OAuthRequiredException)
        assertEquals(listOf("old-access"), bridge.streamAccessTokens)
        assertEquals(listOf("old-access"), credentials.rejectedTokens)
    }

    private fun token(accessToken: String) = OAuthTokenStore.Token(
        accessToken = accessToken,
        refreshToken = "refresh-token",
    )

    private class FakeCredentials(
        initial: OAuthTokenStore.Token,
        private val refreshed: OAuthTokenStore.Token?,
    ) : OAuthLlmCredentialSource {
        private var active = initial
        val rejectedTokens = mutableListOf<String>()

        override suspend fun validToken(): OAuthTokenStore.Token = active

        override suspend fun refreshAfterUnauthorized(
            rejectedAccessToken: String,
        ): OAuthTokenStore.Token? {
            rejectedTokens += rejectedAccessToken
            if (rejectedAccessToken == active.accessToken && refreshed != null) {
                active = refreshed
            }
            return refreshed
        }
    }

    private class RecordingBridge(
        private val completionStatuses: MutableList<Int> = mutableListOf(200),
        private val streamStatuses: MutableList<Int> = mutableListOf(200),
    ) : HostLlmBridge {
        val completionAccessTokens = mutableListOf<String>()
        val streamAccessTokens = mutableListOf<String>()

        override suspend fun complete(
            requestJson: String,
            credential: OAuthCredential,
        ): HostLlmResult {
            completionAccessTokens += credential.accessToken
            return HostLlmResult("{}", completionStatuses.removeAt(0))
        }

        override suspend fun stream(
            requestJson: String,
            credential: OAuthCredential,
        ): HostLlmStreamResult {
            streamAccessTokens += credential.accessToken
            return HostLlmStreamResult(
                statusCode = streamStatuses.removeAt(0),
                events = flowOf(),
            )
        }
    }
}
