package dev.alpine.chat.provider.android.session

import android.app.Activity
import android.content.Context
import android.util.Log
import dev.alpine.chat.feature.backend.ChatBackendDelta
import dev.alpine.chat.feature.backend.ChatBackendException
import dev.alpine.chat.feature.backend.ChatBackendFailureCode
import dev.alpine.chat.feature.backend.ChatBackendStreamResult
import dev.alpine.llm.AnthropicMessagesOAuthAdapter
import dev.alpine.llm.CodexOAuthContract
import dev.alpine.llm.CodexOAuthCompatibilityRegistry
import dev.alpine.llm.CodexResponsesOAuthAdapter
import dev.alpine.llm.GeminiGenerateContentOAuthAdapter
import dev.alpine.llm.GeminiOAuthContract
import dev.alpine.llm.OAuthAuthenticationState
import dev.alpine.llm.OAuthException
import dev.alpine.llm.OAuthFailureKind
import dev.alpine.llm.OAuthHttpLlmBridge
import dev.alpine.llm.OAuthLlmSession
import dev.alpine.llm.OAuthManager
import dev.alpine.llm.OAuthProviderConfig
import dev.alpine.llm.OAuthRequiredException
import dev.alpine.llm.OAuthTokenRequestEncoding
import dev.alpine.llm.OpenAiCompatibleOAuthAdapter
import dev.alpine.llm.ProviderCircuitBreaker
import dev.alpine.llm.ProviderCircuitBreakerConfig
import dev.alpine.llm.ProviderCircuitOpenException
import dev.alpine.llm.ProviderRetryPolicy
import dev.alpine.llm.ProviderStreamException
import dev.alpine.llm.ResilientOAuthHttpTransport
import dev.alpine.chat.provider.android.model.ProviderProfile
import dev.alpine.chat.provider.android.model.ProviderType
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

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
            ProviderType.CODEX -> CodexResponsesOAuthAdapter(
                responsesEndpoint = profile.inferenceEndpoint,
                compatibility = CodexOAuthCompatibilityRegistry.matching(
                    profile.clientId,
                    profile.inferenceEndpoint,
                ),
            )
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
        val common = OAuthProviderConfig(
            providerId = profile.id,
            authorizationEndpoint = profile.authorizationEndpoint,
            tokenEndpoint = profile.tokenEndpoint,
            clientId = profile.clientId,
            scopes = profile.scopes,
            callbackPort = profile.callbackPort,
            extraAuthorizationParams = emptyMap(),
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

        override suspend fun stream(requestJson: String): ChatBackendStreamResult {
            val result = try {
                session.stream(requestJson)
            } catch (error: Throwable) {
                throw normalizedBackendFailure(error)
            }
            return ChatBackendStreamResult(
                statusCode = result.statusCode,
                events = flow {
                    try {
                        result.events.collect { event ->
                            val text = runCatching {
                                JSONObject(event.dataJson).optString("text")
                            }.getOrElse {
                                throw ChatBackendException(
                                    ChatBackendFailureCode.INVALID_RESPONSE,
                                )
                            }
                            emit(ChatBackendDelta(text))
                        }
                    } catch (error: Throwable) {
                        throw normalizedBackendFailure(error)
                    }
                },
            )
        }

        override suspend fun streamForHostBridge(requestJson: String) =
            session.stream(requestJson)

        override fun logout() {
            oauth.logout()
        }

        override fun cancelAuthorization() {
            oauth.cancelAuthorization()
        }
    }

    private fun normalizedBackendFailure(error: Throwable): Throwable = when (error) {
        is CancellationException -> error
        is ChatBackendException -> error
        is OAuthRequiredException -> ChatBackendException(
            ChatBackendFailureCode.REAUTHENTICATION_REQUIRED,
        )
        is OAuthException -> when (error.kind) {
            OAuthFailureKind.INVALID_GRANT,
            OAuthFailureKind.STORAGE_INVALIDATED,
            OAuthFailureKind.STORAGE_FAILURE,
            -> ChatBackendException(ChatBackendFailureCode.REAUTHENTICATION_REQUIRED)
            OAuthFailureKind.CALLBACK_TIMEOUT ->
                ChatBackendException(ChatBackendFailureCode.TIMEOUT)
            else -> ChatBackendException(ChatBackendFailureCode.UNKNOWN)
        }
        is ProviderCircuitOpenException -> ChatBackendException(
            ChatBackendFailureCode.CIRCUIT_OPEN,
        )
        is ProviderStreamException -> {
            runCatching {
                Log.w(DIAGNOSTIC_TAG, "provider_stream_failure:${error.diagnosticCode}")
            }
            ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE)
        }
        is TimeoutCancellationException,
        is SocketTimeoutException,
        is IOException,
        -> error
        else -> ChatBackendException(ChatBackendFailureCode.UNKNOWN)
    }

    private const val DIAGNOSTIC_TAG = "AlpineOAuthStream"
}
