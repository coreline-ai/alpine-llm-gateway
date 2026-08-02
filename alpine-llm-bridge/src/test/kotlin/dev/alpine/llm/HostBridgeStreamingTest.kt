package dev.alpine.llm

import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class HostBridgeStreamingTest {
    private var server: HostBridgeServer? = null

    @After
    fun tearDown() {
        server?.stop()
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
