package dev.alpine.codex.appserver

enum class CodexAppServerErrorCode {
    ARTIFACT_UNAVAILABLE,
    ARTIFACT_INVALID,
    TRUST_STORE_UNAVAILABLE,
    NETWORK_BRIDGE_FAILED,
    PROCESS_START_FAILED,
    PROCESS_EXITED,
    PROCESS_TERMINATION_FAILED,
    PROTOCOL_INVALID,
    RESPONSE_TOO_LARGE,
    REQUEST_TIMEOUT,
    SERVER_OVERLOADED,
    AUTH_URL_REJECTED,
    BROWSER_UNAVAILABLE,
    AUTHENTICATION_REQUIRED,
    LOGIN_FAILED,
    UNSUPPORTED_SERVER_REQUEST,
    UNSUPPORTED_AGENT_ACTION,
    THREAD_REATTACH_REQUIRED,
    UNKNOWN,
}

/** Closed failure contract: raw stderr, JSON-RPC bodies, URLs, and credentials are never attached. */
class CodexAppServerException(
    val code: CodexAppServerErrorCode,
    cause: Throwable? = null,
) : Exception(code.name, cause)
