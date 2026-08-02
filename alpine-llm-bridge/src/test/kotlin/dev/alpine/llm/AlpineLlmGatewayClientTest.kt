package dev.alpine.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class AlpineLlmGatewayClientTest {
    @Test
    fun `http error body is consumed but never exposed`() = withResponse(
        status = "500 Internal Server Error",
        body = "provider-secret-body",
    ) { baseUrl ->
        val error = assertThrows(GatewayClientException::class.java) {
            AlpineLlmGatewayClient(baseUrl).health()
        }

        assertEquals(GatewayClientErrorCode.HTTP_ERROR, error.errorCode)
        assertEquals(500, error.statusCode)
        assertFalse(error.message.orEmpty().contains("provider-secret-body"))
    }

    @Test
    fun `bounded json response rejects oversized payload`() = withResponse(
        body = "x".repeat(256),
    ) { baseUrl ->
        val error = assertThrows(GatewayClientException::class.java) {
            AlpineLlmGatewayClient(baseUrl, maxResponseBytes = 32).health()
        }

        assertEquals(GatewayClientErrorCode.RESPONSE_TOO_LARGE, error.errorCode)
    }

    @Test
    fun `malformed json response uses a closed error code`() = withResponse(body = "not-json") { baseUrl ->
        val error = assertThrows(GatewayClientException::class.java) {
            AlpineLlmGatewayClient(baseUrl).health()
        }

        assertEquals(GatewayClientErrorCode.MALFORMED_JSON, error.errorCode)
    }

    @Test
    fun `malformed sse json is rejected without returning raw data`() = withResponse(
        contentType = "text/event-stream",
        body = "data: provider-secret-not-json\n\n",
    ) { baseUrl ->
        val error = assertThrows(GatewayClientException::class.java) {
            AlpineLlmGatewayClient(baseUrl).stream("test", "hello") { }
        }

        assertEquals(GatewayClientErrorCode.MALFORMED_SSE, error.errorCode)
        assertFalse(error.message.orEmpty().contains("provider-secret"))
    }

    @Test
    fun `total sse limit is enforced`() = withResponse(
        contentType = "text/event-stream",
        body = buildString {
            repeat(6) { append("data: {\"type\":\"delta\",\"text\":\"1234567890\"}\n\n") }
        },
    ) { baseUrl ->
        val error = assertThrows(GatewayClientException::class.java) {
            AlpineLlmGatewayClient(
                baseUrl,
                maxStreamEventBytes = 128,
                maxStreamBytes = 160,
            ).stream("test", "hello") { }
        }

        assertEquals(GatewayClientErrorCode.STREAM_TOO_LARGE, error.errorCode)
    }

    @Test
    fun `normalized json request is bounded before opening a connection`() {
        val error = assertThrows(GatewayClientException::class.java) {
            AlpineLlmGatewayClient(
                baseUrl = "http://127.0.0.1:8787",
                maxRequestBytes = 32,
            ).streamJson(
                """{"model":"test","messages":[{"role":"user","content":"${"x".repeat(64)}"}]}""",
            ) { }
        }

        assertEquals(GatewayClientErrorCode.REQUEST_TOO_LARGE, error.errorCode)
    }

    @Test
    fun `normalized json request rejects malformed input without raw text`() {
        val error = assertThrows(GatewayClientException::class.java) {
            AlpineLlmGatewayClient("http://127.0.0.1:8787").streamJson(
                "provider-secret-not-json",
            ) { }
        }

        assertEquals(GatewayClientErrorCode.MALFORMED_JSON, error.errorCode)
        assertFalse(error.message.orEmpty().contains("provider-secret"))
    }

    private fun withResponse(
        status: String = "200 OK",
        contentType: String = "application/json",
        body: String,
        block: (String) -> Unit,
    ) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread(name = "gateway-client-test") {
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toInt()
                    }
                }
                repeat(contentLength) { reader.read() }
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 $status\r\n" +
                                "Content-Type: $contentType\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(StandardCharsets.US_ASCII),
                    )
                    write(bytes)
                    flush()
                }
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}")
        } finally {
            server.close()
            worker.join(2_000)
        }
    }
}
