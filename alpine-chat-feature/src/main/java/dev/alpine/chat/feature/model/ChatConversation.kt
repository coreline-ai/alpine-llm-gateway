package dev.alpine.chat.feature.model

import dev.alpine.chat.routing.ChatExecutionMode
import java.util.UUID

enum class ConversationGenerationState {
    IDLE,
    STREAMING,
    COMPLETE,
    CANCELLED,
    FAILED,
}

data class ChatConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = DEFAULT_TITLE,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val selectedProfileId: String? = null,
    val selectedModel: String? = null,
    val executionMode: ChatExecutionMode = ChatExecutionMode.FAST_CHAT,
    val selectedSkillId: String = AssistantSelection.DEFAULT_SKILL_ID,
    val selectedPersonaId: String = AssistantSelection.DEFAULT_PERSONA_ID,
    val generationState: ConversationGenerationState = ConversationGenerationState.IDLE,
    val hasUnreadCompletion: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
) {
    fun isCompletelyEmpty(): Boolean = messages.isEmpty() && draft.isBlank()

    val assistantSelection: AssistantSelection
        get() = AssistantSelection(selectedSkillId, selectedPersonaId)

    fun summary(): ConversationSummary = ConversationSummary(
        id = id,
        title = title,
        preview = ConversationText.preview(messages.lastOrNull()?.text.orEmpty()),
        selectedProfileId = selectedProfileId,
        selectedModel = selectedModel,
        executionMode = executionMode,
        generationState = generationState,
        hasUnreadCompletion = hasUnreadCompletion,
        updatedAtMs = updatedAtMs,
    )

    fun normalizeInterrupted(nowMs: Long): ChatConversation {
        if (
            generationState != ConversationGenerationState.STREAMING &&
            messages.none { it.state == ChatMessageState.STREAMING }
        ) {
            return this
        }
        return copy(
            messages = messages.map { message ->
                if (message.state == ChatMessageState.STREAMING) {
                    message.copy(state = ChatMessageState.CANCELLED)
                } else {
                    message
                }
            },
            generationState = ConversationGenerationState.CANCELLED,
            updatedAtMs = maxOf(updatedAtMs, nowMs),
        )
    }

    companion object {
        const val DEFAULT_TITLE = "New chat"
    }
}

data class ConversationSummary(
    val id: String,
    val title: String,
    val preview: String,
    val selectedProfileId: String?,
    val selectedModel: String?,
    val executionMode: ChatExecutionMode,
    val generationState: ConversationGenerationState,
    val hasUnreadCompletion: Boolean,
    val updatedAtMs: Long,
)

object ConversationText {
    const val MAX_TITLE_CODE_POINTS = 48
    const val MAX_RENAMED_TITLE_CODE_POINTS = 80
    const val MAX_PREVIEW_CODE_POINTS = 96

    fun automaticTitle(firstUserText: String): String = normalizedLimited(
        value = firstUserText,
        maxCodePoints = MAX_TITLE_CODE_POINTS,
        fallback = ChatConversation.DEFAULT_TITLE,
    )

    fun renamedTitle(value: String): String = normalizedLimited(
        value = value,
        maxCodePoints = MAX_RENAMED_TITLE_CODE_POINTS,
        fallback = ChatConversation.DEFAULT_TITLE,
    )

    fun preview(value: String): String = normalizedLimited(
        value = value,
        maxCodePoints = MAX_PREVIEW_CODE_POINTS,
        fallback = "No messages yet",
    )

    private fun normalizedLimited(
        value: String,
        maxCodePoints: Int,
        fallback: String,
    ): String {
        val normalized = value.trim().replace(WHITESPACE, " ")
        if (normalized.isEmpty()) return fallback
        val count = normalized.codePointCount(0, normalized.length)
        if (count <= maxCodePoints) return normalized
        val end = normalized.offsetByCodePoints(0, maxCodePoints)
        return normalized.substring(0, end).trimEnd() + "…"
    }

    private val WHITESPACE = Regex("\\s+")
}
