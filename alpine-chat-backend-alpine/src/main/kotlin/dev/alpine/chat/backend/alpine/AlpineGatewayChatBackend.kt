package dev.alpine.chat.backend.alpine

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
import dev.alpine.llm.AlpineLlmGatewayClient
import dev.alpine.llm.GatewayClientErrorCode
import dev.alpine.llm.GatewayClientException
import dev.alpine.runtime.api.AlpineRuntimeManager
import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.bridge.AlpineLlmBridgeController
import dev.alpine.runtime.bridge.LlmBridgeLifecycleState
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Alpine Python Gateway exposed through the shared Phase 5 backend contract. */
class AlpineGatewayChatBackend internal constructor(
    override val id: String,
    private val prepareAction: suspend () -> ChatBackendPreparation,
    private val streamAction: suspend (ChatBackendRequest, ChatStreamEmitter) -> ChatBackendResult,
) : ChatBackend {
    @JvmOverloads
    constructor(
        runtimeManager: AlpineRuntimeManager,
        controller: AlpineLlmBridgeController,
        id: String = DEFAULT_BACKEND_ID,
        gatewayClientFactory: () -> AlpineLlmGatewayClient = { AlpineLlmGatewayClient() },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        id = id,
        prepareAction = {
            prepareController(runtimeManager, controller, ioDispatcher)
        },
        streamAction = { request, emitter ->
            streamGateway(request, emitter, gatewayClientFactory, ioDispatcher)
        },
    )

    init {
        require(id.isNotBlank() && id.length <= 128) { "backend id is invalid" }
    }

    override val kind: ChatBackendKind = ChatBackendKind.ALPINE_GATEWAY
    override val capabilities = ChatBackendCapabilities(ChatBackendIdempotency.NONE)

    override suspend fun prepare(request: ChatBackendRequest): ChatBackendPreparation {
        if (!requestModelMatches(request)) {
            return ChatBackendPreparation.Unavailable(
                ChatBackendFailure(
                    ChatBackendFailureCode.INVALID_RESPONSE,
                    ChatFailureStage.PREPARATION,
                    retryable = false,
                ),
            )
        }
        return prepareAction()
    }

    override suspend fun stream(
        request: ChatBackendRequest,
        emitter: ChatStreamEmitter,
    ): ChatBackendResult = streamAction(request, emitter)

    private fun requestModelMatches(request: ChatBackendRequest): Boolean = runCatching {
        JSONObject(request.requestJson).optString("model") == request.model
    }.getOrDefault(false)

    companion object {
        const val DEFAULT_BACKEND_ID = "alpine-gateway"

        private suspend fun prepareController(
            runtimeManager: AlpineRuntimeManager,
            controller: AlpineLlmBridgeController,
            ioDispatcher: CoroutineDispatcher,
        ): ChatBackendPreparation {
            val runtimeState = runtimeManager.currentState().lifecycle
            when (runtimeState) {
                RuntimeLifecycleState.NOT_INSTALLED -> return unavailable(
                    ChatBackendFailureCode.RUNTIME_NOT_INSTALLED,
                    retryable = false,
                )
                RuntimeLifecycleState.REPAIR_REQUIRED -> return unavailable(
                    ChatBackendFailureCode.RUNTIME_REPAIR_REQUIRED,
                    retryable = false,
                )
                RuntimeLifecycleState.INSTALLING,
                RuntimeLifecycleState.STARTING,
                RuntimeLifecycleState.STOPPING,
                -> return unavailable(ChatBackendFailureCode.RUNTIME_BUSY, retryable = true)
                RuntimeLifecycleState.FAILED -> return unavailable(
                    ChatBackendFailureCode.RUNTIME_START_FAILED,
                    retryable = true,
                )
                RuntimeLifecycleState.RUNNING -> {
                    if (controller.currentState() != LlmBridgeLifecycleState.RUNNING) {
                        return unavailable(ChatBackendFailureCode.RUNTIME_BUSY, retryable = true)
                    }
                }
                RuntimeLifecycleState.READY -> {
                    if (controller.currentState() in setOf(
                            LlmBridgeLifecycleState.STARTING,
                            LlmBridgeLifecycleState.STOPPING,
                        )
                    ) {
                        return unavailable(ChatBackendFailureCode.RUNTIME_BUSY, retryable = true)
                    }
                }
            }

            return try {
                val health = withContext(ioDispatcher) {
                    if (controller.currentState() == LlmBridgeLifecycleState.RUNNING) {
                        controller.health().toCompletableFuture().get()
                    } else {
                        controller.start().toCompletableFuture().get()
                    }
                }
                if (health.healthy) {
                    ChatBackendPreparation.Ready
                } else {
                    unavailable(ChatBackendFailureCode.RUNTIME_START_FAILED, retryable = true)
                }
            } catch (_: CancellationException) {
                unavailable(ChatBackendFailureCode.CANCELLED, retryable = true)
            } catch (_: Exception) {
                unavailable(ChatBackendFailureCode.RUNTIME_START_FAILED, retryable = true)
            }
        }

        private suspend fun streamGateway(
            request: ChatBackendRequest,
            emitter: ChatStreamEmitter,
            gatewayClientFactory: () -> AlpineLlmGatewayClient,
            ioDispatcher: CoroutineDispatcher,
        ): ChatBackendResult {
            var deltaEmitted = false
            var completedEmitted = false
            return try {
                val context = currentCoroutineContext()
                emitter.emit(ChatStreamEvent(ChatStreamEventType.STARTED))
                withContext(ioDispatcher) {
                    gatewayClientFactory().streamJson(
                        requestJson = request.requestJson,
                        isCancelled = { !context.isActive },
                    ) { event ->
                        when (event.type) {
                            "start" -> Unit
                            "delta" -> {
                                deltaEmitted = true
                                emitter.emit(
                                    ChatStreamEvent(
                                        type = ChatStreamEventType.DELTA,
                                        text = event.text,
                                        finishReason = event.finishReason,
                                        inputTokens = event.usage?.optionalInt("prompt_tokens"),
                                        outputTokens = event.usage?.optionalInt("completion_tokens"),
                                    ),
                                )
                            }
                            "done" -> {
                                completedEmitted = true
                                emitter.emit(
                                    ChatStreamEvent(
                                        type = ChatStreamEventType.COMPLETED,
                                        finishReason = event.finishReason,
                                    ),
                                )
                            }
                            "error" -> throw SafeGatewayStreamException()
                            else -> throw SafeGatewayStreamException()
                        }
                    }
                }
                if (!completedEmitted) {
                    emitter.emit(ChatStreamEvent(ChatStreamEventType.COMPLETED))
                }
                ChatBackendResult.Completed
            } catch (_: CancellationException) {
                ChatBackendResult.Failed(
                    failure(ChatBackendFailureCode.CANCELLED, deltaEmitted, retryable = true),
                )
            } catch (_: SafeGatewayStreamException) {
                ChatBackendResult.Failed(
                    failure(ChatBackendFailureCode.INVALID_RESPONSE, deltaEmitted, retryable = false),
                )
            } catch (error: GatewayClientException) {
                ChatBackendResult.Failed(mapGatewayFailure(error, deltaEmitted))
            } catch (_: Exception) {
                ChatBackendResult.Failed(
                    failure(ChatBackendFailureCode.INTERNAL_ERROR, deltaEmitted, retryable = false),
                )
            }
        }

        private fun mapGatewayFailure(
            error: GatewayClientException,
            deltaEmitted: Boolean,
        ): ChatBackendFailure {
            val code = when (error.errorCode) {
                GatewayClientErrorCode.CANCELLED -> ChatBackendFailureCode.CANCELLED
                GatewayClientErrorCode.REQUEST_TOO_LARGE,
                GatewayClientErrorCode.RESPONSE_TOO_LARGE,
                GatewayClientErrorCode.STREAM_TOO_LARGE,
                -> ChatBackendFailureCode.RESPONSE_TOO_LARGE
                GatewayClientErrorCode.MALFORMED_JSON,
                GatewayClientErrorCode.MALFORMED_SSE,
                GatewayClientErrorCode.INVALID_ENDPOINT,
                -> ChatBackendFailureCode.INVALID_RESPONSE
                GatewayClientErrorCode.CONNECTION_FAILED ->
                    ChatBackendFailureCode.NETWORK_UNAVAILABLE
                GatewayClientErrorCode.HTTP_ERROR -> when (error.statusCode) {
                    401, 403 -> ChatBackendFailureCode.AUTHENTICATION_REQUIRED
                    429 -> ChatBackendFailureCode.RATE_LIMITED
                    in 400..499 -> ChatBackendFailureCode.PROVIDER_REJECTED
                    else -> ChatBackendFailureCode.PROVIDER_UNAVAILABLE
                }
            }
            val retryable = code in setOf(
                ChatBackendFailureCode.CANCELLED,
                ChatBackendFailureCode.NETWORK_UNAVAILABLE,
                ChatBackendFailureCode.RATE_LIMITED,
                ChatBackendFailureCode.PROVIDER_UNAVAILABLE,
                ChatBackendFailureCode.AUTHENTICATION_REQUIRED,
            )
            return failure(code, deltaEmitted, retryable)
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

        private fun unavailable(
            code: ChatBackendFailureCode,
            retryable: Boolean,
        ) = ChatBackendPreparation.Unavailable(
            ChatBackendFailure(code, ChatFailureStage.PREPARATION, retryable),
        )

        private fun JSONObject.optionalInt(name: String): Int? =
            if (has(name) && !isNull(name)) optInt(name) else null
    }

    private class SafeGatewayStreamException : Exception()
}
