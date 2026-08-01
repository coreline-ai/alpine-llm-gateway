package dev.alpine.llm.demo.model

import java.util.UUID

enum class ChatRole {
    USER,
    ASSISTANT,
    ERROR,
}

enum class ChatMessageState {
    COMPLETE,
    STREAMING,
    CANCELLED,
    FAILED,
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val state: ChatMessageState = ChatMessageState.COMPLETE,
    val providerProfileId: String? = null,
    val providerLabel: String? = null,
    val model: String? = null,
    val assistantSkillId: String? = null,
    val assistantPersonaId: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
)
