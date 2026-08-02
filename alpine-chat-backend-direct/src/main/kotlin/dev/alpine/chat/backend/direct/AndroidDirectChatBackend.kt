package dev.alpine.chat.backend.direct

import dev.alpine.chat.routing.ChatBackend
import dev.alpine.chat.routing.ChatBackendCapabilities
import dev.alpine.chat.routing.ChatBackendFailure
import dev.alpine.chat.routing.ChatBackendFailureCode
import dev.alpine.chat.routing.ChatBackendIdempotency
import dev.alpine.chat.routing.ChatBackendKind
import dev.alpine.chat.routing.ChatBackendPreparation
import dev.alpine.chat.routing.ChatBackendRequest
import dev.alpine.chat.routing.ChatBackendResult
import dev.alpine.chat.routing.ChatFailureStage
import dev.alpine.chat.routing.ChatStreamEmitter
import dev.alpine.chat.routing.ChatStreamEvent
import dev.alpine.chat.routing.ChatStreamEventType
import dev.alpine.llm.HostLlmStreamResult
import dev.alpine.llm.OAuthException
import dev.alpine.llm.OAuthFailureKind
import dev.alpine.llm.OAuthLlmSession
import dev.alpine.llm.OAuthRequiredException
import dev.alpine.llm.ProviderCircuitOpenException
import dev.alpine.llm.ProviderStreamException
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/** Existing Android OAuth/Provider session exposed through the shared Phase 5 backend contract. */
class AndroidDirectChatBackend internal constructor(
    override val id: String,
    private val streamRequest: suspend (String) -> HostLlmStreamResult,
) : ChatBackend {
    constructor(
        id: String = DEFAULT_BACKEND_ID,
        session: OAuthLlmSession,
    ) : this(id, session::stream)

    init {
        require(id.isNotBlank() && id.length <= 128) { "backend id is invalid" }
    }

    override val kind: ChatBackendKind = ChatBackendKind.ANDROID_DIRECT
    override val capabilities = ChatBackendCapabilities(ChatBackendIdempotency.NONE)

    override suspend fun prepare(request: ChatBackendRequest): ChatBackendPreparation =
        if (requestModelMatches(request)) {
            ChatBackendPreparation.Ready
        } else {
            ChatBackendPreparation.Unavailable(
                ChatBackendFailure(
                    ChatBackendFailureCode.INVALID_RESPONSE,
                    ChatFailureStage.PREPARATION,
                    retryable = false,
                ),
            )
        }

    override suspend fun stream(
        request: ChatBackendRequest,
        emitter: ChatStreamEmitter,
    ): ChatBackendResult {
        var deltaEmitted = false
        return try {
            emitter.emit(ChatStreamEvent(ChatStreamEventType.STARTED))
            val response = streamRequest(request.requestJson)
            if (response.statusCode !in 200..299) {
                return ChatBackendResult.Failed(statusFailure(response.statusCode))
            }
            response.events.collect { event ->
                val json = try {
                    JSONObject(event.dataJson)
                } catch (_: Exception) {
                    throw SafeDirectStreamException()
                }
                if (json.optString("type").ifBlank { "delta" } != "delta") {
                    throw SafeDirectStreamException()
                }
                val usage = json.optJSONObject("usage")
                deltaEmitted = true
                emitter.emit(
                    ChatStreamEvent(
                        type = ChatStreamEventType.DELTA,
                        text = json.optString("text"),
                        finishReason = json.optString("finish_reason").ifBlank { null },
                        inputTokens = usage?.optionalInt("prompt_tokens"),
                        outputTokens = usage?.optionalInt("completion_tokens"),
                    ),
                )
            }
            emitter.emit(ChatStreamEvent(ChatStreamEventType.COMPLETED))
            ChatBackendResult.Completed
        } catch (_: CancellationException) {
            ChatBackendResult.Failed(
                failure(
                    ChatBackendFailureCode.CANCELLED,
                    deltaEmitted,
                    retryable = true,
                ),
            )
        } catch (_: OAuthRequiredException) {
            ChatBackendResult.Failed(
                failure(
                    ChatBackendFailureCode.AUTHENTICATION_REQUIRED,
                    deltaEmitted,
                    retryable = true,
                ),
            )
        } catch (error: OAuthException) {
            ChatBackendResult.Failed(
                failure(
                    if (error.kind == OAuthFailureKind.NETWORK) {
                        ChatBackendFailureCode.NETWORK_UNAVAILABLE
                    } else {
                        ChatBackendFailureCode.AUTHENTICATION_REQUIRED
                    },
                    deltaEmitted,
                    retryable = true,
                ),
            )
        } catch (_: ProviderCircuitOpenException) {
            ChatBackendResult.Failed(
                failure(ChatBackendFailureCode.PROVIDER_UNAVAILABLE, deltaEmitted, retryable = true),
            )
        } catch (_: ProviderStreamException) {
            ChatBackendResult.Failed(
                failure(ChatBackendFailureCode.INVALID_RESPONSE, deltaEmitted, retryable = false),
            )
        } catch (_: SafeDirectStreamException) {
            ChatBackendResult.Failed(
                failure(ChatBackendFailureCode.INVALID_RESPONSE, deltaEmitted, retryable = false),
            )
        } catch (_: IOException) {
            ChatBackendResult.Failed(
                failure(ChatBackendFailureCode.NETWORK_UNAVAILABLE, deltaEmitted, retryable = true),
            )
        } catch (_: Exception) {
            ChatBackendResult.Failed(
                failure(ChatBackendFailureCode.INTERNAL_ERROR, deltaEmitted, retryable = false),
            )
        }
    }

    private fun requestModelMatches(request: ChatBackendRequest): Boolean = runCatching {
        JSONObject(request.requestJson).optString("model") == request.model
    }.getOrDefault(false)

    private fun statusFailure(status: Int): ChatBackendFailure = when (status) {
        401, 403 -> ChatBackendFailure(
            ChatBackendFailureCode.AUTHENTICATION_REQUIRED,
            ChatFailureStage.DISPATCH,
            retryable = true,
        )
        429 -> ChatBackendFailure(
            ChatBackendFailureCode.RATE_LIMITED,
            ChatFailureStage.DISPATCH,
            retryable = true,
        )
        in 400..499 -> ChatBackendFailure(
            ChatBackendFailureCode.PROVIDER_REJECTED,
            ChatFailureStage.DISPATCH,
            retryable = false,
        )
        else -> ChatBackendFailure(
            ChatBackendFailureCode.PROVIDER_UNAVAILABLE,
            ChatFailureStage.DISPATCH,
            retryable = true,
        )
    }

    private fun failure(
        code: ChatBackendFailureCode,
        deltaEmitted: Boolean,
        retryable: Boolean,
    ) = ChatBackendFailure(
        code = code,
        stage = if (deltaEmitted) ChatFailureStage.STREAMING else ChatFailureStage.DISPATCH,
        retryable = retryable,
    )

    private fun JSONObject.optionalInt(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    companion object {
        const val DEFAULT_BACKEND_ID = "android-direct"
    }

    private class SafeDirectStreamException : Exception()
}
