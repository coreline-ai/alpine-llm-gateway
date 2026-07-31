package dev.alpine.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class HostBridgeServerInstrumentedTest {
    private var server: HostBridgeServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun loopbackCompletionRequiresCurrentSessionToken() {
        val endpoint = startServer()

        assertEquals(401, post(endpoint.url, "{}", null).status)
        assertEquals(401, post(endpoint.url, "{}", "wrong-session-token").status)

        val response = post(
            endpoint.url,
            """{"model":"device-test","messages":[]}""",
            endpoint.sessionToken,
        )

        assertEquals(200, response.status)
        assertTrue(response.body.contains("device-ok"))
        assertFalse(response.body.contains(endpoint.sessionToken))
        assertTrue(response.header("X-Request-Id").isNotBlank())
    }

    @Test
    fun loopbackStreamingReturnsNormalizedSseAndDoneMarker() {
        val endpoint = startServer(
            streamExecutor = {
                HostLlmStreamResult(
                    events = flowOf(
                        HostLlmStreamEvent.delta("device-"),
                        HostLlmStreamEvent.delta("stream", finishReason = "stop"),
                    ),
                )
            },
        )

        val response = post(
            endpoint.url,
            """{"model":"device-test","messages":[],"stream":true}""",
            endpoint.sessionToken,
        )

        assertEquals(200, response.status)
        assertTrue(response.header("Content-Type").startsWith("text/event-stream"))
        assertTrue(response.body.contains("\"type\":\"start\""))
        assertTrue(response.body.contains("\"text\":\"device-\""))
        assertTrue(response.body.contains("\"text\":\"stream\""))
        assertTrue(response.body.contains("\"type\":\"done\""))
        assertTrue(response.body.contains("data: [DONE]"))
        assertFalse(response.body.contains(endpoint.sessionToken))
    }

    @Test
    fun restartingBridgeRotatesSessionTokenAndAcceptsNewRequests() {
        val instance = HostBridgeServer {
            HostLlmResult("""{"choices":[{"message":{"content":"device-ok"}}]}""")
        }
        server = instance
        val first = instance.start()
        instance.stop()

        val second = instance.start()

        assertNotEquals(first.sessionToken, second.sessionToken)
        assertEquals(401, post(second.url, "{}", first.sessionToken).status)
        assertEquals(200, post(second.url, "{}", second.sessionToken).status)
    }

    private fun startServer(
        streamExecutor: (suspend (String) -> HostLlmStreamResult)? = null,
    ): HostBridgeServer.Endpoint {
        val instance = HostBridgeServer(
            streamExecutor = streamExecutor,
            requestExecutor = {
                HostLlmResult("""{"choices":[{"message":{"content":"device-ok"}}]}""")
            },
        )
        server = instance
        return instance.start()
    }

    private fun post(baseUrl: String, body: String, token: String?): Response {
        val connection = URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Content-Type", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        return try {
            connection.outputStream.use {
                it.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { requireNotNull(it.key).lowercase() }
                .mapValues { it.value.firstOrNull().orEmpty() }
            Response(status, responseBody, headers)
        } finally {
            connection.disconnect()
        }
    }

    private data class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, String>,
    ) {
        fun header(name: String): String = headers[name.lowercase()].orEmpty()
    }
}
