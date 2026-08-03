package dev.alpine.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HostBridgeServerTest {
    private var server: HostBridgeServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun rejectsCompletionWithoutSessionToken() {
        val endpoint = startServer()

        val response = post(endpoint.url, """{"model":"test"}""")

        assertEquals(401, response.status)
        assertTrue(response.body.contains("unauthorized"))
        assertTrue(response.header("X-Request-Id").isNotBlank())
    }

    @Test
    fun forwardsAuthorizedJsonToExecutor() {
        var forwarded: String? = null
        val endpoint = startServer { request ->
            forwarded = request
            HostLlmResult("""{"choices":[{"message":{"content":"ok"}}]}""")
        }
        val body = """{"model":"test","messages":[]}"""

        val response = post(endpoint.url, body, endpoint.sessionToken)

        assertEquals(200, response.status)
        assertEquals(body, forwarded)
        assertTrue(response.body.contains("ok"))
        assertTrue(!response.body.contains(endpoint.sessionToken))
        assertTrue(response.header("X-Request-Id").isNotBlank())
    }

    @Test
    fun rejectsOversizedAndInvalidJsonRequests() {
        val endpoint = startServer(maxRequestBytes = 16)

        assertEquals(413, post(endpoint.url, """{"payload":"too-large"}""", endpoint.sessionToken).status)
        assertEquals(400, post(endpoint.url, "not-json", endpoint.sessionToken).status)
    }

    @Test
    fun canRestartWithRotatedSessionToken() {
        val instance = HostBridgeServer { HostLlmResult("{}") }
        server = instance
        val first = instance.start()
        instance.stop()

        val second = instance.start()

        assertNotEquals(first.sessionToken, second.sessionToken)
        assertEquals(401, post(second.url, "{}", first.sessionToken).status)
        assertEquals(200, post(second.url, "{}", second.sessionToken).status)
    }

    @Test
    fun expiredCapabilityIsRejectedWithoutCallingProvider() {
        var now = 1_000L
        val calls = AtomicInteger(0)
        val instance = HostBridgeServer(
            sessionTokenTtlMs = 50,
            clockMs = { now },
        ) {
            calls.incrementAndGet()
            HostLlmResult("{}")
        }
        server = instance
        val endpoint = instance.start()

        assertEquals(200, post(endpoint.url, "{}", endpoint.sessionToken).status)
        now = endpoint.expiresAtEpochMillis
        assertEquals(401, post(endpoint.url, "{}", endpoint.sessionToken).status)
        assertEquals(1, calls.get())
        assertEquals(false, JSONObject(get(endpoint.url).body).getBoolean("capability_valid"))
    }

    @Test
    fun mapsOAuthStorageFailureToReauthenticationWithoutLeakingDetails() {
        val endpoint = startServer {
            throw OAuthException(
                "credential secret-access-token could not be decrypted",
                OAuthFailureKind.STORAGE_INVALIDATED,
            )
        }

        val response = post(endpoint.url, "{}", endpoint.sessionToken)

        assertEquals(401, response.status)
        assertTrue(response.body.contains("oauth_reauthentication_required"))
        assertTrue(!response.body.contains("secret-access-token"))
    }

    @Test
    fun redactsUnexpectedProviderExceptionMessage() {
        val endpoint = startServer {
            throw IllegalStateException("provider included secret-access-token")
        }

        val response = post(endpoint.url, "{}", endpoint.sessionToken)

        assertEquals(502, response.status)
        assertTrue(!response.body.contains("secret-access-token"))
    }

    @Test
    fun rejectsNonJsonProviderResponse() {
        val endpoint = startServer {
            HostLlmResult("<html>upstream error</html>", 502)
        }

        val response = post(endpoint.url, "{}", endpoint.sessionToken)

        assertEquals(502, response.status)
        assertTrue(response.body.contains("provider_error"))
        assertTrue(!response.body.contains("<html>"))
    }

    @Test
    fun provider5xxBodyIsRedactedWhileStatusIsPreserved() {
        val endpoint = startServer {
            HostLlmResult("""{"error":{"message":"provider-secret"}}""", 503)
        }

        val response = post(endpoint.url, "{}", endpoint.sessionToken)

        assertEquals(503, response.status)
        assertTrue(response.body.contains("provider_error"))
        assertTrue(!response.body.contains("provider-secret"))
    }

    @Test
    fun oversizedProviderResponseIsRejectedWithoutReturningItsBody() {
        val endpoint = startServer(maxResponseBytes = 32) {
            HostLlmResult(JSONObject().put("secret", "x".repeat(128)).toString())
        }

        val response = post(endpoint.url, "{}", endpoint.sessionToken)

        assertEquals(502, response.status)
        assertTrue(response.body.contains("provider_response_too_large"))
        assertTrue(!response.body.contains("x".repeat(32)))
    }

    @Test
    fun interruptedRequestDoesNotStopBridge() {
        val endpoint = startServer()
        val url = URL(endpoint.url)
        Socket(url.host, url.port).use { socket ->
            val request = "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: ${url.host}\r\n" +
                "Authorization: Bearer ${endpoint.sessionToken}\r\n" +
                "Content-Length: 100\r\n\r\n{}"
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            socket.shutdownOutput()
        }

        val response = post(endpoint.url, "{}", endpoint.sessionToken)

        assertEquals(200, response.status)
    }

    @Test
    fun overloadReturns429WithoutRunningExtraExecutor() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val endpoint = startServer(
            maxConcurrentRequests = 1,
            overloadRetryAfterSeconds = 3,
        ) {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            HostLlmResult("{}")
        }

        val first = async(Dispatchers.IO) {
            post(endpoint.url, "{}", endpoint.sessionToken)
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            val health = get(endpoint.url)
            val overloaded = post(endpoint.url, "{}", endpoint.sessionToken)

            assertEquals(1, JSONObject(health.body).getInt("active_requests"))
            assertEquals(1, JSONObject(health.body).getInt("max_concurrent_requests"))
            assertEquals(429, overloaded.status)
            assertEquals("3", overloaded.header("Retry-After"))
            assertTrue(overloaded.header("X-Request-Id").isNotBlank())
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
        }
        assertEquals(200, first.await().status)
    }

    @Test
    fun executorExceptionReleasesConcurrencyPermit() {
        val calls = AtomicInteger(0)
        val endpoint = startServer(maxConcurrentRequests = 1) {
            if (calls.incrementAndGet() == 1) error("first request fails")
            HostLlmResult("{}")
        }

        val first = post(endpoint.url, "{}", endpoint.sessionToken)
        val permitReleased = awaitNoActiveRequests(endpoint.url)
        val second = post(endpoint.url, "{}", endpoint.sessionToken)

        assertEquals(502, first.status)
        assertTrue(permitReleased)
        assertEquals(200, second.status)
        assertEquals(2, calls.get())
    }

    @Test
    fun providerTimeoutReturns504AndReleasesPermit() {
        val calls = AtomicInteger(0)
        val endpoint = startServer(
            maxConcurrentRequests = 1,
            requestTimeoutMs = 25,
        ) {
            if (calls.incrementAndGet() == 1) delay(250)
            HostLlmResult("{}")
        }

        val timedOut = post(endpoint.url, "{}", endpoint.sessionToken)
        val permitReleased = awaitNoActiveRequests(endpoint.url)

        assertEquals(504, timedOut.status)
        assertTrue(timedOut.body.contains("request_timeout"))
        assertTrue(permitReleased)
        val recovered = post(endpoint.url, "{}", endpoint.sessionToken)
        assertEquals(200, recovered.status)
    }

    @Test
    fun metricsAndEventsExcludeRequestBodyAndCredential() {
        val events = mutableListOf<GatewayEvent>()
        val endpoint = startServer(
            eventSink = GatewayEventSink(events::add),
        ) {
            HostLlmResult("{}")
        }
        val secretBody = """{"prompt":"body-secret-value"}"""

        assertEquals(200, post(endpoint.url, secretBody, endpoint.sessionToken).status)
        assertTrue(awaitNoActiveRequests(endpoint.url))
        val health = JSONObject(get(endpoint.url).body)
        val serializedEvents = events.joinToString()

        assertEquals(1, health.getLong("successful_requests"))
        assertEquals(0, health.getLong("failed_requests"))
        assertEquals(2, events.size)
        assertTrue(!serializedEvents.contains("body-secret-value"))
        assertTrue(!serializedEvents.contains(endpoint.sessionToken))
    }

    @Test
    fun restartGetsFreshLimiterWhileOldRequestIsCancelled() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val instance = HostBridgeServer(maxConcurrentRequests = 1) {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                awaitCancellation()
            }
            HostLlmResult("{}")
        }
        server = instance
        val firstEndpoint = instance.start()
        val first = async(Dispatchers.IO) {
            runCatching { post(firstEndpoint.url, "{}", firstEndpoint.sessionToken) }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        instance.stop()
        val secondEndpoint = instance.start()
        val second = post(secondEndpoint.url, "{}", secondEndpoint.sessionToken)

        assertEquals(200, second.status)
        first.await()
    }

    private fun startServer(
        maxRequestBytes: Int = 1024,
        maxResponseBytes: Int = 8 * 1024 * 1024,
        maxConcurrentRequests: Int = 4,
        overloadRetryAfterSeconds: Int = 1,
        requestTimeoutMs: Long = 180_000L,
        eventSink: GatewayEventSink = GatewayEventSink.NONE,
        executor: suspend (String) -> HostLlmResult = { HostLlmResult("{}") },
    ): HostBridgeServer.Endpoint {
        val instance = HostBridgeServer(
            maxRequestBytes = maxRequestBytes,
            maxResponseBytes = maxResponseBytes,
            maxConcurrentRequests = maxConcurrentRequests,
            overloadRetryAfterSeconds = overloadRetryAfterSeconds,
            requestTimeoutMs = requestTimeoutMs,
            eventSink = eventSink,
            requestExecutor = executor,
        )
        server = instance
        return instance.start()
    }

    private fun awaitNoActiveRequests(baseUrl: String): Boolean = (1..100).any {
        val released = JSONObject(get(baseUrl).body).getInt("active_requests") == 0
        if (!released) Thread.sleep(10)
        released
    }

    private fun post(baseUrl: String, body: String, token: String? = null): Response {
        val connection = URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Content-Type", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val headers = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { requireNotNull(it.key).lowercase() }
            .mapValues { it.value.firstOrNull().orEmpty() }
        connection.disconnect()
        return Response(status, responseBody, headers)
    }

    private fun get(baseUrl: String): Response {
        val connection = URL("$baseUrl/healthz").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        val status = connection.responseCode
        val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val headers = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { requireNotNull(it.key).lowercase() }
            .mapValues { it.value.firstOrNull().orEmpty() }
        connection.disconnect()
        return Response(status, body, headers)
    }

    private data class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, String>,
    ) {
        fun header(name: String): String = headers[name.lowercase()].orEmpty()
    }
}
