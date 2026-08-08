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
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertFalse(request.headers.containsKey("anthropic-beta"))
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
    fun invalidMessageContentFailsBeforeTransport() {
        val adapter = AnthropicMessagesOAuthAdapter("https://api.example.com/messages")

        assertThrows(HostLlmRequestException::class.java) {
            adapter.createRequest(
                """
                {
                  "model":"test",
                  "messages":[{
                    "role":"user",
                    "content":[{"type":"image_url","image_url":{"url":"https://example.com/a.png"}}]
                  }]
                }
                """.trimIndent(),
            )
        }
        assertThrows(HostLlmRequestException::class.java) {
            adapter.createRequest(
                """{"model":"test","messages":[{"role":"user","content":"  "}]}""",
            )
        }
    }

    @Test
    fun mapsInlineImageToolsCallsAndResults() {
        val inlineImage = "data:image/png;base64,${Base64.getEncoder().encodeToString("png".toByteArray())}"
        val requestJson = """
            {
              "model":"tool-model",
              "messages":[
                {"role":"user","content":[
                  {"type":"text","text":"inspect"},
                  {"type":"image_url","image_url":{"url":"$inlineImage"}}
                ]},
                {"role":"assistant","content":null,"tool_calls":[{
                  "id":"call_1","type":"function",
                  "function":{"name":"lookup","arguments":"{\"key\":\"value\"}"}
                }]},
                {"role":"tool","tool_call_id":"call_1","name":"lookup","content":"{\"result\":1}"}
              ],
              "tools":[{
                "type":"function",
                "function":{
                  "name":"lookup",
                  "description":"Lookup",
                  "parameters":{"type":"object","properties":{"key":{"type":"string"}}}
                }
              }],
              "tool_choice":{"type":"function","function":{"name":"lookup"}}
            }
        """.trimIndent()

        val anthropic = JSONObject(
            AnthropicMessagesOAuthAdapter("https://api.example.com/messages")
                .createRequest(requestJson).bodyJson,
        )
        val userContent = anthropic.getJSONArray("messages")
            .getJSONObject(0).getJSONArray("content")
        assertEquals("image", userContent.getJSONObject(1).getString("type"))
        assertEquals(
            "tool_use",
            anthropic.getJSONArray("messages").getJSONObject(1)
                .getJSONArray("content").getJSONObject(0).getString("type"),
        )
        assertEquals(
            "tool_result",
            anthropic.getJSONArray("messages").getJSONObject(2)
                .getJSONArray("content").getJSONObject(0).getString("type"),
        )
        assertEquals("lookup", anthropic.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertEquals("tool", anthropic.getJSONObject("tool_choice").getString("type"))

        val gemini = JSONObject(
            GeminiGenerateContentOAuthAdapter(
                "https://api.example.com/models/{model}:generateContent",
            ).createRequest(requestJson).bodyJson,
        )
        assertEquals(
            "image/png",
            gemini.getJSONArray("contents").getJSONObject(0)
                .getJSONArray("parts").getJSONObject(1)
                .getJSONObject("inlineData").getString("mimeType"),
        )
        assertEquals(
            "lookup",
            gemini.getJSONArray("contents").getJSONObject(1)
                .getJSONArray("parts").getJSONObject(0)
                .getJSONObject("functionCall").getString("name"),
        )
        assertEquals(
            "lookup",
            gemini.getJSONArray("contents").getJSONObject(2)
                .getJSONArray("parts").getJSONObject(0)
                .getJSONObject("functionResponse").getString("name"),
        )
        assertEquals(
            "ANY",
            gemini.getJSONObject("toolConfig").getJSONObject("functionCallingConfig")
                .getString("mode"),
        )
    }

    @Test
    fun normalizesProviderToolCalls() {
        val anthropic = AnthropicMessagesOAuthAdapter("https://api.example.com/messages")
            .createResult(
                ProviderHttpResponse(
                    200,
                    """
                    {
                      "id":"m1","model":"claude","stop_reason":"tool_use",
                      "content":[{"type":"tool_use","id":"call_1","name":"lookup","input":{"q":"x"}}],
                      "usage":{"input_tokens":1,"output_tokens":2}
                    }
                    """.trimIndent(),
                ),
            )
        val anthropicCall = JSONObject(anthropic.bodyJson)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("lookup", anthropicCall.getJSONObject("function").getString("name"))
        assertEquals("""{"q":"x"}""", anthropicCall.getJSONObject("function").getString("arguments"))

        val gemini = GeminiGenerateContentOAuthAdapter(
            "https://api.example.com/models/{model}:generateContent",
        ).createResult(
            ProviderHttpResponse(
                200,
                """
                {
                  "responseId":"r1","modelVersion":"gemini",
                  "candidates":[{
                    "content":{"parts":[{"functionCall":{"name":"lookup","args":{"q":"x"}}}]},
                    "finishReason":"STOP"
                  }]
                }
                """.trimIndent(),
            ),
        )
        val geminiCall = JSONObject(gemini.bodyJson)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("lookup", geminiCall.getJSONObject("function").getString("name"))
    }

    @Test
    fun rejectsMalformedToolArgumentsAndOversizedInlineImage() {
        val adapter = GeminiGenerateContentOAuthAdapter(
            "https://api.example.com/models/{model}:generateContent",
        )
        assertThrows(HostLlmRequestException::class.java) {
            adapter.createRequest(
                """
                {
                  "model":"m",
                  "messages":[{
                    "role":"assistant","content":null,
                    "tool_calls":[{
                      "id":"c","type":"function",
                      "function":{"name":"lookup","arguments":"not-json"}
                    }]
                  }]
                }
                """.trimIndent(),
            )
        }

        val large = Base64.getEncoder().encodeToString(ByteArray(5 * 1024 * 1024 + 1))
        assertThrows(HostLlmRequestException::class.java) {
            adapter.createRequest(
                """
                {
                  "model":"m",
                  "messages":[{
                    "role":"user",
                    "content":[{
                      "type":"image_url",
                      "image_url":{"url":"data:image/png;base64,$large"}
                    }]
                  }]
                }
                """.trimIndent(),
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
        assertTrue("id_token" !in token.metadata)
        assertTrue("account_id" !in token.metadata)
    }

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            value.toByteArray(StandardCharsets.UTF_8),
        )
}
