package dev.alpine.chat.feature.ui.state

enum class ChatFailureKind {
    REAUTHENTICATION_REQUIRED,
    TIMEOUT,
    OVERLOADED,
    PROVIDER_UNAVAILABLE,
    CIRCUIT_OPEN,
    INVALID_RESPONSE,
    NETWORK,
    UNKNOWN,
}

enum class ChatRecoveryAction {
    RETRY,
    RECONNECT,
    CHECK_SETTINGS,
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
