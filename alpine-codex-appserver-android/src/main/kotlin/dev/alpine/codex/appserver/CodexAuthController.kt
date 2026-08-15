package dev.alpine.codex.appserver

import java.io.Closeable
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CodexAuthState {
    data object Checking : CodexAuthState
    data object SignedOut : CodexAuthState
    data object SignedIn : CodexAuthState

    data class BrowserPending(
        val loginId: String,
        val authorizationUri: URI,
    ) : CodexAuthState

    data class DeviceCodePending(
        val loginId: String,
        val verificationUri: URI,
        val userCode: String,
    ) : CodexAuthState

    /** Closed UI-safe failure. It contains no server message, URL, email, or credential. */
    data class Failed(val code: CodexAppServerErrorCode) : CodexAuthState
}

/**
 * Process-lifetime account state machine. The notification collector subscribes undispatched so
 * `account/login/completed` cannot race a newly started login request.
 */
class CodexAuthController(
    private val account: CodexAccountApi,
    private val scope: CoroutineScope,
    private val loginTimeoutMs: Long = DEFAULT_LOGIN_TIMEOUT_MS,
) : Closeable {
    init {
        require(loginTimeoutMs > 0)
    }

    private val operationLock = Mutex()
    private val mutableState = MutableStateFlow<CodexAuthState>(CodexAuthState.Checking)
    val state: StateFlow<CodexAuthState> = mutableState.asStateFlow()
    private var pendingTimeoutJob: Job? = null
    private val notificationJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        account.notifications.collect { notification ->
            notification.clientFailureCode?.let { code ->
                operationLock.withLock {
                    clearLoginTimeout()
                    mutableState.value = CodexAuthState.Failed(code)
                }
                return@collect
            }
            when (notification.method) {
                LOGIN_COMPLETED -> handleLoginCompleted(notification.params)
                ACCOUNT_UPDATED -> refresh()
            }
        }
    }

    suspend fun refresh() = operationLock.withLock {
        refreshLocked()
    }

    suspend fun startBrowserLogin(): CodexLoginStart.Browser = operationLock.withLock {
        cancelPendingLocked()
        try {
            account.startBrowserLogin().also { login ->
                mutableState.value = CodexAuthState.BrowserPending(
                    login.loginId,
                    login.authorizationUri,
                )
                armLoginTimeout(login.loginId)
            }
        } catch (failure: Throwable) {
            mutableState.value = CodexAuthState.Failed(failure.safeCode())
            throw failure
        }
    }

    suspend fun startDeviceCodeLogin(): CodexLoginStart.DeviceCode = operationLock.withLock {
        cancelPendingLocked()
        try {
            account.startDeviceCodeLogin().also { login ->
                mutableState.value = CodexAuthState.DeviceCodePending(
                    login.loginId,
                    login.verificationUri,
                    login.userCode,
                )
                armLoginTimeout(login.loginId)
            }
        } catch (failure: Throwable) {
            mutableState.value = CodexAuthState.Failed(failure.safeCode())
            throw failure
        }
    }

    suspend fun cancelLogin() = operationLock.withLock {
        cancelPendingLocked()
        refreshLocked()
    }

    /** Cancels the current server login action and exposes only the supplied UI-safe code. */
    suspend fun failPendingLogin(code: CodexAppServerErrorCode) = operationLock.withLock {
        val loginId = pendingLoginId()
        clearLoginTimeout()
        if (loginId != null) cancelIgnoringFailure(loginId)
        mutableState.value = CodexAuthState.Failed(code)
    }

    suspend fun logout() = operationLock.withLock {
        try {
            cancelPendingLocked()
            account.logout()
            refreshLocked()
        } catch (failure: Throwable) {
            mutableState.value = CodexAuthState.Failed(failure.safeCode())
            throw failure
        }
    }

    private suspend fun handleLoginCompleted(params: org.json.JSONObject) = operationLock.withLock {
        val pendingId = pendingLoginId() ?: return@withLock
        val success = params.opt("success") as? Boolean
        val rawLoginId = params.opt("loginId")
        val notificationId = when (rawLoginId) {
            null, org.json.JSONObject.NULL -> null
            is String -> rawLoginId.takeIf(::isBoundedLoginId)
            else -> null
        }
        if (success == null ||
            (rawLoginId != null && rawLoginId != org.json.JSONObject.NULL && notificationId == null)
        ) {
            clearLoginTimeout()
            mutableState.value = CodexAuthState.Failed(CodexAppServerErrorCode.PROTOCOL_INVALID)
            return@withLock
        }
        if (notificationId != null && notificationId != pendingId) return@withLock
        if (!success) {
            clearLoginTimeout()
            mutableState.value = CodexAuthState.Failed(CodexAppServerErrorCode.LOGIN_FAILED)
            return@withLock
        }
        refreshLocked()
    }

    private suspend fun refreshLocked() {
        val pending = mutableState.value.takeIf {
            it is CodexAuthState.BrowserPending || it is CodexAuthState.DeviceCodePending
        }
        if (pending == null) mutableState.value = CodexAuthState.Checking
        val refreshed = try {
            when (account.accountState()) {
                CodexAccountState.CHATGPT -> CodexAuthState.SignedIn
                CodexAccountState.SIGNED_OUT -> pending ?: CodexAuthState.SignedOut
                CodexAccountState.OTHER ->
                    CodexAuthState.Failed(CodexAppServerErrorCode.AUTHENTICATION_REQUIRED)
            }
        } catch (failure: Throwable) {
            CodexAuthState.Failed(failure.safeCode())
        }
        if (refreshed !is CodexAuthState.BrowserPending &&
            refreshed !is CodexAuthState.DeviceCodePending
        ) {
            clearLoginTimeout()
        }
        mutableState.value = refreshed
    }

    private suspend fun cancelPendingLocked() {
        val loginId = pendingLoginId() ?: return
        clearLoginTimeout()
        try {
            account.cancelLogin(loginId)
        } finally {
            mutableState.value = CodexAuthState.SignedOut
        }
    }

    private fun pendingLoginId(): String? = when (val current = mutableState.value) {
        is CodexAuthState.BrowserPending -> current.loginId
        is CodexAuthState.DeviceCodePending -> current.loginId
        else -> null
    }

    override fun close() {
        clearLoginTimeout()
        notificationJob.cancel()
    }

    private fun armLoginTimeout(loginId: String) {
        clearLoginTimeout()
        pendingTimeoutJob = scope.launch {
            delay(loginTimeoutMs)
            operationLock.withLock {
                if (pendingLoginId() != loginId) return@withLock
                pendingTimeoutJob = null
                cancelIgnoringFailure(loginId)
                mutableState.value = CodexAuthState.Failed(
                    CodexAppServerErrorCode.REQUEST_TIMEOUT,
                )
            }
        }
    }

    private fun clearLoginTimeout() {
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = null
    }

    private suspend fun cancelIgnoringFailure(loginId: String) {
        try {
            account.cancelLogin(loginId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The caller still publishes its explicit safe terminal state.
        }
    }

    private fun isBoundedLoginId(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_LOGIN_ID_LENGTH && value.all { character ->
            character.isLetterOrDigit() || character in "-_"
        }

    private fun Throwable.safeCode(): CodexAppServerErrorCode {
        if (this is CancellationException) throw this
        return (this as? CodexAppServerException)?.code ?: CodexAppServerErrorCode.UNKNOWN
    }

    private companion object {
        const val LOGIN_COMPLETED = "account/login/completed"
        const val ACCOUNT_UPDATED = "account/updated"
        const val DEFAULT_LOGIN_TIMEOUT_MS = 10L * 60L * 1000L
        const val MAX_LOGIN_ID_LENGTH = 128
    }
}
