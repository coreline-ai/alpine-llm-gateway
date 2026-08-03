package dev.alpine.chat.feature.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Backend-neutral identity captured by a conversation and rendered by the common UI. */
data class ChatBackendDescriptor(
    val profileId: String,
    val label: String,
    val model: String,
    val modelOptions: List<String> = listOf(model),
) {
    init {
        require(profileId.isNotBlank()) { "profileId is required" }
        require(label.isNotBlank()) { "label is required" }
        require(model.isNotBlank()) { "model is required" }
        require(modelOptions.isNotEmpty() && modelOptions.all(String::isNotBlank)) {
            "modelOptions must contain non-blank models"
        }
        require(model in modelOptions) { "selected model must be present in modelOptions" }
    }
}

enum class ChatBackendConnectionState {
    AVAILABLE,
    SIGNED_OUT,
    REAUTHENTICATION_REQUIRED,
}

data class ChatBackendConnection(
    val descriptor: ChatBackendDescriptor,
    val state: ChatBackendConnectionState,
    val session: ChatBackendSession,
) {
    init {
        require(session.descriptor == descriptor) {
            "connection and session descriptors must match"
        }
    }
}

/** Normalized text delta. Raw Provider events and credentials never cross this boundary. */
data class ChatBackendDelta(val text: String)

data class ChatBackendStreamResult(
    val statusCode: Int = 200,
    val events: Flow<ChatBackendDelta> = emptyFlow(),
) {
    init {
        require(statusCode in 100..599) { "statusCode must be an HTTP status" }
    }
}

/** Implemented by Direct Android and Alpine Gateway adapters without UI dependencies. */
interface ChatBackendSession {
    val descriptor: ChatBackendDescriptor

    suspend fun stream(requestJson: String): ChatBackendStreamResult
}

enum class ChatBackendFailureCode {
    REAUTHENTICATION_REQUIRED,
    TIMEOUT,
    CIRCUIT_OPEN,
    INVALID_RESPONSE,
    NETWORK,
    RATE_LIMITED,
    PROVIDER_UNAVAILABLE,
    RESPONSE_TOO_LARGE,
    RUNTIME_NOT_INSTALLED,
    RUNTIME_REPAIR_REQUIRED,
    RUNTIME_BUSY,
    RUNTIME_START_FAILED,
    FALLBACK_DECLINED,
    UNKNOWN,
}

/** Closed failure contract; it intentionally carries no raw message, URL, body, or headers. */
class ChatBackendException(
    val code: ChatBackendFailureCode,
) : Exception()
