package dev.alpine.llm.demo.support

import android.app.Activity
import dev.alpine.llm.HostLlmStreamEvent
import dev.alpine.llm.HostLlmStreamResult
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.demo.llm.ChatCompletionSession
import dev.alpine.llm.demo.model.ProviderProfile
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

class ScriptedChatCompletionSession private constructor(
    override val profile: ProviderProfile,
    private val mode: Mode,
) : ChatCompletionSession {
    private val requests = AtomicInteger()

    val requestCount: Int
        get() = requests.get()

    override fun authenticationState(): OAuthAuthenticationState =
        OAuthAuthenticationState.Authenticated(null, emptyMap())

    override suspend fun authorize(activity: Activity) = Unit

    override suspend fun stream(requestJson: String): HostLlmStreamResult {
        val attempt = requests.incrementAndGet()
        return when (mode) {
            Mode.SLOW -> HostLlmStreamResult(
                events = flow {
                    emit(HostLlmStreamEvent.delta("Slow partial answer"))
                    awaitCancellation()
                },
            )
            Mode.FAIL_THEN_RECOVER -> if (attempt == 1) {
                HostLlmStreamResult(
                    events = flow {
                        emit(HostLlmStreamEvent.delta("Failed partial answer"))
                        throw IOException("redacted-test-provider-failure")
                    },
                )
            } else {
                HostLlmStreamResult(
                    events = flowOf(HostLlmStreamEvent.delta("Recovered answer")),
                )
            }
        }
    }

    override fun logout() = Unit

    override fun cancelAuthorization() = Unit

    private enum class Mode {
        SLOW,
        FAIL_THEN_RECOVER,
    }

    companion object {
        fun slow(profile: ProviderProfile) =
            ScriptedChatCompletionSession(profile, Mode.SLOW)

        fun failThenRecover(profile: ProviderProfile) =
            ScriptedChatCompletionSession(profile, Mode.FAIL_THEN_RECOVER)
    }
}
