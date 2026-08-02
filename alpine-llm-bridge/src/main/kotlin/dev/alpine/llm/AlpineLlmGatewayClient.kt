package dev.alpine.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class GatewayClientErrorCode {
    INVALID_ENDPOINT,
    REQUEST_TOO_LARGE,
    HTTP_ERROR,
    RESPONSE_TOO_LARGE,
    MALFORMED_JSON,
    MALFORMED_SSE,
    STREAM_TOO_LARGE,
    CANCELLED,
    CONNECTION_FAILED,
}

class GatewayClientException(
    val errorCode: GatewayClientErrorCode,
    val statusCode: Int? = null,
) : RuntimeException(errorCode.name)

/** Safe, bounded client for a gateway running on Android loopback. */
class AlpineLlmGatewayClient(
    baseUrl: String = "http://127.0.0.1:8787",
    private val sessionToken: String? = null,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 180_000,
    private val maxResponseBytes: Int = 8 * 1024 * 1024,
    private val maxStreamEventBytes: Int = 1 * 1024 * 1024,
    private val maxStreamBytes: Int = 32 * 1024 * 1024,
    private val maxRequestBytes: Int = 2 * 1024 * 1024,
) {
    data class Completion(
        val model: String,
        val text: String,
        val inputTokens: Int,
        val outputTokens: Int,
    )

    data class StreamEvent(
        val type: String,
        val text: String = "",
        val finishReason: String? = null,
        val usage: JSONObject? = null,
    )

    private val baseUrl: String

    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        require(maxStreamEventBytes > 0) { "maxStreamEventBytes must be positive" }
        require(maxStreamBytes > 0) { "maxStreamBytes must be positive" }
        require(maxRequestBytes > 0) { "maxRequestBytes must be positive" }
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        require(
            uri != null &&
                uri.scheme == "http" &&
                uri.host in setOf("127.0.0.1", "::1") &&
                uri.port in 1..65535 &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null,
        ) { GatewayClientErrorCode.INVALID_ENDPOINT.name }
        this.baseUrl = baseUrl.trimEnd('/')
    }

    fun complete(
        model: String,
        prompt: String,
        maxTokens: Int = 1024,
        temperature: Double? = null,
    ): Completion {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_tokens", maxTokens)
        if (temperature != null) body.put("temperature", temperature)

        return parseCompletion(post("/v1/chat/completions", body), model)
    }

    /** Sends an already-normalized bounded chat request without dropping history or system input. */
    fun completeJson(requestJson: String): Completion {
        val body = parseRequest(requestJson).put("stream", false)
        val model = body.optString("model").takeIf { it.isNotBlank() }
            ?: throw GatewayClientException(GatewayClientErrorCode.MALFORMED_JSON)
        return parseCompletion(post("/v1/chat/completions", body), model)
    }

    private fun parseCompletion(response: JSONObject, fallbackModel: String): Completion {
        return try {
            val choice = response.getJSONArray("choices").getJSONObject(0)
            val usage = response.optJSONObject("usage")
            Completion(
                model = response.optString("model", fallbackModel),
                text = choice.getJSONObject("message").optString("content"),
                inputTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                outputTokens = usage?.optInt("completion_tokens", 0) ?: 0,
            )
        } catch (_: Exception) {
            throw GatewayClientException(GatewayClientErrorCode.MALFORMED_JSON)
        }
    }

    fun stream(
        model: String,
        prompt: String,
        maxTokens: Int = 1024,
        temperature: Double? = null,
        isCancelled: () -> Boolean = { false },
        onEvent: (StreamEvent) -> Unit,
    ) {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", prompt)),
            )
            .put("max_tokens", maxTokens)
            .put("stream", true)
        temperature?.let { body.put("temperature", it) }

        streamBody(body, isCancelled, onEvent)
    }

    /** Streams an already-normalized bounded chat request and forces the protocol stream flag. */
    fun streamJson(
        requestJson: String,
        isCancelled: () -> Boolean = { false },
        onEvent: (StreamEvent) -> Unit,
    ) {
        val body = parseRequest(requestJson).put("stream", true)
        if (body.optString("model").isBlank()) {
            throw GatewayClientException(GatewayClientErrorCode.MALFORMED_JSON)
        }
        streamBody(body, isCancelled, onEvent)
    }

    private fun streamBody(
        body: JSONObject,
        isCancelled: () -> Boolean,
        onEvent: (StreamEvent) -> Unit,
    ) {
        val connection = open("/v1/chat/completions")
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            addAuthorization(connection)
            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.use { readLimited(it, maxResponseBytes) }
                throw GatewayClientException(GatewayClientErrorCode.HTTP_ERROR, status)
            }
            var totalBytes = 0
            val dataLines = mutableListOf<String>()
            fun dispatch() {
                if (dataLines.isEmpty()) return
                val data = dataLines.joinToString("\n")
                dataLines.clear()
                if (data == "[DONE]") return
                val json = runCatching { JSONObject(data) }.getOrElse {
                    throw GatewayClientException(GatewayClientErrorCode.MALFORMED_SSE)
                }
                onEvent(
                    StreamEvent(
                        type = json.optString("type"),
                        text = json.optString("text"),
                        finishReason = json.optString("finish_reason").ifBlank { null },
                        usage = json.optJSONObject("usage"),
                    ),
                )
            }
            connection.inputStream.use { input ->
                while (true) {
                    if (isCancelled()) throw GatewayClientException(GatewayClientErrorCode.CANCELLED)
                    val rawLine = readLineLimited(input, maxStreamEventBytes) ?: break
                    val lineBytes = rawLine.size + 1
                    totalBytes += lineBytes
                    if (totalBytes > maxStreamBytes) {
                        throw GatewayClientException(GatewayClientErrorCode.STREAM_TOO_LARGE)
                    }
                    val line = decodeUtf8(rawLine)
                    if (line.isEmpty()) {
                        dispatch()
                    } else if (line.startsWith("data:")) {
                        val value = line.substring(5).trimStart()
                        val eventBytes = dataLines.sumOf { it.toByteArray(Charsets.UTF_8).size } +
                            value.toByteArray(Charsets.UTF_8).size
                        if (eventBytes > maxStreamEventBytes) {
                            throw GatewayClientException(GatewayClientErrorCode.MALFORMED_SSE)
                        }
                        dataLines += value
                    }
                }
                dispatch()
            }
        } catch (error: GatewayClientException) {
            throw error
        } catch (_: Exception) {
            throw GatewayClientException(GatewayClientErrorCode.CONNECTION_FAILED)
        } finally {
            connection.disconnect()
        }
    }

    fun health(): JSONObject = get("/healthz")

    fun models(): JSONObject = get("/v1/models")

    private fun parseRequest(requestJson: String): JSONObject {
        val bytes = requestJson.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > maxRequestBytes) {
            throw GatewayClientException(GatewayClientErrorCode.REQUEST_TOO_LARGE)
        }
        return runCatching { JSONObject(requestJson) }.getOrElse {
            throw GatewayClientException(GatewayClientErrorCode.MALFORMED_JSON)
        }
    }

    private fun get(path: String): JSONObject {
        val connection = open(path)
        return try {
            connection.requestMethod = "GET"
            addAuthorization(connection)
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val connection = open(path)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            addAuthorization(connection)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection = try {
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
        }
    } catch (_: Exception) {
        throw GatewayClientException(GatewayClientErrorCode.CONNECTION_FAILED)
    }

    private fun addAuthorization(connection: HttpURLConnection) {
        sessionToken?.takeIf { it.isNotBlank() }?.let {
            connection.setRequestProperty("Authorization", "Bearer $it")
        }
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.use { readLimited(it, maxResponseBytes) } ?: byteArrayOf()
        if (status !in 200..299) {
            throw GatewayClientException(GatewayClientErrorCode.HTTP_ERROR, status)
        }
        return runCatching { JSONObject(raw.toString(Charsets.UTF_8)) }.getOrElse {
            throw GatewayClientException(GatewayClientErrorCode.MALFORMED_JSON)
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) {
                throw GatewayClientException(GatewayClientErrorCode.RESPONSE_TOO_LARGE)
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readLineLimited(input: InputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (output.size() == 0) null else output.toByteArray()
            if (value == '\n'.code) {
                val raw = output.toByteArray()
                return if (raw.lastOrNull() == '\r'.code.toByte()) raw.copyOf(raw.size - 1) else raw
            }
            if (output.size() >= limit) {
                throw GatewayClientException(GatewayClientErrorCode.MALFORMED_SSE)
            }
            output.write(value)
        }
    }

    private fun decodeUtf8(raw: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw))
            .toString()
    } catch (_: Exception) {
        throw GatewayClientException(GatewayClientErrorCode.MALFORMED_SSE)
    }
}
