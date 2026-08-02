package dev.alpine.chat.feature.llm

import dev.alpine.chat.feature.model.ChatMessage
import dev.alpine.chat.feature.model.ChatRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestBuilderTest {
    @Test
    fun `builds a normalized streaming conversation request`() {
        val request = JSONObject(
            ChatRequestBuilder.build(
                model = "demo-model",
                messages = listOf(
                    ChatMessage(role = ChatRole.USER, text = "Hello"),
                    ChatMessage(role = ChatRole.ASSISTANT, text = "Hi"),
                    ChatMessage(role = ChatRole.ERROR, text = "Local error"),
                    ChatMessage(role = ChatRole.USER, text = "Continue"),
                ),
            ),
        )

        assertEquals("demo-model", request.getString("model"))
        assertEquals(1024, request.getInt("max_tokens"))
        assertTrue(request.getBoolean("stream"))
        val messages = request.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("Continue", messages.getJSONObject(2).getString("content"))
    }
}
