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

        return JSONObject()
            .put("model", model)
            .put("messages", providerMessages)
            .put("max_tokens", maxTokens)
            .put("stream", true)
            .toString()
    }
}
