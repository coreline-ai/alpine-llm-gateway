package dev.alpine.llm.demo.llm

import android.app.Activity
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.chat.feature.backend.ChatBackendDescriptor
import dev.alpine.chat.feature.backend.ChatBackendSession
import dev.alpine.llm.demo.model.AnthropicProfileDefaults
import dev.alpine.llm.demo.model.CodexProfileDefaults
import dev.alpine.llm.demo.model.GeminiProfileDefaults
import dev.alpine.llm.demo.model.ProviderProfile
import dev.alpine.llm.demo.model.ProviderType
import dev.alpine.llm.demo.model.XaiProfileDefaults

interface ChatCompletionSession : ChatBackendSession {
    val profile: ProviderProfile

    override val descriptor: ChatBackendDescriptor
        get() = profile.toChatBackendDescriptor()

    fun authenticationState(): OAuthAuthenticationState

    suspend fun authorize(activity: Activity)

    fun logout()

    fun cancelAuthorization()
}

fun ProviderProfile.toChatBackendDescriptor(): ChatBackendDescriptor = ChatBackendDescriptor(
    profileId = id,
    label = label,
    model = model,
    modelOptions = when (type) {
        ProviderType.ANTHROPIC -> (AnthropicProfileDefaults.MODELS + model).distinct()
        ProviderType.GEMINI -> (GeminiProfileDefaults.MODELS + model).distinct()
        ProviderType.CODEX -> (CodexProfileDefaults.MODELS + model).distinct()
        ProviderType.XAI -> (XaiProfileDefaults.MODELS + model).distinct()
        ProviderType.OPENAI_COMPATIBLE -> listOf(model)
    },
)
