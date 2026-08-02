package dev.alpine.llm.demo.support

import android.app.Activity
import dev.alpine.chat.feature.backend.ChatBackendDelta
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.OAuthTokenStore
import dev.alpine.llm.demo.llm.ChatCompletionSession
import dev.alpine.llm.demo.model.ProviderProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.flow

/**
 * Credential-free provider used by device tests.
 *
 * Each profile receives an independent response and request counter so the
 * selector test can prove that the active LLM, rather than a shared fallback,
 * handled the prompt.
 */
class FakeChatCompletionSession(
    override val profile: ProviderProfile,
    private val responseText: String,
    private val requestCounts: ConcurrentHashMap<String, AtomicInteger>,
    private val logoutCounts: ConcurrentHashMap<String, AtomicInteger>,
    private val authorizeCounts: ConcurrentHashMap<String, AtomicInteger>,
    private val signedOutProfiles: MutableSet<String>,
    private val reauthenticationProfiles: MutableSet<String>,
) : ChatCompletionSession {
    override fun authenticationState(): OAuthAuthenticationState =
        when (profile.id) {
            in reauthenticationProfiles -> OAuthAuthenticationState.ReauthenticationRequired(
                OAuthTokenStore.InvalidationReason.DECRYPTION_FAILED,
            )
            in signedOutProfiles -> OAuthAuthenticationState.SignedOut
            else -> OAuthAuthenticationState.Authenticated(
                expiresAtMs = null,
                metadata = emptyMap(),
            )
        }

    override suspend fun authorize(activity: Activity) {
        authorizeCounts
            .computeIfAbsent(profile.id) { AtomicInteger() }
            .incrementAndGet()
        signedOutProfiles -= profile.id
        reauthenticationProfiles -= profile.id
    }

    override suspend fun stream(requestJson: String): ChatBackendStreamResult {
        requestCounts
            .computeIfAbsent(profile.id) { AtomicInteger() }
            .incrementAndGet()
        return ChatBackendStreamResult(
            events = flow {
                val splitAt = responseText.length / 2
                emit(ChatBackendDelta(responseText.substring(0, splitAt)))
                emit(ChatBackendDelta(responseText.substring(splitAt)))
            },
        )
    }

    override fun logout() {
        logoutCounts
            .computeIfAbsent(profile.id) { AtomicInteger() }
            .incrementAndGet()
        signedOutProfiles += profile.id
    }

    override fun cancelAuthorization() = Unit
}

class FakeProviderScenario {
    private val responses = ConcurrentHashMap<String, String>()
    private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val logoutCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val authorizeCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val signedOutProfiles = ConcurrentHashMap.newKeySet<String>()
    private val reauthenticationProfiles = ConcurrentHashMap.newKeySet<String>()

    fun respond(profileId: String, text: String) {
        responses[profileId] = text
    }

    fun create(profile: ProviderProfile): ChatCompletionSession =
        FakeChatCompletionSession(
            profile = profile,
            responseText = checkNotNull(responses[profile.id]) {
                "No fake response configured for ${profile.id}"
            },
            requestCounts = requestCounts,
            logoutCounts = logoutCounts,
            authorizeCounts = authorizeCounts,
            signedOutProfiles = signedOutProfiles,
            reauthenticationProfiles = reauthenticationProfiles,
        )

    fun requestCount(profileId: String): Int = requestCounts[profileId]?.get() ?: 0

    fun logoutCount(profileId: String): Int = logoutCounts[profileId]?.get() ?: 0

    fun authorizeCount(profileId: String): Int = authorizeCounts[profileId]?.get() ?: 0

    fun startSignedOut(profileId: String) {
        signedOutProfiles += profileId
        reauthenticationProfiles -= profileId
    }

    fun requireReauthentication(profileId: String) {
        reauthenticationProfiles += profileId
        signedOutProfiles -= profileId
    }
}
