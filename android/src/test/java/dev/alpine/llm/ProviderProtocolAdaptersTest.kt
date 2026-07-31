package dev.alpine.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class ProviderProtocolAdaptersTest {
    @Test
    fun anthropicAdapterMapsSystemMessagesAndUsage() {
        val adapter = AnthropicMessagesOAuthAdapter(
            messagesEndpoint = "https://api.example.com/v1/messages",
            anthropicBeta = "feature-1",
        )
        val request = adapter.createRequest(
            """
            {
              "model":"claude-test",
              "system":"top-system",
              "messages":[
                {"role":"system","content":"message-system"},
                {"role":"user","content":"hello"},
                {"role":"assistant","content":"hi"}
              ],
              "max_tokens":123,
              "temperature":0.25,
              "stop":["END"]
            }
            """.trimIndent(),
        )
        val body = JSONObject(request.bodyJson)

        assertEquals("top-system\n\nmessage-system", body.getString("system"))
        assertEquals(2, body.getJSONArray("messages").length())
        assertEquals(123, body.getInt("max_tokens"))
        assertEquals("feature-1", request.headers["anthropic-beta"])
        assertFalse(request.headers.keys.any { it.equals("Authorization", ignoreCase = true) })

        val result = adapter.createResult(
            ProviderHttpResponse(
                200,
                """
                {
                  "id":"msg_1",
                  "model":"claude-test",
                  "content":[{"type":"text","text":"hello "},{"type":"text","text":"world"}],
                  "stop_reason":"max_tokens",
                  "usage":{"input_tokens":5,"output_tokens":7}
                }
                """.trimIndent(),
            ),
        )
        val normalized = JSONObject(result.bodyJson)

        assertEquals("hello world", normalized
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content"))
        assertEquals("length", normalized
            .getJSONArray("choices").getJSONObject(0)
            .getString("finish_reason"))
        assertEquals(12, normalized.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun geminiAdapterMapsRolesGenerationConfigAndUsage() {
        val adapter = GeminiGenerateContentOAuthAdapter(
            endpointTemplate = "https://generativelanguage.example.com/v1/models/{model}:generateContent",
            extraHeaders = mapOf("X-Goog-User-Project" to "project-1"),
        )
        val request = adapter.createRequest(
            """
            {
              "model":"gemini test",
              "messages":[
                {"role":"system","content":"be concise"},
                {"role":"user","content":"hello"},
                {"role":"assistant","content":"hi"}
              ],
              "max_tokens":321,
              "temperature":0.5
            }
            """.trimIndent(),
        )
        val body = JSONObject(request.bodyJson)

        assertTrue(request.url.contains("gemini%20test"))
        assertEquals("model", body.getJSONArray("contents").getJSONObject(1).getString("role"))
        assertEquals(321, body.getJSONObject("generationConfig").getInt("maxOutputTokens"))
        assertEquals("be concise", body.getJSONObject("systemInstruction")
            .getJSONArray("parts").getJSONObject(0).getString("text"))

        val result = adapter.createResult(
            ProviderHttpResponse(
                200,
                """
                {
                  "responseId":"response-1",
                  "modelVersion":"gemini-test",
                  "candidates":[{
                    "content":{"parts":[{"text":"hello "},{"text":"world"}]},
                    "finishReason":"STOP"
                  }],
                  "usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":4}
                }
                """.trimIndent(),
            ),
        )
        val normalized = JSONObject(result.bodyJson)

        assertEquals("hello world", normalized
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content"))
        assertEquals(7, normalized.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun providerErrorsAreRedacted() {
        val secretBody = """{"error":{"message":"secret-access-token leaked"}}"""
        val anthropic = AnthropicMessagesOAuthAdapter("https://api.example.com/messages")
        val gemini = GeminiGenerateContentOAuthAdapter(
            "https://api.example.com/models/{model}:generateContent",
        )

        val anthropicError = anthropic.createResult(ProviderHttpResponse(429, secretBody))
        val geminiError = gemini.createResult(ProviderHttpResponse(403, secretBody))

        assertEquals(429, anthropicError.statusCode)
        assertEquals(403, geminiError.statusCode)
        assertFalse(anthropicError.bodyJson.contains("secret-access-token"))
        assertFalse(geminiError.bodyJson.contains("secret-access-token"))
    }

    @Test
    fun unsupportedMessageContentFailsBeforeTransport() {
        val adapter = AnthropicMessagesOAuthAdapter("https://api.example.com/messages")

        assertThrows(HostLlmRequestException::class.java) {
            adapter.createRequest(
                """{"model":"test","messages":[{"role":"user","content":[{"type":"text","text":"x"}]}]}""",
            )
        }
        assertThrows(HostLlmRequestException::class.java) {
            adapter.createRequest(
                """{"model":"test","messages":[{"role":"user","content":"  "}]}""",
            )
        }
    }

    @Test
    fun jwtMetadataAdapterMergesDisplayClaimsWithoutOverridingExplicitMetadata() {
        val payload = JSONObject()
            .put("chatgpt_account_id", "claim-account")
            .put("chatgpt_plan_type", "pro")
            .put("email", "user@example.com")
            .toString()
        val idToken = "${base64Url("{\"alg\":\"none\"}")}." +
            "${base64Url(payload)}."
        val adapter = JwtClaimMetadataTokenResponseAdapter()

        val token = adapter.parse(
            JSONObject()
                .put("access_token", "access")
                .put("id_token", idToken)
                .put("account_id", "explicit-account"),
            nowMs = 1_000L,
        )

        assertEquals("explicit-account", token.metadata["account_id"])
        assertEquals("pro", token.metadata["plan_type"])
        assertEquals("user@example.com", token.metadata["email"])
    }

    @Test
    fun malformedJwtIsIgnored() {
        val token = JwtClaimMetadataTokenResponseAdapter().parse(
            JSONObject()
                .put("access_token", "access")
                .put("id_token", "not-a-jwt"),
            nowMs = 1_000L,
        )

        assertEquals("access", token.accessToken)
        assertEquals("not-a-jwt", token.metadata["id_token"])
        assertTrue("account_id" !in token.metadata)
    }

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            value.toByteArray(StandardCharsets.UTF_8),
        )
}
