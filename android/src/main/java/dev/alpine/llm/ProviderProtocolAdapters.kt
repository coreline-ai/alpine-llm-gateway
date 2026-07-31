package dev.alpine.llm

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * OpenAI chat-completion JSON to Anthropic Messages API protocol adapter.
 * Endpoint and Provider-specific beta headers remain application settings.
 */
class AnthropicMessagesOAuthAdapter(
    private val messagesEndpoint: String,
    private val anthropicVersion: String = "2023-06-01",
    private val anthropicBeta: String? = null,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : OAuthProviderHttpAdapter {
    init {
        ProviderAdapterJson.requireHttps(messagesEndpoint, "messagesEndpoint")
        ProviderAdapterJson.requireSafeHeaders(extraHeaders)
        require(anthropicVersion.isNotBlank()) { "anthropicVersion must not be blank" }
    }

    override fun createRequest(requestJson: String): ProviderHttpRequest {
        val input = ProviderAdapterJson.parseRequest(requestJson)
        val body = JSONObject()
            .put("model", input.model)
            .put("max_tokens", input.maxTokens)
            .put("messages", JSONArray())
            .put("stream", false)
        input.temperature?.let { body.put("temperature", it) }
        input.stopSequences.takeIf { it.isNotEmpty() }?.let {
            body.put("stop_sequences", JSONArray(it))
        }

        val systems = mutableListOf<String>()
        input.system?.takeIf { it.isNotBlank() }?.let(systems::add)
        val messages = body.getJSONArray("messages")
        input.messages.forEach { message ->
            when (message.role) {
                "system" -> message.content.takeIf { it.isNotBlank() }?.let(systems::add)
                "user", "assistant" -> messages.put(
                    JSONObject()
                        .put("role", message.role)
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "text")
                                    .put("text", message.content),
                            ),
                        ),
                )
                else -> throw HostLlmRequestException(
                    "Anthropic adapter does not support role ${message.role}",
                )
            }
        }
        if (messages.length() == 0) {
            throw HostLlmRequestException("Anthropic request requires user or assistant messages")
        }
        if (systems.isNotEmpty()) body.put("system", systems.joinToString("\n\n"))

        val headers = linkedMapOf("anthropic-version" to anthropicVersion)
        anthropicBeta?.takeIf { it.isNotBlank() }?.let { headers["anthropic-beta"] = it }
        headers.putAll(extraHeaders)
        return ProviderHttpRequest(messagesEndpoint, body.toString(), headers)
    }

    override fun createResult(response: ProviderHttpResponse): HostLlmResult {
        if (response.statusCode !in 200..299) {
            return ProviderAdapterJson.redactedError("anthropic", response.statusCode)
        }
        return runCatching {
            val json = JSONObject(response.bodyJson)
            val text = buildString {
                val content = json.optJSONArray("content") ?: JSONArray()
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "text") append(block.optString("text"))
                }
            }
            val usage = json.optJSONObject("usage")
            val result = ProviderAdapterJson.completion(
                id = json.optString("id"),
                model = json.optString("model"),
                text = text,
                finishReason = when (json.optString("stop_reason")) {
                    "max_tokens" -> "length"
                    "tool_use" -> "tool_calls"
                    else -> "stop"
                },
                promptTokens = usage?.optInt("input_tokens", 0) ?: 0,
                completionTokens = usage?.optInt("output_tokens", 0) ?: 0,
            )
            HostLlmResult(result.toString())
        }.getOrElse {
            ProviderAdapterJson.invalidResponse("anthropic")
        }
    }
}

/**
 * OpenAI chat-completion JSON to Gemini generateContent protocol adapter.
 * [endpointTemplate] must contain `{model}` and use HTTPS.
 */
class GeminiGenerateContentOAuthAdapter(
    private val endpointTemplate: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : OAuthProviderHttpAdapter {
    init {
        ProviderAdapterJson.requireHttps(endpointTemplate, "endpointTemplate")
        require(endpointTemplate.contains("{model}")) {
            "endpointTemplate must contain {model}"
        }
        ProviderAdapterJson.requireSafeHeaders(extraHeaders)
    }

    override fun createRequest(requestJson: String): ProviderHttpRequest {
        val input = ProviderAdapterJson.parseRequest(requestJson)
        val body = JSONObject()
            .put("contents", JSONArray())
        val systems = mutableListOf<String>()
        input.system?.takeIf { it.isNotBlank() }?.let(systems::add)
        val contents = body.getJSONArray("contents")
        input.messages.forEach { message ->
            when (message.role) {
                "system" -> message.content.takeIf { it.isNotBlank() }?.let(systems::add)
                "user", "assistant" -> contents.put(
                    JSONObject()
                        .put("role", if (message.role == "assistant") "model" else "user")
                        .put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", message.content)),
                        ),
                )
                else -> throw HostLlmRequestException(
                    "Gemini adapter does not support role ${message.role}",
                )
            }
        }
        if (contents.length() == 0) {
            throw HostLlmRequestException("Gemini request requires user or assistant messages")
        }
        if (systems.isNotEmpty()) {
            body.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systems.joinToString("\n\n"))),
                ),
            )
        }
        val generationConfig = JSONObject().put("maxOutputTokens", input.maxTokens)
        input.temperature?.let { generationConfig.put("temperature", it) }
        input.stopSequences.takeIf { it.isNotEmpty() }?.let {
            generationConfig.put("stopSequences", JSONArray(it))
        }
        body.put("generationConfig", generationConfig)

        val encodedModel = URLEncoder.encode(
            input.model,
            StandardCharsets.UTF_8.name(),
        ).replace("+", "%20")
        return ProviderHttpRequest(
            url = endpointTemplate.replace("{model}", encodedModel),
            bodyJson = body.toString(),
            headers = extraHeaders,
        )
    }

    override fun createResult(response: ProviderHttpResponse): HostLlmResult {
        if (response.statusCode !in 200..299) {
            return ProviderAdapterJson.redactedError("gemini", response.statusCode)
        }
        return runCatching {
            val json = JSONObject(response.bodyJson)
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                ?: error("candidate is missing")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val text = buildString {
                for (index in 0 until parts.length()) {
                    append(parts.optJSONObject(index)?.optString("text").orEmpty())
                }
            }
            val usage = json.optJSONObject("usageMetadata")
            val result = ProviderAdapterJson.completion(
                id = json.optString("responseId"),
                model = json.optString("modelVersion"),
                text = text,
                finishReason = when (candidate.optString("finishReason")) {
                    "MAX_TOKENS" -> "length"
                    "STOP" -> "stop"
                    else -> candidate.optString("finishReason", "stop").lowercase()
                },
                promptTokens = usage?.optInt("promptTokenCount", 0) ?: 0,
                completionTokens = usage?.optInt("candidatesTokenCount", 0) ?: 0,
            )
            HostLlmResult(result.toString())
        }.getOrElse {
            ProviderAdapterJson.invalidResponse("gemini")
        }
    }
}

internal object ProviderAdapterJson {
    data class Message(val role: String, val content: String)

    data class Request(
        val model: String,
        val messages: List<Message>,
        val system: String?,
        val maxTokens: Int,
        val temperature: Double?,
        val stopSequences: List<String>,
    )

    fun parseRequest(requestJson: String): Request {
        val json = runCatching { JSONObject(requestJson) }.getOrElse {
            throw HostLlmRequestException("request must be a JSON object")
        }
        val model = json.optString("model").takeIf { it.isNotBlank() }
            ?: throw HostLlmRequestException("model is required")
        val sourceMessages = json.optJSONArray("messages")
            ?: throw HostLlmRequestException("messages are required")
        val messages = buildList {
            for (index in 0 until sourceMessages.length()) {
                val message = sourceMessages.optJSONObject(index)
                    ?: throw HostLlmRequestException("message must be an object")
                val role = message.optString("role").lowercase().takeIf { it.isNotBlank() }
                    ?: throw HostLlmRequestException("message role is required")
                val content = message.opt("content") as? String
                    ?: throw HostLlmRequestException("only text message content is supported")
                if (content.isBlank()) {
                    throw HostLlmRequestException("message content must not be blank")
                }
                add(Message(role, content))
            }
        }
        if (messages.isEmpty()) throw HostLlmRequestException("messages must not be empty")
        val maxTokens = json.optInt("max_tokens", 1024)
        if (maxTokens <= 0) throw HostLlmRequestException("max_tokens must be positive")
        val temperature = json.opt("temperature")?.takeUnless { it == JSONObject.NULL }?.let {
            (it as? Number)?.toDouble()
                ?: throw HostLlmRequestException("temperature must be numeric")
        }
        val stopSequences = when (val stop = json.opt("stop")) {
            null, JSONObject.NULL -> emptyList()
            is String -> listOf(stop)
            is JSONArray -> buildList {
                for (index in 0 until stop.length()) {
                    val value = stop.optString(index)
                    if (value.isNotEmpty()) add(value)
                }
            }
            else -> throw HostLlmRequestException("stop must be a string or array")
        }
        return Request(
            model = model,
            messages = messages,
            system = json.optString("system").ifBlank { null },
            maxTokens = maxTokens,
            temperature = temperature,
            stopSequences = stopSequences,
        )
    }

    fun completion(
        id: String,
        model: String,
        text: String,
        finishReason: String,
        promptTokens: Int,
        completionTokens: Int,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("object", "chat.completion")
        .put("model", model)
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put(
                        "message",
                        JSONObject().put("role", "assistant").put("content", text),
                    )
                    .put("finish_reason", finishReason),
            ),
        )
        .put(
            "usage",
            JSONObject()
                .put("prompt_tokens", promptTokens)
                .put("completion_tokens", completionTokens)
                .put("total_tokens", promptTokens + completionTokens),
        )

    fun redactedError(provider: String, statusCode: Int): HostLlmResult =
        HostLlmResult(
            bodyJson = JSONObject()
                .put(
                    "error",
                    JSONObject()
                        .put("code", "provider_error")
                        .put("provider", provider)
                        .put("message", "Provider request failed"),
                )
                .toString(),
            statusCode = statusCode.takeIf { it in 400..599 } ?: 502,
        )

    fun invalidResponse(provider: String): HostLlmResult =
        HostLlmResult(
            bodyJson = JSONObject()
                .put(
                    "error",
                    JSONObject()
                        .put("code", "invalid_provider_response")
                        .put("provider", provider)
                        .put("message", "Provider returned an invalid response"),
                )
                .toString(),
            statusCode = 502,
        )

    fun requireHttps(url: String, name: String) {
        require(url.startsWith("https://")) { "$name must use HTTPS" }
    }

    fun requireSafeHeaders(headers: Map<String, String>) {
        require(headers.keys.none { it.equals("Authorization", ignoreCase = true) }) {
            "extraHeaders must not contain Authorization"
        }
        headers.forEach { (name, value) ->
            require(name.none { it == '\r' || it == '\n' }) { "invalid header name" }
            require(value.none { it == '\r' || it == '\n' }) { "invalid header value" }
        }
    }
}
