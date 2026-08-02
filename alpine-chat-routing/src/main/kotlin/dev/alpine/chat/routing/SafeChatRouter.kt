package dev.alpine.chat.routing

import java.util.concurrent.CancellationException

/**
 * Routes exactly one backend per request. Alpine fallback is possible only after a preparation
 * failure, explicit host approval, and before any Provider dispatch.
 */
class SafeChatRouter @JvmOverloads constructor(
    private val directBackend: ChatBackend,
    private val alpineBackend: ChatBackend,
    private val ledger: ChatRequestLedger = InMemoryChatRequestLedger(),
    private val auditSink: ChatRoutingAuditSink = ChatRoutingAuditSink.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(directBackend.kind == ChatBackendKind.ANDROID_DIRECT)
        require(alpineBackend.kind == ChatBackendKind.ALPINE_GATEWAY)
        require(directBackend.id.isNotBlank() && alpineBackend.id.isNotBlank())
        require(directBackend.id != alpineBackend.id)
    }

    suspend fun route(
        routingRequest: ChatRoutingRequest,
        authorizer: ChatFallbackAuthorizer = ChatFallbackAuthorizer.DENY,
        emitter: ChatStreamEmitter = ChatStreamEmitter { },
    ): ChatRoutingResult {
        val request = routingRequest.primaryRequest
        val initialBackend = when (routingRequest.requestedMode) {
            ChatExecutionMode.FAST_CHAT -> directBackend
            ChatExecutionMode.ALPINE_WORKSPACE -> alpineBackend
        }
        if (!ledger.claim(request.requestId)) {
            val failure = ChatBackendFailure(
                ChatBackendFailureCode.DUPLICATE_REQUEST,
                ChatFailureStage.PREPARATION,
                retryable = false,
            )
            audit(
                ChatRoutingAuditType.DUPLICATE_REJECTED,
                routingRequest,
                routingRequest.requestedMode,
                initialBackend,
                request,
                failure.code,
            )
            return result(
                routingRequest,
                routingRequest.requestedMode,
                initialBackend,
                request,
                ChatRoutingOutcome.DUPLICATE_REJECTED,
                fallbackUsed = false,
                firstDelta = false,
                failure = failure,
            )
        }

        try {
            audit(
                ChatRoutingAuditType.REQUEST_ACCEPTED,
                routingRequest,
                routingRequest.requestedMode,
                initialBackend,
                request,
            )
            var effectiveMode = routingRequest.requestedMode
            var backend = initialBackend
            var backendRequest = request
            var fallbackUsed = false
            emitRoute(emitter, ChatStreamEventType.ROUTE_SELECTED, effectiveMode, backend, backendRequest)
            audit(
                ChatRoutingAuditType.MODE_SELECTED,
                routingRequest,
                effectiveMode,
                backend,
                backendRequest,
            )

            val primaryPreparation = safePrepare(backend, backendRequest)
            if (primaryPreparation is ChatBackendPreparation.Unavailable) {
                val fallbackRequest = routingRequest.fastFallbackRequest
                val canOfferFallback =
                    routingRequest.requestedMode == ChatExecutionMode.ALPINE_WORKSPACE &&
                        fallbackRequest != null &&
                        primaryPreparation.failure.code in FALLBACK_ELIGIBLE_FAILURES
                if (!canOfferFallback) {
                    auditFailure(
                        routingRequest,
                        effectiveMode,
                        backend,
                        backendRequest,
                        primaryPreparation.failure,
                    )
                    return result(
                        routingRequest,
                        effectiveMode,
                        backend,
                        backendRequest,
                        ChatRoutingOutcome.FAILED,
                        fallbackUsed,
                        firstDelta = false,
                        failure = primaryPreparation.failure,
                    )
                }

                audit(
                    ChatRoutingAuditType.FALLBACK_APPROVAL_REQUIRED,
                    routingRequest,
                    effectiveMode,
                    backend,
                    backendRequest,
                    primaryPreparation.failure.code,
                )
                val approval = ChatFallbackApprovalRequest(
                    requestId = request.requestId,
                    conversationId = request.conversationId,
                    fromMode = ChatExecutionMode.ALPINE_WORKSPACE,
                    toMode = ChatExecutionMode.FAST_CHAT,
                    fromBackendId = alpineBackend.id,
                    toBackendId = directBackend.id,
                    fromModel = request.model,
                    toModel = fallbackRequest.model,
                    reason = primaryPreparation.failure.code,
                )
                val approved = try {
                    authorizer.approve(approval)
                } catch (_: CancellationException) {
                    val cancelled = ChatBackendFailure(
                        ChatBackendFailureCode.CANCELLED,
                        ChatFailureStage.PREPARATION,
                        retryable = true,
                    )
                    auditFailure(
                        routingRequest,
                        effectiveMode,
                        backend,
                        backendRequest,
                        cancelled,
                    )
                    return result(
                        routingRequest,
                        effectiveMode,
                        backend,
                        backendRequest,
                        ChatRoutingOutcome.FAILED,
                        fallbackUsed = false,
                        firstDelta = false,
                        failure = cancelled,
                    )
                } catch (_: Exception) {
                    false
                }
                if (!approved) {
                    val declined = ChatBackendFailure(
                        ChatBackendFailureCode.FALLBACK_DECLINED,
                        ChatFailureStage.PREPARATION,
                        retryable = true,
                    )
                    audit(
                        ChatRoutingAuditType.FALLBACK_DECLINED,
                        routingRequest,
                        effectiveMode,
                        backend,
                        backendRequest,
                        declined.code,
                    )
                    return result(
                        routingRequest,
                        effectiveMode,
                        backend,
                        backendRequest,
                        ChatRoutingOutcome.FALLBACK_DECLINED,
                        fallbackUsed = false,
                        firstDelta = false,
                        failure = declined,
                    )
                }

                effectiveMode = ChatExecutionMode.FAST_CHAT
                backend = directBackend
                backendRequest = fallbackRequest
                fallbackUsed = true
                audit(
                    ChatRoutingAuditType.FALLBACK_APPROVED,
                    routingRequest,
                    effectiveMode,
                    backend,
                    backendRequest,
                    primaryPreparation.failure.code,
                )
                emitRoute(
                    emitter,
                    ChatStreamEventType.FALLBACK_ACTIVATED,
                    effectiveMode,
                    backend,
                    backendRequest,
                )
                val fallbackPreparation = safePrepare(backend, backendRequest)
                if (fallbackPreparation is ChatBackendPreparation.Unavailable) {
                    auditFailure(
                        routingRequest,
                        effectiveMode,
                        backend,
                        backendRequest,
                        fallbackPreparation.failure,
                    )
                    return result(
                        routingRequest,
                        effectiveMode,
                        backend,
                        backendRequest,
                        ChatRoutingOutcome.FAILED,
                        fallbackUsed,
                        firstDelta = false,
                        failure = fallbackPreparation.failure,
                    )
                }
            }

            audit(
                ChatRoutingAuditType.BACKEND_DISPATCHED,
                routingRequest,
                effectiveMode,
                backend,
                backendRequest,
            )
            var firstDelta = false
            val backendResult = try {
                backend.stream(
                    backendRequest,
                    ChatStreamEmitter { event ->
                        if (event.type == ChatStreamEventType.DELTA && !firstDelta) {
                            firstDelta = true
                            audit(
                                ChatRoutingAuditType.FIRST_DELTA,
                                routingRequest,
                                effectiveMode,
                                backend,
                                backendRequest,
                            )
                        }
                        emitter.emit(event)
                    },
                )
            } catch (_: CancellationException) {
                ChatBackendResult.Failed(
                    ChatBackendFailure(
                        ChatBackendFailureCode.CANCELLED,
                        if (firstDelta) ChatFailureStage.STREAMING else ChatFailureStage.DISPATCH,
                        retryable = true,
                    ),
                )
            } catch (_: Exception) {
                ChatBackendResult.Failed(
                    ChatBackendFailure(
                        ChatBackendFailureCode.INTERNAL_ERROR,
                        if (firstDelta) ChatFailureStage.STREAMING else ChatFailureStage.DISPATCH,
                        retryable = !firstDelta,
                    ),
                )
            }

            return when (backendResult) {
                ChatBackendResult.Completed -> {
                    audit(
                        ChatRoutingAuditType.COMPLETED,
                        routingRequest,
                        effectiveMode,
                        backend,
                        backendRequest,
                    )
                    result(
                        routingRequest,
                        effectiveMode,
                        backend,
                        backendRequest,
                        ChatRoutingOutcome.COMPLETED,
                        fallbackUsed,
                        firstDelta,
                    )
                }
                is ChatBackendResult.Failed -> {
                    auditFailure(
                        routingRequest,
                        effectiveMode,
                        backend,
                        backendRequest,
                        backendResult.failure,
                    )
                    result(
                        routingRequest,
                        effectiveMode,
                        backend,
                        backendRequest,
                        ChatRoutingOutcome.FAILED,
                        fallbackUsed,
                        firstDelta,
                        backendResult.failure,
                    )
                }
            }
        } finally {
            ledger.complete(request.requestId)
        }
    }

    private suspend fun safePrepare(
        backend: ChatBackend,
        request: ChatBackendRequest,
    ): ChatBackendPreparation = try {
        backend.prepare(request)
    } catch (_: CancellationException) {
        ChatBackendPreparation.Unavailable(
            ChatBackendFailure(
                ChatBackendFailureCode.CANCELLED,
                ChatFailureStage.PREPARATION,
                retryable = true,
            ),
        )
    } catch (_: Exception) {
        ChatBackendPreparation.Unavailable(
            ChatBackendFailure(
                ChatBackendFailureCode.INTERNAL_ERROR,
                ChatFailureStage.PREPARATION,
                retryable = true,
            ),
        )
    }

    private fun emitRoute(
        emitter: ChatStreamEmitter,
        type: ChatStreamEventType,
        mode: ChatExecutionMode,
        backend: ChatBackend,
        request: ChatBackendRequest,
    ) {
        emitter.emit(
            ChatStreamEvent(
                type = type,
                executionMode = mode,
                backendId = backend.id,
                model = request.model,
            ),
        )
    }

    private fun auditFailure(
        routingRequest: ChatRoutingRequest,
        mode: ChatExecutionMode,
        backend: ChatBackend,
        request: ChatBackendRequest,
        failure: ChatBackendFailure,
    ) = audit(
        ChatRoutingAuditType.FAILED,
        routingRequest,
        mode,
        backend,
        request,
        failure.code,
    )

    private fun audit(
        type: ChatRoutingAuditType,
        routingRequest: ChatRoutingRequest,
        effectiveMode: ChatExecutionMode,
        backend: ChatBackend,
        request: ChatBackendRequest,
        failureCode: ChatBackendFailureCode? = null,
    ) {
        auditSink.emit(
            ChatRoutingAuditEvent(
                type = type,
                timestampEpochMillis = clock(),
                requestId = request.requestId,
                conversationId = request.conversationId,
                requestedMode = routingRequest.requestedMode,
                effectiveMode = effectiveMode,
                backendId = backend.id,
                model = request.model,
                failureCode = failureCode,
            ),
        )
    }

    private fun result(
        routingRequest: ChatRoutingRequest,
        mode: ChatExecutionMode,
        backend: ChatBackend,
        request: ChatBackendRequest,
        outcome: ChatRoutingOutcome,
        fallbackUsed: Boolean,
        firstDelta: Boolean,
        failure: ChatBackendFailure? = null,
    ) = ChatRoutingResult(
        requestId = request.requestId,
        requestedMode = routingRequest.requestedMode,
        effectiveMode = mode,
        backendId = backend.id,
        model = request.model,
        outcome = outcome,
        fallbackUsed = fallbackUsed,
        firstDeltaEmitted = firstDelta,
        failure = failure,
    )

    companion object {
        private val FALLBACK_ELIGIBLE_FAILURES = setOf(
            ChatBackendFailureCode.RUNTIME_NOT_INSTALLED,
            ChatBackendFailureCode.RUNTIME_REPAIR_REQUIRED,
            ChatBackendFailureCode.RUNTIME_BUSY,
            ChatBackendFailureCode.RUNTIME_START_FAILED,
        )
    }
}
