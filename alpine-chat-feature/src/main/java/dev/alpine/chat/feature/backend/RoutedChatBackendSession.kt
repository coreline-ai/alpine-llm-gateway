package dev.alpine.chat.feature.backend

import dev.alpine.chat.routing.ChatBackendFailureCode as RoutingFailureCode
import dev.alpine.chat.routing.ChatBackendRequest
import dev.alpine.chat.routing.ChatFallbackAuthorizer
import dev.alpine.chat.routing.ChatRoutingOutcome
import dev.alpine.chat.routing.ChatRoutingRequest
import dev.alpine.chat.routing.ChatStreamEmitter
import dev.alpine.chat.routing.ChatStreamEventType
import dev.alpine.chat.routing.ChatExecutionMode
import dev.alpine.chat.routing.SafeChatRouter
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.channelFlow

/**
 * Adapts the safe two-backend router to the UI-neutral Chat Feature session contract.
 *
 * The request JSON is passed through unchanged. A fallback can occur only through the supplied
 * [ChatFallbackAuthorizer], and [SafeChatRouter] still prohibits replay after dispatch.
 */
class RoutedChatBackendSession(
    override val descriptor: ChatBackendDescriptor,
    private val requestedMode: ChatExecutionMode,
    private val routerProvider: suspend () -> SafeChatRouter,
    private val fallbackAuthorizer: ChatFallbackAuthorizer = ChatFallbackAuthorizer.DENY,
) : ChatBackendSession {
    override suspend fun stream(requestJson: String): ChatBackendStreamResult {
        val primary = request(requestJson)
        return ChatBackendStreamResult(
            events = channelFlow {
                val result = routerProvider().route(
                    routingRequest = ChatRoutingRequest(
                        requestedMode = requestedMode,
                        primaryRequest = primary,
                        fastFallbackRequest = if (requestedMode == ChatExecutionMode.ALPINE_WORKSPACE) {
                            primary
                        } else {
                            null
                        },
                    ),
                    authorizer = fallbackAuthorizer,
                    emitter = ChatStreamEmitter { event ->
                        if (event.type == ChatStreamEventType.DELTA && event.text.isNotEmpty()) {
                            check(trySend(ChatBackendDelta(event.text)).isSuccess) {
                                "chat stream collector is unavailable"
                            }
                        }
                    },
                )
                when (result.outcome) {
                    ChatRoutingOutcome.COMPLETED -> Unit
                    ChatRoutingOutcome.FAILED,
                    ChatRoutingOutcome.FALLBACK_DECLINED,
                    ChatRoutingOutcome.DUPLICATE_REJECTED,
                    -> throw mappedFailure(result.failure?.code ?: RoutingFailureCode.INTERNAL_ERROR)
                }
            },
        )
    }

    private fun request(requestJson: String): ChatBackendRequest = ChatBackendRequest(
        requestId = "request-${UUID.randomUUID()}",
        conversationId = "conversation-${UUID.randomUUID()}",
        model = descriptor.model,
        requestJson = requestJson,
    )

    private fun mappedFailure(code: RoutingFailureCode): Throwable = when (code) {
        RoutingFailureCode.CANCELLED -> CancellationException("chat request cancelled")
        RoutingFailureCode.AUTHENTICATION_REQUIRED ->
            ChatBackendException(ChatBackendFailureCode.REAUTHENTICATION_REQUIRED)
        RoutingFailureCode.RATE_LIMITED ->
            ChatBackendException(ChatBackendFailureCode.RATE_LIMITED)
        RoutingFailureCode.PROVIDER_UNAVAILABLE ->
            ChatBackendException(ChatBackendFailureCode.PROVIDER_UNAVAILABLE)
        RoutingFailureCode.NETWORK_UNAVAILABLE ->
            ChatBackendException(ChatBackendFailureCode.NETWORK)
        RoutingFailureCode.INVALID_RESPONSE ->
            ChatBackendException(ChatBackendFailureCode.INVALID_RESPONSE)
        RoutingFailureCode.RESPONSE_TOO_LARGE ->
            ChatBackendException(ChatBackendFailureCode.RESPONSE_TOO_LARGE)
        RoutingFailureCode.RUNTIME_NOT_INSTALLED ->
            ChatBackendException(ChatBackendFailureCode.RUNTIME_NOT_INSTALLED)
        RoutingFailureCode.RUNTIME_REPAIR_REQUIRED ->
            ChatBackendException(ChatBackendFailureCode.RUNTIME_REPAIR_REQUIRED)
        RoutingFailureCode.RUNTIME_BUSY ->
            ChatBackendException(ChatBackendFailureCode.RUNTIME_BUSY)
        RoutingFailureCode.RUNTIME_START_FAILED ->
            ChatBackendException(ChatBackendFailureCode.RUNTIME_START_FAILED)
        RoutingFailureCode.FALLBACK_DECLINED ->
            ChatBackendException(ChatBackendFailureCode.FALLBACK_DECLINED)
        RoutingFailureCode.PROVIDER_REJECTED,
        RoutingFailureCode.DUPLICATE_REQUEST,
        RoutingFailureCode.INTERNAL_ERROR,
        -> ChatBackendException(ChatBackendFailureCode.UNKNOWN)
    }
}
