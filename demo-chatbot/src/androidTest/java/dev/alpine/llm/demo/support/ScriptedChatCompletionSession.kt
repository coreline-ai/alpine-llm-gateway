package dev.alpine.llm.demo.support

import android.app.Activity
import dev.alpine.chat.feature.backend.ChatBackendDelta
import dev.alpine.chat.feature.backend.ChatBackendException
import dev.alpine.chat.feature.backend.ChatBackendFailureCode
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.chat.provider.android.model.ProviderProfile
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout

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

    override suspend fun stream(requestJson: String): ChatBackendStreamResult {
        val attempt = requests.incrementAndGet()
        return when (mode) {
            Mode.SLOW -> ChatBackendStreamResult(
                events = flow {
                    emit(ChatBackendDelta("Slow partial answer"))
                    awaitCancellation()
                },
            )
            Mode.FAIL_THEN_RECOVER -> if (attempt == 1) {
                ChatBackendStreamResult(
                    events = flow {
                        emit(ChatBackendDelta("Failed partial answer"))
                        throw IOException("redacted-test-provider-failure")
                    },
                )
            } else {
                ChatBackendStreamResult(
                    events = flowOf(ChatBackendDelta("Recovered answer")),
                )
            }
            Mode.CONSTRAINT_CORRECT -> ChatBackendStreamResult(
                events = flowOf(
                    ChatBackendDelta(
                        if (attempt == 1) {
                            "one two three four five six"
                        } else {
                            "**Done** now"
                        },
                    ),
                ),
            )
            Mode.HTTP_429_THEN_RECOVER -> statusThenRecover(attempt, 429)
            Mode.HTTP_503_THEN_RECOVER -> statusThenRecover(attempt, 503)
            Mode.MALFORMED_STREAM_THEN_RECOVER -> streamFailureThenRecover(
                attempt = attempt,
                partial = "Malformed partial answer",
                error = ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE),
            )
            Mode.INTERRUPTED_STREAM_THEN_RECOVER -> streamFailureThenRecover(
                attempt = attempt,
                partial = "Interrupted partial answer",
                error = IOException("https://provider.invalid secret=must-never-reach-the-ui"),
            )
            Mode.TIMEOUT_THEN_RECOVER -> if (attempt == 1) {
                ChatBackendStreamResult(
                    events = flow {
                        withTimeout(25) { awaitCancellation() }
                    },
                )
            } else {
                recovered()
            }
            Mode.FRESHNESS_CORRECT -> ChatBackendStreamResult(
                events = flowOf(
                    ChatBackendDelta(
                        if (attempt == 1) {
                            "I checked the web today and verified the current value."
                        } else {
                            "I cannot access live web data here, so I cannot verify today's value."
                        },
                    ),
                ),
            )
        }
    }

    private fun statusThenRecover(attempt: Int, statusCode: Int): ChatBackendStreamResult =
        if (attempt == 1) {
            ChatBackendStreamResult(
                statusCode = statusCode,
            )
        } else {
            recovered()
        }

    private fun streamFailureThenRecover(
        attempt: Int,
        partial: String,
        error: Exception,
    ): ChatBackendStreamResult = if (attempt == 1) {
        ChatBackendStreamResult(
            events = flow {
                emit(ChatBackendDelta(partial))
                throw error
            },
        )
    } else {
        recovered()
    }

    private fun recovered() = ChatBackendStreamResult(
        events = flowOf(ChatBackendDelta("Recovered answer")),
    )

    override fun logout() = Unit

    override fun cancelAuthorization() = Unit

    private enum class Mode {
        SLOW,
        FAIL_THEN_RECOVER,
        CONSTRAINT_CORRECT,
        HTTP_429_THEN_RECOVER,
        HTTP_503_THEN_RECOVER,
        MALFORMED_STREAM_THEN_RECOVER,
        INTERRUPTED_STREAM_THEN_RECOVER,
        TIMEOUT_THEN_RECOVER,
        FRESHNESS_CORRECT,
    }

    companion object {
        fun slow(profile: ProviderProfile) =
            ScriptedChatCompletionSession(profile, Mode.SLOW)

        fun failThenRecover(profile: ProviderProfile) =
            ScriptedChatCompletionSession(profile, Mode.FAIL_THEN_RECOVER)

        fun constraintCorrect(profile: ProviderProfile) =
            ScriptedChatCompletionSession(profile, Mode.CONSTRAINT_CORRECT)

        fun overloadedThenRecover(profile: ProviderProfile) =
            ScriptedChatCompletionSession(profile, Mode.HTTP_429_THEN_RECOVER)

        fun unavailableThenRecover(profile: ProviderProfile) =
            ScriptedChatCompletionSession(profile, Mode.HTTP_503_THEN_RECOVER)

        fun malformedThenRecover(profile: ProviderProfile) =
            ScriptedChatCompletionSession(profile, Mode.MALFORMED_STREAM_THEN_RECOVER)

        fun interruptedThenRecover(profile: ProviderProfile) =
            ScriptedChatCompletionSession(profile, Mode.INTERRUPTED_STREAM_THEN_RECOVER)

        fun timeoutThenRecover(profile: ProviderProfile) =
            ScriptedChatCompletionSession(profile, Mode.TIMEOUT_THEN_RECOVER)

        fun freshnessCorrect(profile: ProviderProfile) =
            ScriptedChatCompletionSession(profile, Mode.FRESHNESS_CORRECT)
    }
}
