package dev.alpine.llm.demo.llm

import dev.alpine.llm.demo.model.ChatMessage
import dev.alpine.llm.demo.model.ChatRole
import org.json.JSONArray
import org.json.JSONObject

object ChatRequestBuilder {
    fun build(
        model: String,
        messages: List<ChatMessage>,
        maxTokens: Int = 1024,
        systemInstruction: String? = null,
    ): String {
        require(model.isNotBlank()) { "Model is required" }
        require(maxTokens > 0) { "maxTokens must be positive" }

        val providerMessages = JSONArray()
        messages.forEach { message ->
            val role = when (message.role) {
                ChatRole.USER -> "user"
                ChatRole.ASSISTANT -> "assistant"
                ChatRole.ERROR -> return@forEach
            }
            if (message.text.isNotBlank()) {
                providerMessages.put(
                    JSONObject()
                        .put("role", role)
                        .put("content", message.text),
                )
            }
        }
        require(providerMessages.length() > 0) { "At least one chat message is required" }

        val normalizedSystem = systemInstruction?.trim()?.takeIf { it.isNotEmpty() }
        normalizedSystem?.let {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_SYSTEM_INSTRUCTION_BYTES) {
                "system instruction is too large"
            }
            require(!UNSUPPORTED_CONTROL_CHARACTERS.containsMatchIn(it)) {
                "system instruction contains unsupported control characters"
            }
        }

        return JSONObject()
            .put("model", model)
            .put("messages", providerMessages)
            .put("max_tokens", maxTokens)
            .put("stream", true)
            .apply { normalizedSystem?.let { put("system", it) } }
            .toString()
    }

    private const val MAX_SYSTEM_INSTRUCTION_BYTES = 16 * 1024
    private val UNSUPPORTED_CONTROL_CHARACTERS =
        Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
}
