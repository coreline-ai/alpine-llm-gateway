package dev.alpine.llm.demo.llm

import android.app.Activity
import android.content.Context
import dev.alpine.llm.AnthropicOAuthContract
import dev.alpine.llm.AnthropicMessagesOAuthAdapter
import dev.alpine.llm.CodexOAuthContract
import dev.alpine.llm.CodexResponsesOAuthAdapter
import dev.alpine.llm.GeminiGenerateContentOAuthAdapter
import dev.alpine.llm.GeminiOAuthContract
import dev.alpine.llm.HostLlmStreamResult
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.OAuthHttpLlmBridge
import dev.alpine.llm.OAuthLlmSession
import dev.alpine.llm.OAuthManager
import dev.alpine.llm.OAuthProviderConfig
import dev.alpine.llm.OAuthTokenRequestEncoding
import dev.alpine.llm.OpenAiCompatibleOAuthAdapter
import dev.alpine.llm.ProviderCircuitBreaker
import dev.alpine.llm.ProviderCircuitBreakerConfig
import dev.alpine.llm.ProviderRetryPolicy
import dev.alpine.llm.ResilientOAuthHttpTransport
import dev.alpine.llm.XaiOAuthContract
import dev.alpine.llm.demo.model.ProviderProfile
import dev.alpine.llm.demo.model.ProviderType

object ProviderSessionFactory {
    fun create(context: Context, profile: ProviderProfile): ChatCompletionSession {
        require(profile.validationErrors().isEmpty()) { "Provider profile is invalid" }
        val oauth = OAuthManager(
            context = context.applicationContext,
            config = oauthConfig(profile),
        )
        val transport = ResilientOAuthHttpTransport(
            retryPolicy = ProviderRetryPolicy(maxAttempts = 3),
            circuitBreaker = ProviderCircuitBreaker(
                ProviderCircuitBreakerConfig(failureThreshold = 5),
            ),
        )
        val adapter = when (profile.type) {
            ProviderType.ANTHROPIC -> AnthropicMessagesOAuthAdapter(
                messagesEndpoint = profile.inferenceEndpoint,
                anthropicBeta = profile.anthropicBeta,
            )
            ProviderType.GEMINI -> GeminiGenerateContentOAuthAdapter(
                endpointTemplate = profile.inferenceEndpoint,
                extraHeaders = profile.googleProjectId
                    ?.takeIf(String::isNotBlank)
                    ?.let { mapOf("X-Goog-User-Project" to it) }
                    .orEmpty(),
            )
            ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleOAuthAdapter(
                completionEndpoint = profile.inferenceEndpoint,
            )
            ProviderType.CODEX -> CodexResponsesOAuthAdapter()
            ProviderType.XAI -> OpenAiCompatibleOAuthAdapter(
                completionEndpoint = profile.inferenceEndpoint,
            )
        }
        val bridge = OAuthHttpLlmBridge(
            adapter = adapter,
            streamingTransport = transport,
            transport = transport,
        )
        val session = OAuthLlmSession(oauth, bridge)
        return OAuthChatCompletionSession(profile, oauth, session)
    }

    private fun oauthConfig(profile: ProviderProfile): OAuthProviderConfig {
        if (profile.type == ProviderType.ANTHROPIC) {
            return AnthropicOAuthContract.providerConfig(
                providerId = profile.id,
                clientId = profile.clientId,
            )
        }
        if (profile.type == ProviderType.GEMINI) {
            return GeminiOAuthContract.providerConfig(
                providerId = profile.id,
                clientId = profile.clientId,
            )
        }
        if (profile.type == ProviderType.CODEX) {
            return CodexOAuthContract.providerConfig(
                providerId = profile.id,
                clientId = profile.clientId,
            )
        }
        if (profile.type == ProviderType.XAI) {
            return XaiOAuthContract.providerConfig(
                providerId = profile.id,
                clientId = profile.clientId,
            )
        }
        val common = OAuthProviderConfig(
            providerId = profile.id,
            authorizationEndpoint = profile.authorizationEndpoint,
            tokenEndpoint = profile.tokenEndpoint,
            clientId = profile.clientId,
            scopes = profile.scopes,
            callbackPort = profile.callbackPort,
            extraAuthorizationParams = if (profile.type == ProviderType.GEMINI) {
                mapOf("access_type" to "offline", "prompt" to "consent")
            } else {
                emptyMap()
            },
            tokenRequestEncoding = OAuthTokenRequestEncoding.FORM_URLENCODED,
            tokenRequestAdapter = dev.alpine.llm.StandardOAuthTokenRequestAdapter,
        )
        return common
    }

    private class OAuthChatCompletionSession(
        override val profile: ProviderProfile,
        private val oauth: OAuthManager,
        private val session: OAuthLlmSession,
    ) : ChatCompletionSession {
        override fun authenticationState(): OAuthAuthenticationState =
            oauth.authenticationState()

        override suspend fun authorize(activity: Activity) {
            oauth.authorize(activity)
        }

        override suspend fun stream(requestJson: String): HostLlmStreamResult =
            session.stream(requestJson)

        override fun logout() {
            oauth.logout()
        }

        override fun cancelAuthorization() {
            oauth.cancelAuthorization()
        }
    }
}
