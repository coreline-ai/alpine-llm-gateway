package dev.alpine.chat.routing

/** Host-selected execution mode. The SDK never changes this value silently. */
enum class ChatExecutionMode {
    FAST_CHAT,
    ALPINE_WORKSPACE,
}

enum class ChatBackendKind {
    ANDROID_DIRECT,
    ALPINE_GATEWAY,
}

/**
 * Provider idempotency is explicit. NONE means the router must never replay after dispatch.
 * The current direct and Alpine adapters intentionally declare NONE until each Provider contract
 * has a verified upstream idempotency key.
 */
enum class ChatBackendIdempotency {
    NONE,
    PROVIDER_VERIFIED_KEY,
}

data class ChatBackendCapabilities @JvmOverloads constructor(
    val idempotency: ChatBackendIdempotency = ChatBackendIdempotency.NONE,
)

data class ChatBackendRequest @JvmOverloads constructor(
    val requestId: String,
    val conversationId: String,
    val model: String,
    val requestJson: String,
    val idempotencyKey: String? = null,
) {
    init {
        require(ID_PATTERN.matches(requestId)) { "requestId is invalid" }
        require(ID_PATTERN.matches(conversationId)) { "conversationId is invalid" }
        require(model.isNotBlank() && model.toByteArray().size <= MAX_MODEL_BYTES) {
            "model is invalid"
        }
        require(requestJson.isNotBlank() && requestJson.toByteArray().size <= MAX_REQUEST_BYTES) {
            "requestJson size is invalid"
        }
        idempotencyKey?.let {
            require(IDEMPOTENCY_PATTERN.matches(it)) { "idempotencyKey is invalid" }
        }
    }

    companion object {
        const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
        private const val MAX_MODEL_BYTES = 1_024
        private val ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
        private val IDEMPOTENCY_PATTERN = Regex("[A-Za-z0-9._:-]{16,200}")
    }
}

enum class ChatFailureStage {
    PREPARATION,
    DISPATCH,
    STREAMING,
}

enum class ChatBackendFailureCode {
    RUNTIME_NOT_INSTALLED,
    RUNTIME_REPAIR_REQUIRED,
    RUNTIME_BUSY,
    RUNTIME_START_FAILED,
    AUTHENTICATION_REQUIRED,
    RATE_LIMITED,
    PROVIDER_REJECTED,
    PROVIDER_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    INVALID_RESPONSE,
    RESPONSE_TOO_LARGE,
    CANCELLED,
    FALLBACK_DECLINED,
    DUPLICATE_REQUEST,
    INTERNAL_ERROR,
}

/** Closed failure payload: raw Provider/process exception text is deliberately absent. */
data class ChatBackendFailure(
    val code: ChatBackendFailureCode,
    val stage: ChatFailureStage,
    val retryable: Boolean,
) {
    val providerMayHaveAcceptedRequest: Boolean
        get() = stage != ChatFailureStage.PREPARATION
}

sealed interface ChatBackendPreparation {
    data object Ready : ChatBackendPreparation

    data class Unavailable(val failure: ChatBackendFailure) : ChatBackendPreparation {
        init {
            require(failure.stage == ChatFailureStage.PREPARATION) {
                "preparation failure must use PREPARATION stage"
            }
        }
    }
}

sealed interface ChatBackendResult {
    data object Completed : ChatBackendResult
    data class Failed(val failure: ChatBackendFailure) : ChatBackendResult
}

enum class ChatStreamEventType {
    ROUTE_SELECTED,
    FALLBACK_ACTIVATED,
    STARTED,
    DELTA,
    COMPLETED,
}

/** Safe user-facing stream metadata shared by both backends. */
data class ChatStreamEvent @JvmOverloads constructor(
    val type: ChatStreamEventType,
    val text: String = "",
    val executionMode: ChatExecutionMode? = null,
    val backendId: String? = null,
    val model: String? = null,
    val finishReason: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

fun interface ChatStreamEmitter {
    fun emit(event: ChatStreamEvent)
}

interface ChatBackend {
    val id: String
    val kind: ChatBackendKind
    val capabilities: ChatBackendCapabilities

    suspend fun prepare(request: ChatBackendRequest): ChatBackendPreparation

    suspend fun stream(
        request: ChatBackendRequest,
        emitter: ChatStreamEmitter,
    ): ChatBackendResult
}

data class ChatRoutingRequest @JvmOverloads constructor(
    val requestedMode: ChatExecutionMode,
    val primaryRequest: ChatBackendRequest,
    /** Required only when an Alpine pre-dispatch failure may be offered as fast-chat fallback. */
    val fastFallbackRequest: ChatBackendRequest? = null,
) {
    init {
        require(
            fastFallbackRequest == null ||
                fastFallbackRequest.requestId == primaryRequest.requestId,
        ) { "fallback request must preserve requestId" }
        require(
            fastFallbackRequest == null ||
                fastFallbackRequest.conversationId == primaryRequest.conversationId,
        ) { "fallback request must preserve conversationId" }
    }
}

data class ChatFallbackApprovalRequest(
    val requestId: String,
    val conversationId: String,
    val fromMode: ChatExecutionMode,
    val toMode: ChatExecutionMode,
    val fromBackendId: String,
    val toBackendId: String,
    val fromModel: String,
    val toModel: String,
    val reason: ChatBackendFailureCode,
) {
    val modelWillChange: Boolean
        get() = fromModel != toModel
}

fun interface ChatFallbackAuthorizer {
    suspend fun approve(request: ChatFallbackApprovalRequest): Boolean

    companion object {
        val DENY = ChatFallbackAuthorizer { false }
    }
}

enum class ChatRoutingOutcome {
    COMPLETED,
    FAILED,
    FALLBACK_DECLINED,
    DUPLICATE_REJECTED,
}

data class ChatRoutingResult(
    val requestId: String,
    val requestedMode: ChatExecutionMode,
    val effectiveMode: ChatExecutionMode,
    val backendId: String,
    val model: String,
    val outcome: ChatRoutingOutcome,
    val fallbackUsed: Boolean,
    val firstDeltaEmitted: Boolean,
    val failure: ChatBackendFailure? = null,
)

enum class ChatRoutingAuditType {
    REQUEST_ACCEPTED,
    MODE_SELECTED,
    FALLBACK_APPROVAL_REQUIRED,
    FALLBACK_APPROVED,
    FALLBACK_DECLINED,
    BACKEND_DISPATCHED,
    FIRST_DELTA,
    COMPLETED,
    FAILED,
    DUPLICATE_REJECTED,
}

/** Closed audit event. Request JSON, prompts, credentials, URLs, and exception text are excluded. */
data class ChatRoutingAuditEvent(
    val type: ChatRoutingAuditType,
    val timestampEpochMillis: Long,
    val requestId: String,
    val conversationId: String,
    val requestedMode: ChatExecutionMode,
    val effectiveMode: ChatExecutionMode,
    val backendId: String,
    val model: String,
    val failureCode: ChatBackendFailureCode? = null,
)

fun interface ChatRoutingAuditSink {
    fun emit(event: ChatRoutingAuditEvent)

    companion object {
        val NONE = ChatRoutingAuditSink { }
    }
}
