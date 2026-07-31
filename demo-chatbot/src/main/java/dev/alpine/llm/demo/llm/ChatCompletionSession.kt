package dev.alpine.llm.demo.llm

import android.app.Activity
import dev.alpine.llm.HostLlmStreamResult
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.demo.model.ProviderProfile

interface ChatCompletionSession {
    val profile: ProviderProfile

    fun authenticationState(): OAuthAuthenticationState

    suspend fun authorize(activity: Activity)

    suspend fun stream(requestJson: String): HostLlmStreamResult

    fun logout()

    fun cancelAuthorization()
}
