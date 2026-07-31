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
) {
    data class Completion(
        val model: String,
        val text: String,
        val inputTokens: Int,
        val outputTokens: Int,
    )

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

    fun health(): JSONObject = get("/healthz")

    private fun get(path: String): JSONObject {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        addAuthorization(connection)
        return readJson(connection)
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        addAuthorization(connection)
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readJson(connection)
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
