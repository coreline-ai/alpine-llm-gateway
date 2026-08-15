package dev.alpine.chat.feature.ui.state

enum class ChatFailureKind {
    REAUTHENTICATION_REQUIRED,
    TIMEOUT,
    OVERLOADED,
    PROVIDER_UNAVAILABLE,
    CIRCUIT_OPEN,
    INVALID_RESPONSE,
    NETWORK,
    RESPONSE_TOO_LARGE,
    RUNTIME_NOT_INSTALLED,
    RUNTIME_REPAIR_REQUIRED,
    RUNTIME_BUSY,
    RUNTIME_START_FAILED,
    FALLBACK_DECLINED,
    UNSUPPORTED_AGENT_ACTION,
    THREAD_REATTACH_REQUIRED,
    UNKNOWN,
}

enum class ChatRecoveryAction {
    RETRY,
    RECONNECT,
    CHECK_SETTINGS,
    INSTALL_RUNTIME,
    REPAIR_RUNTIME,
    RESTART_RUNTIME,
}

/**
 * Redacted UI failure. It deliberately has no raw Provider body, URL, header,
 * exception message, or credential-bearing field.
 */
data class ChatFailure(
    val kind: ChatFailureKind,
    val recoveryAction: ChatRecoveryAction,
    val retryAfterSeconds: Int? = null,
)

data class ChatRetryTarget(
    val userText: String,
    val profileId: String,
    val model: String,
    val assistantSkillId: String,
    val assistantPersonaId: String,
)
