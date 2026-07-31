package dev.alpine.llm

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class StreamingBridgeTest {
    private var server: HostBridgeServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun parsesMultilineSseAndDoneSentinel() = runBlocking {
        val raw = """
            : keepalive
            event: content
            data: {"text":"hello",
            data: "part":2}

            data: [DONE]

        """.trimIndent()

        val events = SseEventParser.parse(
            ByteArrayInputStream(raw.toByteArray(StandardCharsets.UTF_8)),
            maxEventBytes = 1024,
            maxTotalBytes = 4096,
        ).toList()

        assertEquals(2, events.size)
        assertEquals("content", events[0].event)
        assertEquals("{\"text\":\"hello\",\n\"part\":2}", events[0].data)
        assertEquals("[DONE]", events[1].data)
    }

    @Test
    fun rejectsOversizedSseEvent() {
        val raw = "data: ${"x".repeat(32)}\n\n"

        val error = runCatching {
            runBlocking {
                SseEventParser.parse(
                    ByteArrayInputStream(raw.toByteArray(StandardCharsets.UTF_8)),
                    maxEventBytes = 8,
                    maxTotalBytes = 1024,
                ).toList()
            }
        }.exceptionOrNull()

        assertTrue(error is ProviderStreamException)
    }

    @Test
    fun normalizesProviderStreamEvents() {
        val openAi = OpenAiCompatibleOAuthAdapter("https://provider.example.com/completions")
        val openAiRequest = openAi.createStreamRequest("""{"model":"m","messages":[]}""")
        val openAiEvent = openAi.createStreamEvent(
            ProviderSseEvent(
                null,
                """{"choices":[{"delta":{"content":"oa"},"finish_reason":null}]}""",
            ),
        )
        assertTrue(JSONObject(openAiRequest.bodyJson).getBoolean("stream"))
        assertEquals("oa", JSONObject(requireNotNull(openAiEvent).dataJson).getString("text"))

        val anthropic = AnthropicMessagesOAuthAdapter("https://provider.example.com/messages")
        val anthropicRequest = anthropic.createStreamRequest(
            """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
        )
        val anthropicEvent = anthropic.createStreamEvent(
            ProviderSseEvent(
                "content_block_delta",
                """{"delta":{"type":"text_delta","text":"an"}}""",
            ),
        )
        assertTrue(JSONObject(anthropicRequest.bodyJson).getBoolean("stream"))
        assertEquals("an", JSONObject(requireNotNull(anthropicEvent).dataJson).getString("text"))

        val gemini = GeminiGenerateContentOAuthAdapter(
            "https://provider.example.com/models/{model}:generateContent",
        )
        val geminiRequest = gemini.createStreamRequest(
            """{"model":"gemini/test","messages":[{"role":"user","content":"hi"}]}""",
        )
        val geminiEvent = gemini.createStreamEvent(
            ProviderSseEvent(
                null,
                """{"candidates":[{"content":{"parts":[{"text":"ge"}]}}]}""",
            ),
        )
        assertTrue(geminiRequest.url.contains(":streamGenerateContent?alt=sse"))
        assertEquals("ge", JSONObject(requireNotNull(geminiEvent).dataJson).getString("text"))
    }

    @Test
    fun oauthBridgeAddsCredentialAfterStreamAdaptation() = runBlocking {
        var captured: ProviderHttpRequest? = null
        val adapter = OpenAiCompatibleOAuthAdapter("https://provider.example.com/completions")
        val bridge = OAuthHttpLlmBridge(
            adapter = adapter,
            streamingTransport = OAuthStreamingHttpTransport { request ->
                captured = request
                ProviderHttpStreamResponse(
                    statusCode = 200,
                    events = flowOf(
                        ProviderSseEvent(
                            null,
                            """{"choices":[{"delta":{"content":"ok"}}]}""",
                        ),
                    ),
                )
            },
            transport = OAuthHttpTransport { error("non-stream transport must not run") },
        )

        val result = bridge.stream(
            """{"model":"m","messages":[],"stream":true}""",
            OAuthCredential("stream-secret"),
        )
        val events = result.events.toList()

        assertEquals("Bearer stream-secret", captured?.headers?.get("Authorization"))
        assertFalse(adapter.createStreamRequest("{}").headers.containsKey("Authorization"))
        assertEquals("ok", JSONObject(events.single().dataJson).getString("text"))
    }

    @Test
    fun hostBridgeWritesNormalizedSseEnvelope() {
        val instance = HostBridgeServer(
            streamExecutor = {
                HostLlmStreamResult(events = flowOf(HostLlmStreamEvent.delta("hello")))
            },
            requestExecutor = { HostLlmResult("{}") },
        )
        server = instance
        val endpoint = instance.start()
        val connection = URL("${endpoint.url}/v1/chat/completions")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setRequestProperty("Authorization", "Bearer ${endpoint.sessionToken}")
        connection.outputStream.use {
            it.write(
                """{"model":"test","messages":[],"stream":true}"""
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }

        assertEquals(200, connection.responseCode)
        assertTrue(connection.contentType.startsWith("text/event-stream"))
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        assertTrue(body.contains("\"type\":\"start\""))
        assertTrue(body.contains("\"type\":\"delta\""))
        assertTrue(body.contains("\"text\":\"hello\""))
        assertTrue(body.contains("\"type\":\"done\""))
        assertTrue(body.contains("data: [DONE]"))
    }
}
