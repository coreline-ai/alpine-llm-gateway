package dev.alpine.llm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loopback-only HTTP bridge between Alpine and the Android Host.
 *
 * The bridge token is a short-lived local capability token. It is safe to pass
 * into the Alpine process environment because it cannot be exchanged for an
 * upstream Provider credential. OAuth access/refresh tokens remain in Android.
 */
class HostBridgeServer(
    private val port: Int = 0,
    private val maxRequestBytes: Int = 1 * 1024 * 1024,
    private val maxConcurrentRequests: Int = 4,
    private val overloadRetryAfterSeconds: Int = 1,
    private val requestExecutor: suspend (requestJson: String) -> HostLlmResult,
) {
    data class Endpoint(
        val url: String,
        val sessionToken: String,
    )

    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var endpoint: Endpoint? = null

    init {
        require(port in 0..65535) { "port must be between 0 and 65535" }
        require(maxRequestBytes > 0) { "maxRequestBytes must be positive" }
        require(maxConcurrentRequests > 0) { "maxConcurrentRequests must be positive" }
        require(overloadRetryAfterSeconds > 0) {
            "overloadRetryAfterSeconds must be positive"
        }
    }

    @Synchronized
    fun start(): Endpoint {
        endpoint?.let { return it }
        val socket = ServerSocket(port, 8, InetAddress.getByName(LOOPBACK))
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val limiter = RequestLimiter(maxConcurrentRequests)
        val created = Endpoint(
            url = "http://$LOOPBACK:${socket.localPort}",
            sessionToken = newSessionToken(),
        )
        scope = serverScope
        serverSocket = socket
        endpoint = created
        acceptJob = serverScope.launch {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                serverScope.launch { handle(client, created.sessionToken, limiter) }
            }
        }
        return created
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        endpoint = null
        acceptJob?.cancel()
        acceptJob = null
        scope?.cancel()
        scope = null
    }

    private suspend fun handle(
        socket: Socket,
        expectedToken: String,
        limiter: RequestLimiter,
    ) {
        socket.use { client ->
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            try {
                handleRequest(input, output, expectedToken, limiter)
            } catch (error: IllegalArgumentException) {
                respond(
                    output,
                    400,
                    errorJson("invalid_request", error.message ?: "malformed HTTP request"),
                )
            } catch (_: java.net.SocketTimeoutException) {
                respond(output, 400, errorJson("request_timeout", "request was not completed in time"))
            }
        }
    }

    private suspend fun handleRequest(
        input: InputStream,
        output: BufferedOutputStream,
        expectedToken: String,
        limiter: RequestLimiter,
    ) {
        val requestLine = readLine(input, MAX_LINE_BYTES)
            ?: return respond(output, 400, errorJson("invalid_request", "missing request line"))
        val requestParts = requestLine.trim().split(Regex("\\s+"), limit = 3)
        if (requestParts.size != 3 || !requestParts[2].startsWith("HTTP/")) {
            return respond(output, 400, errorJson("invalid_request", "malformed request line"))
        }
        val method = requestParts[0]
        val path = requestParts[1].substringBefore('?')
        val headers = readHeaders(input)

        if (method == "GET" && path == "/healthz") {
            return respond(
                output,
                200,
                JSONObject()
                    .put("status", "ok")
                    .put("active_requests", limiter.active.get())
                    .put("max_concurrent_requests", maxConcurrentRequests)
                    .toString(),
            )
        }
        if (method != "POST" || path != "/v1/chat/completions") {
            return respond(output, 404, errorJson("not_found", "route not found"))
        }
        val requestId = newRequestId()
        fun completionResponse(
            status: Int,
            body: String,
            headers: Map<String, String> = emptyMap(),
        ) = respond(
            output,
            status,
            body,
            headers + (REQUEST_ID_HEADER to requestId),
        )
        if (!authorized(headers["authorization"], expectedToken)) {
            return completionResponse(
                401,
                errorJson("unauthorized", "valid bridge session token required"),
            )
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return completionResponse(
                400,
                errorJson("unsupported_transfer", "chunked request bodies are not supported"),
            )
        }
        val length = headers["content-length"]?.toIntOrNull()
            ?: return completionResponse(
                411,
                errorJson("length_required", "Content-Length is required"),
            )
        if (length <= 0 || length > maxRequestBytes) {
            return completionResponse(
                413,
                errorJson("request_too_large", "request body exceeds the bridge limit"),
            )
        }
        val body = readExactly(input, length)
            ?: return completionResponse(
                400,
                errorJson("invalid_request", "request body ended early"),
            )
        val requestJson = body.toString(StandardCharsets.UTF_8)
        if (runCatching { JSONObject(requestJson) }.isFailure) {
            return completionResponse(
                400,
                errorJson("invalid_json", "request body must be a JSON object"),
            )
        }
        if (!limiter.permits.tryAcquire()) {
            return completionResponse(
                429,
                errorJson("bridge_overloaded", "Host Bridge concurrency limit reached"),
                mapOf("Retry-After" to overloadRetryAfterSeconds.toString()),
            )
        }
        limiter.active.incrementAndGet()
        try {
            val result = requestExecutor(requestJson)
            if (runCatching { JSONObject(result.bodyJson) }.isFailure) {
                completionResponse(
                    502,
                    errorJson("provider_invalid_json", "Provider returned an invalid JSON response"),
                )
            } else {
                completionResponse(result.statusCode, result.bodyJson)
            }
        } catch (_: HostLlmRequestException) {
            completionResponse(400, errorJson("invalid_request", "LLM request is invalid"))
        } catch (_: OAuthRequiredException) {
            completionResponse(401, errorJson("oauth_required", "OAuth login is required"))
        } catch (error: OAuthException) {
            if (error.kind == OAuthFailureKind.STORAGE_INVALIDATED ||
                error.kind == OAuthFailureKind.INVALID_GRANT
            ) {
                completionResponse(
                    401,
                    errorJson("oauth_reauthentication_required", "OAuth login must be renewed"),
                )
            } else {
                completionResponse(
                    502,
                    errorJson("oauth_provider_error", "OAuth provider request failed"),
                )
            }
        } catch (_: Exception) {
            completionResponse(502, errorJson("host_provider_error", "provider request failed"))
        } finally {
            limiter.active.decrementAndGet()
            limiter.permits.release()
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        repeat(MAX_HEADERS) {
            val line = readLine(input, MAX_LINE_BYTES)
                ?: throw IllegalArgumentException("unexpected end of headers")
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator <= 0) throw IllegalArgumentException("malformed HTTP header")
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }
        throw IllegalArgumentException("too many HTTP headers")
    }

    private fun readLine(input: InputStream, maxBytes: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < maxBytes) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.US_ASCII.name())
            if (value == '\n'.code) {
                val result = bytes.toByteArray()
                val length = if (result.isNotEmpty() && result.last() == '\r'.code.toByte()) result.size - 1 else result.size
                return String(result, 0, length, StandardCharsets.US_ASCII)
            }
            bytes.write(value)
        }
        throw IllegalArgumentException("HTTP line is too long")
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) return null
            offset += count
        }
        return result
    }

    private fun authorized(header: String?, expectedToken: String): Boolean {
        val actual = header?.removePrefix("Bearer ") ?: return false
        return MessageDigest.isEqual(
            actual.toByteArray(StandardCharsets.UTF_8),
            expectedToken.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun respond(
        output: BufferedOutputStream,
        status: Int,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            else -> if (status in 200..299) "OK" else "Bad Gateway"
        }
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            extraHeaders.entries.joinToString("") { (name, value) ->
                "$name: $value\r\n"
            } +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject().put("error", JSONObject().put("code", code).put("message", message)).toString()

    private fun newSessionToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun newRequestId(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private data class RequestLimiter(
        val maxConcurrentRequests: Int,
        val permits: Semaphore = Semaphore(maxConcurrentRequests, true),
        val active: AtomicInteger = AtomicInteger(0),
    )

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAX_HEADERS = 64
        const val MAX_LINE_BYTES = 8192
        const val REQUEST_ID_HEADER = "X-Request-Id"
    }
}
