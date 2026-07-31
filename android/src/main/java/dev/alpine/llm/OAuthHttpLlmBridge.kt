package dev.alpine.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class ProviderHttpRequest(
    val url: String,
    val bodyJson: String,
    val headers: Map<String, String> = emptyMap(),
)

data class ProviderHttpResponse(
    val statusCode: Int,
    val bodyJson: String,
)

/**
 * Provider adapters transform protocol data only. They never receive the
 * OAuth credential, which limits accidental token logging or serialization.
 */
interface OAuthProviderHttpAdapter {
    fun createRequest(requestJson: String): ProviderHttpRequest

    fun createResult(response: ProviderHttpResponse): HostLlmResult =
        HostLlmResult(response.bodyJson, response.statusCode)
}

fun interface OAuthHttpTransport {
    suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse
}

/**
 * Adds the OAuth credential after Provider adaptation and performs the Host
 * request. The resulting bridge can be passed directly to [OAuthLlmSession].
 */
class OAuthHttpLlmBridge(
    private val adapter: OAuthProviderHttpAdapter,
    private val transport: OAuthHttpTransport = UrlConnectionOAuthHttpTransport(),
) : HostLlmBridge {
    override suspend fun complete(
        requestJson: String,
        credential: OAuthCredential,
    ): HostLlmResult {
        val adapted = adapter.createRequest(requestJson)
        require(adapted.headers.keys.none { it.equals(AUTHORIZATION, ignoreCase = true) }) {
            "Provider adapter must not set Authorization"
        }
        val authenticated = adapted.copy(
            headers = adapted.headers + (AUTHORIZATION to "${credential.tokenType} ${credential.accessToken}"),
        )
        return adapter.createResult(transport.execute(authenticated))
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}

/**
 * Minimal built-in adapter for OAuth-enabled OpenAI-compatible endpoints.
 * Provider endpoint/model/client registration remain application settings.
 */
class OpenAiCompatibleOAuthAdapter(
    private val completionEndpoint: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : OAuthProviderHttpAdapter {
    init {
        require(completionEndpoint.startsWith("https://")) {
            "completionEndpoint must use HTTPS"
        }
        require(extraHeaders.keys.none { it.equals("Authorization", ignoreCase = true) }) {
            "extraHeaders must not contain Authorization"
        }
    }

    override fun createRequest(requestJson: String): ProviderHttpRequest {
        val body = runCatching { JSONObject(requestJson) }.getOrElse {
            throw HostLlmRequestException("requestJson must be a JSON object")
        }
        body.put("stream", false)
        return ProviderHttpRequest(
            url = completionEndpoint,
            bodyJson = body.toString(),
            headers = extraHeaders,
        )
    }
}

class UrlConnectionOAuthHttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 180_000,
    private val maxResponseBytes: Int = 8 * 1024 * 1024,
) : OAuthHttpTransport {
    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
    }

    override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse =
        withContext(Dispatchers.IO) {
            val url = URL(request.url)
            require(url.protocol == "https") { "OAuth Provider requests must use HTTPS" }
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                request.headers.forEach { (name, value) ->
                    require(name.none { it == '\r' || it == '\n' }) { "invalid HTTP header name" }
                    require(value.none { it == '\r' || it == '\n' }) { "invalid HTTP header value" }
                    connection.setRequestProperty(name, value)
                }
                connection.outputStream.use {
                    it.write(request.bodyJson.toByteArray(StandardCharsets.UTF_8))
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                ProviderHttpResponse(
                    statusCode = status,
                    bodyJson = stream?.use { readLimited(it, maxResponseBytes) }.orEmpty(),
                )
            } finally {
                connection.disconnect()
            }
        }

    private fun readLimited(input: InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IllegalStateException("Provider response exceeds limit")
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }
}
