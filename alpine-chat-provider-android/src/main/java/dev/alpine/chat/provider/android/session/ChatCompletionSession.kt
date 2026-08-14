package dev.alpine.chat.provider.android.session

import android.app.Activity
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.HostLlmStreamEvent
import dev.alpine.llm.HostLlmStreamResult
import dev.alpine.chat.feature.backend.ChatBackendDescriptor
import dev.alpine.chat.feature.backend.ChatBackendSession
import dev.alpine.chat.provider.android.model.ProviderProfile
import kotlinx.coroutines.flow.map

interface ChatCompletionSession : ChatBackendSession {
    val profile: ProviderProfile

    override val descriptor: ChatBackendDescriptor
        get() = profile.toChatBackendDescriptor()

    fun authenticationState(): OAuthAuthenticationState

    suspend fun authorize(activity: Activity)

    /**
     * Safe normalized stream used by the loopback HostBridge. Implementations may override this
     * to preserve normalized usage metadata, but credential and Provider transport details never
     * cross this boundary.
     */
    suspend fun streamForHostBridge(requestJson: String): HostLlmStreamResult {
        val result = stream(requestJson)
        return HostLlmStreamResult(
            statusCode = result.statusCode,
            events = result.events.map { delta -> HostLlmStreamEvent.delta(delta.text) },
        )
    }

    fun logout()

    fun cancelAuthorization()
}

fun ProviderProfile.toChatBackendDescriptor(): ChatBackendDescriptor = ChatBackendDescriptor(
    profileId = id,
    label = label,
    model = model,
    modelOptions = enabledModelIds(),
)
