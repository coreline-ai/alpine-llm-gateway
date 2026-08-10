package dev.alpine.llm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class CodexResponsesOAuthAdapterTest {
    private val endpoint = "https://provider.example.test/v1/responses"

    @Test
    fun contractUsesCodexLoopbackWithoutCliFingerprint() {
        val config = CodexOAuthContract.providerConfig("codex-profile", "public-client")

        assertEquals(CodexOAuthContract.AUTHORIZATION_ENDPOINT, config.authorizationEndpoint)
        assertEquals(CodexOAuthContract.TOKEN_ENDPOINT, config.tokenEndpoint)
        assertEquals("http://localhost:1455/auth/callback", config.redirectUri())
        assertTrue(config.callbackFallbackPorts.isEmpty())
        assertEquals(OAuthTokenRequestEncoding.JSON, config.tokenRequestEncoding)
        assertTrue(config.extraAuthorizationParams.isEmpty())
        assertTrue(config.tokenResponseAdapter is StandardOAuthTokenResponseAdapter)
        val request = mapOf("grant_type" to "authorization_code", "code_verifier" to "verifier")
        assertEquals(
            request,
            config.tokenRequestAdapter.adapt(
                OAuthTokenRequestContext(OAuthTokenGrantType.AUTHORIZATION_CODE, request),
            ),
        )
    }

    @Test
    fun requestUsesConfiguredEndpointAndSafeResponsesBody() {
        val adapter = CodexResponsesOAuthAdapter(endpoint)
        val request = adapter.createStreamRequest(
            """
            {"model":"gpt-test","system":"base instruction","messages":[
              {"role":"system","content":"second instruction"},
              {"role":"user","content":"inspect"},
              {"role":"assistant","content":"calling", "tool_calls":[{
                "id":"call_1|fc_1","type":"function",
                "function":{"name":"lookup","arguments":"{\"q\":\"x\"}"}
              }]},
              {"role":"tool","tool_call_id":"call_1|fc_1","content":"result"}
            ],"tools":[{"type":"function","function":{
              "name":"lookup","description":"Lookup","parameters":{"type":"object"}
            }}],"tool_choice":"auto","max_tokens":2048}
            """.trimIndent(),
        )

        assertEquals(endpoint, request.url)
        assertTrue(request.headers.isEmpty())
        assertFalse(request.headers.containsKey("Authorization"))
        assertNull(request.credentialAccountIdHeader)
        assertFalse(request.allowNonStandardEventStreamContentType)

        val body = JSONObject(request.bodyJson)
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
        assertFalse(body.has("include"))
        assertFalse(body.has("reasoning"))
        assertEquals("base instruction\n\nsecond instruction", body.getString("instructions"))
        assertEquals("lookup", body.getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    @Test
    fun approvedCompatibilityAddsOauthMetadataHeadersAndRequiredBodyFields() {
        val compatibility = CodexOAuthCompatibilityConfig(
            sourceRevision = "reference@revision",
            clientId = "approved-debug-public-client",
            responsesEndpoint = endpoint,
            defaultModel = "gpt-current",
            modelOptions = listOf("gpt-current", "gpt-fast"),
            extraAuthorizationParams = mapOf("approved_flow" to "true"),
            requestHeaders = mapOf("Version" to "1.2.3", "Originator" to "approved-debug"),
            accountIdHeader = "Provider-Account-Id",
            includeEncryptedReasoning = true,
            reasoningEffort = "low",
        )
        CodexOAuthCompatibilityRegistry.installApprovedDebug(compatibility)

        val oauth = CodexOAuthContract.providerConfig("codex-profile", compatibility.clientId)
        assertEquals(mapOf("approved_flow" to "true"), oauth.extraAuthorizationParams)
        assertTrue(oauth.tokenResponseAdapter is JwtClaimMetadataTokenResponseAdapter)

        val request = CodexResponsesOAuthAdapter(endpoint, compatibility).createStreamRequest(
            """{"model":"gpt-current","messages":[{"role":"user","content":"hello"}]}""",
        )
        val body = JSONObject(request.bodyJson)
        assertEquals(compatibility.requestHeaders, request.headers)
        assertEquals("Provider-Account-Id", request.credentialAccountIdHeader)
        assertTrue(request.allowNonStandardEventStreamContentType)
        assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("auto", body.getJSONObject("reasoning").getString("summary"))
    }

    @Test
    fun nonStreamingResponseNormalizesTextToolsAndUsage() {
        val adapter = CodexResponsesOAuthAdapter(endpoint)
        val response = JSONObject()
            .put("id", "resp_1")
            .put("model", "gpt-test")
            .put(
                "output",
                JSONArray()
                    .put(
                        JSONObject().put("type", "message").put(
                            "content",
                            JSONArray().put(
                                JSONObject().put("type", "output_text").put("text", "hello"),
                            ),
                        ),
                    )
                    .put(
                        JSONObject()
                            .put("type", "function_call")
                            .put("id", "fc_1")
                            .put("call_id", "call_1")
                            .put("name", "lookup")
                            .put("arguments", "{\"q\":\"x\"}"),
                    ),
            )
            .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 4))

        val normalized = JSONObject(adapter.createResult(ProviderHttpResponse(200, response.toString())).bodyJson)
        val choice = normalized.getJSONArray("choices").getJSONObject(0)
        assertEquals("hello", choice.getJSONObject("message").getString("content"))
        assertEquals("tool_calls", choice.getString("finish_reason"))
        assertEquals(14, normalized.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun streamNormalizesEventsAndRedactsProviderFailure() {
        val adapter = CodexResponsesOAuthAdapter(endpoint)
        val text = adapter.createStreamEvent(
            ProviderSseEvent(null, "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}"),
        )
        val completed = adapter.createStreamEvent(
            ProviderSseEvent(
                null,
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":2,\"output_tokens\":3}}}",
            ),
        )
        assertEquals("hi", JSONObject(requireNotNull(text).dataJson).getString("text"))
        assertEquals(5, JSONObject(requireNotNull(completed).dataJson).getJSONObject("usage").getInt("total_tokens"))
        assertNull(adapter.createStreamEvent(ProviderSseEvent(null, "[DONE]")))

        val error = runCatching {
            adapter.createStreamEvent(
                ProviderSseEvent(null, "{\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"secret-provider-detail\"}}}"),
            )
        }.exceptionOrNull()
        assertTrue(error is ProviderStreamException)
        assertFalse(error?.message.orEmpty().contains("secret-provider-detail"))
    }

    @Test
    fun streamFailureKeepsOnlySafeProviderErrorCode() {
        val adapter = CodexResponsesOAuthAdapter(endpoint)
        val error = runCatching {
            adapter.createStreamEvent(
                ProviderSseEvent(
                    null,
                    """{"type":"response.failed","response":{"error":{"code":"model_not_found","message":"do not expose"}}}""",
                ),
            )
        }.exceptionOrNull() as ProviderStreamException

        assertEquals("codex_stream_error.model_not_found", error.diagnosticCode)
        assertFalse(error.message.orEmpty().contains("do not expose"))
    }

    @Test
    fun malformedAndHttpErrorResponsesAreRedacted() {
        val adapter = CodexResponsesOAuthAdapter(endpoint)
        val malformed = adapter.createResult(ProviderHttpResponse(200, "not-json-secret"))
        val httpError = adapter.createResult(ProviderHttpResponse(429, "{\"error\":\"provider-secret\"}"))

        assertEquals(502, malformed.statusCode)
        assertFalse(malformed.bodyJson.contains("not-json-secret"))
        assertEquals(429, httpError.statusCode)
        assertFalse(httpError.bodyJson.contains("provider-secret"))
    }
}
    @After
    fun clearCompatibility() {
        CodexOAuthCompatibilityRegistry.clear()
    }
