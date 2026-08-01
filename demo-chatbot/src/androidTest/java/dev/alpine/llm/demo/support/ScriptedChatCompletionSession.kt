package dev.alpine.llm.demo.support

import android.app.Activity
import dev.alpine.llm.HostLlmStreamEvent
import dev.alpine.llm.HostLlmStreamResult
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.ProviderStreamException
import dev.alpine.llm.demo.llm.ChatCompletionSession
import dev.alpine.llm.demo.model.ProviderProfile
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
            Mode.CONSTRAINT_CORRECT -> HostLlmStreamResult(
                events = flowOf(
                    HostLlmStreamEvent.delta(
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
                error = ProviderStreamException(
                    "raw-provider-body token=must-never-reach-the-ui",
                ),
            )
            Mode.INTERRUPTED_STREAM_THEN_RECOVER -> streamFailureThenRecover(
                attempt = attempt,
                partial = "Interrupted partial answer",
                error = IOException("https://provider.invalid secret=must-never-reach-the-ui"),
            )
            Mode.TIMEOUT_THEN_RECOVER -> if (attempt == 1) {
                HostLlmStreamResult(
                    events = flow {
                        withTimeout(25) { awaitCancellation() }
                    },
                )
            } else {
                recovered()
            }
            Mode.FRESHNESS_CORRECT -> HostLlmStreamResult(
                events = flowOf(
                    HostLlmStreamEvent.delta(
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

    private fun statusThenRecover(attempt: Int, statusCode: Int): HostLlmStreamResult =
        if (attempt == 1) {
            HostLlmStreamResult(
                statusCode = statusCode,
                errorBodyJson = """{"error":"raw-secret-must-not-render"}""",
            )
        } else {
            recovered()
        }

    private fun streamFailureThenRecover(
        attempt: Int,
        partial: String,
        error: Exception,
    ): HostLlmStreamResult = if (attempt == 1) {
        HostLlmStreamResult(
            events = flow {
                emit(HostLlmStreamEvent.delta(partial))
                throw error
            },
        )
    } else {
        recovered()
    }

    private fun recovered() = HostLlmStreamResult(
        events = flowOf(HostLlmStreamEvent.delta("Recovered answer")),
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
