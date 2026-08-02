package dev.alpine.chat.provider.android.session

import android.app.Activity
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.chat.feature.backend.ChatBackendDescriptor
import dev.alpine.chat.feature.backend.ChatBackendSession
import dev.alpine.chat.provider.android.model.AnthropicProfileDefaults
import dev.alpine.chat.provider.android.model.CodexProfileDefaults
import dev.alpine.chat.provider.android.model.GeminiProfileDefaults
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderType
import dev.alpine.chat.provider.android.model.XaiProfileDefaults

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
