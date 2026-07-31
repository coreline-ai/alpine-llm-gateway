package dev.alpine.llm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Android-side client for a gateway running at 127.0.0.1.
 * Call these methods from Dispatchers.IO or another background thread.
 */
class AlpineLlmGatewayClient(
    private val baseUrl: String = "http://127.0.0.1:8787",
    private val sessionToken: String? = null,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 180_000,
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

    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
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

        val response = post("/v1/chat/completions", body)
        val choice = response.getJSONArray("choices").getJSONObject(0)
        val usage = response.optJSONObject("usage")
        return Completion(
            model = response.optString("model", model),
            text = choice.getJSONObject("message").optString("content"),
            inputTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
            outputTokens = usage?.optInt("completion_tokens", 0) ?: 0,
        )
    }

    fun stream(
        model: String,
        prompt: String,
        maxTokens: Int = 1024,
        temperature: Double? = null,
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
                connection.errorStream?.close()
                throw IllegalStateException("Alpine LLM Gateway HTTP $status")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val dataLines = mutableListOf<String>()
                fun dispatch() {
                    if (dataLines.isEmpty()) return
                    val data = dataLines.joinToString("\n")
                    dataLines.clear()
                    if (data == "[DONE]") return
                    val json = JSONObject(data)
                    onEvent(
                        StreamEvent(
                            type = json.optString("type"),
                            text = json.optString("text"),
                            finishReason = json.optString("finish_reason").ifBlank { null },
                            usage = json.optJSONObject("usage"),
                        ),
                    )
                }
                while (true) {
                    val line = reader.readLine()
                    if (line == null) {
                        dispatch()
                        break
                    }
                    if (line.isEmpty()) {
                        dispatch()
                    } else if (line.startsWith("data:")) {
                        dataLines += line.substring(5).trimStart()
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun health(): JSONObject = get("/healthz")

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

    private fun open(path: String): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
        }

    private fun addAuthorization(connection: HttpURLConnection) {
        sessionToken?.takeIf { it.isNotBlank() }?.let {
            connection.setRequestProperty("Authorization", "Bearer $it")
        }
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Alpine LLM Gateway HTTP ${connection.responseCode}: $text")
        }
        return JSONObject(text)
    }
}
