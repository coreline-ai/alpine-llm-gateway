package dev.alpine.llm.demo.ui.state

import dev.alpine.llm.OAuthException
import dev.alpine.llm.OAuthFailureKind
import dev.alpine.llm.OAuthRequiredException
import dev.alpine.llm.ProviderCircuitOpenException
import dev.alpine.llm.ProviderStreamException
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
        is OAuthRequiredException -> reauthenticationRequired()
        is OAuthException -> mapOAuth(error)
        is ProviderCircuitOpenException -> ChatFailure(
            kind = ChatFailureKind.CIRCUIT_OPEN,
            recoveryAction = ChatRecoveryAction.RETRY,
        )
        is ProviderStreamException -> ChatFailure(
            kind = ChatFailureKind.INVALID_RESPONSE,
            recoveryAction = ChatRecoveryAction.RETRY,
        )
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

    private fun mapOAuth(error: OAuthException): ChatFailure = when (error.kind) {
        OAuthFailureKind.INVALID_GRANT,
        OAuthFailureKind.STORAGE_INVALIDATED,
        OAuthFailureKind.STORAGE_FAILURE,
        -> reauthenticationRequired()
        OAuthFailureKind.CALLBACK_TIMEOUT -> ChatFailure(
            kind = ChatFailureKind.TIMEOUT,
            recoveryAction = ChatRecoveryAction.RETRY,
        )
        else -> ChatFailure(
            kind = ChatFailureKind.UNKNOWN,
            recoveryAction = ChatRecoveryAction.CHECK_SETTINGS,
        )
    }

    private fun reauthenticationRequired() = ChatFailure(
        kind = ChatFailureKind.REAUTHENTICATION_REQUIRED,
        recoveryAction = ChatRecoveryAction.RECONNECT,
    )
}
