package dev.alpine.chat.feature.ui.state

import dev.alpine.chat.feature.backend.ChatBackendException
import dev.alpine.chat.feature.backend.ChatBackendFailureCode
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Safe status metadata captured by the client layer.  It intentionally does
 * not include an endpoint, response body, headers, or an exception message.
 */
data class SafeProviderStatus(
    val statusCode: Int,
    val retryAfterSeconds: Int? = null,
) {
    init {
        require(statusCode in 100..599) { "status code must be an HTTP status" }
    }

    val safeRetryAfterSeconds: Int?
        get() = retryAfterSeconds?.takeIf { it >= 0 }
}

/** Exception wrapper used only to carry [SafeProviderStatus] across a coroutine boundary. */
class SafeProviderStatusException(
    val status: SafeProviderStatus,
) : Exception()

/** Converts transport/authentication failures into the closed UI failure schema. */
object ChatFailureMapper {
    fun map(error: Throwable): ChatFailure = when (error) {
        is ChatBackendException -> mapBackend(error.code)
        is SafeProviderStatusException -> mapStatus(error.status)
        is TimeoutCancellationException,
        is SocketTimeoutException,
        -> ChatFailure(
            kind = ChatFailureKind.TIMEOUT,
            recoveryAction = ChatRecoveryAction.RETRY,
        )
        is IOException -> ChatFailure(
            kind = ChatFailureKind.NETWORK,
            recoveryAction = ChatRecoveryAction.RETRY,
        )
        else -> ChatFailure(
            kind = ChatFailureKind.UNKNOWN,
            recoveryAction = ChatRecoveryAction.CHECK_SETTINGS,
        )
    }

    fun mapStatus(status: SafeProviderStatus): ChatFailure = when (status.statusCode) {
        401, 403 -> reauthenticationRequired()
        429 -> ChatFailure(
            kind = ChatFailureKind.OVERLOADED,
            recoveryAction = ChatRecoveryAction.RETRY,
            retryAfterSeconds = status.safeRetryAfterSeconds,
        )
        500, 501, 503, 504 -> ChatFailure(
            kind = ChatFailureKind.PROVIDER_UNAVAILABLE,
            recoveryAction = ChatRecoveryAction.RETRY,
        )
        // The gateway normalizes a malformed provider protocol to HTTP 502.
        502 -> ChatFailure(
            kind = ChatFailureKind.INVALID_RESPONSE,
            recoveryAction = ChatRecoveryAction.RETRY,
        )
        else -> ChatFailure(
            kind = ChatFailureKind.UNKNOWN,
            recoveryAction = ChatRecoveryAction.CHECK_SETTINGS,
        )
    }

    private fun mapBackend(code: ChatBackendFailureCode): ChatFailure = when (code) {
        ChatBackendFailureCode.REAUTHENTICATION_REQUIRED -> reauthenticationRequired()
        ChatBackendFailureCode.TIMEOUT -> ChatFailure(
            kind = ChatFailureKind.TIMEOUT,
            recoveryAction = ChatRecoveryAction.RETRY,
        )
        ChatBackendFailureCode.CIRCUIT_OPEN -> ChatFailure(
            kind = ChatFailureKind.CIRCUIT_OPEN,
            recoveryAction = ChatRecoveryAction.RETRY,
        )
        ChatBackendFailureCode.INVALID_RESPONSE -> ChatFailure(
            kind = ChatFailureKind.INVALID_RESPONSE,
            recoveryAction = ChatRecoveryAction.RETRY,
        )
        ChatBackendFailureCode.NETWORK -> ChatFailure(
            kind = ChatFailureKind.NETWORK,
            recoveryAction = ChatRecoveryAction.RETRY,
        )
        ChatBackendFailureCode.UNKNOWN -> ChatFailure(
            kind = ChatFailureKind.UNKNOWN,
            recoveryAction = ChatRecoveryAction.CHECK_SETTINGS,
        )
    }

    private fun reauthenticationRequired() = ChatFailure(
        kind = ChatFailureKind.REAUTHENTICATION_REQUIRED,
        recoveryAction = ChatRecoveryAction.RECONNECT,
    )
}
