package dev.alpine.llm

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthHttpLlmBridgeTest {
    @Test
    fun credentialIsAddedAfterProviderAdaptation() = runBlocking {
        var captured: ProviderHttpRequest? = null
        val adapter = RecordingAdapter()
        val bridge = OAuthHttpLlmBridge(adapter) { request ->
            captured = request
            ProviderHttpResponse(200, """{"choices":[]}""")
        }

        val result = bridge.complete(
            """{"model":"test","messages":[]}""",
            OAuthCredential("secret-access-token", "Bearer"),
        )

        assertFalse(adapter.sawAuthorization)
        assertEquals("Bearer secret-access-token", captured?.headers?.get("Authorization"))
        assertEquals(200, result.statusCode)
        assertEquals("""{"choices":[]}""", result.bodyJson)
    }

    @Test
    fun openAiAdapterForcesNonStreamingRequest() {
        val adapter = OpenAiCompatibleOAuthAdapter(
            completionEndpoint = "https://provider.example.com/v1/chat/completions",
            extraHeaders = mapOf("X-Provider-Version" to "1"),
        )

        val request = adapter.createRequest("""{"model":"test","stream":true}""")

        assertEquals("1", request.headers["X-Provider-Version"])
        assertFalse(JSONObject(request.bodyJson).getBoolean("stream"))
    }

    @Test
    fun adapterCannotInjectAuthorization() {
        val bridge = OAuthHttpLlmBridge(
            adapter = object : OAuthProviderHttpAdapter {
                override fun createRequest(requestJson: String) = ProviderHttpRequest(
                    url = "https://provider.example.com",
                    bodyJson = requestJson,
                    headers = mapOf("authorization" to "attacker-controlled"),
                )
            },
            transport = OAuthHttpTransport { error("transport must not run") },
        )

        val error = runCatching {
            runBlocking { bridge.complete("{}", OAuthCredential("secret")) }
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private class RecordingAdapter : OAuthProviderHttpAdapter {
        var sawAuthorization = false

        override fun createRequest(requestJson: String): ProviderHttpRequest {
            val request = ProviderHttpRequest(
                url = "https://provider.example.com/v1/chat/completions",
                bodyJson = requestJson,
                headers = mapOf("X-Test" to "true"),
            )
            sawAuthorization = request.headers.keys.any {
                it.equals("Authorization", ignoreCase = true)
            }
            return request
        }
    }
}
