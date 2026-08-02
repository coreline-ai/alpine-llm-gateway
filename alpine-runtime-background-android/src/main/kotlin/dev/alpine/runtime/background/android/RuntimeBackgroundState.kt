package dev.alpine.runtime.background.android

/** Persisted state contains lifecycle metadata only; commands, prompts and credentials are forbidden. */
enum class RuntimeBackgroundState {
    STOPPED,
    START_REQUESTED,
    ACTIVE,
    STOP_REQUESTED,
    RECOVERED_AFTER_PROCESS_DEATH,
}

data class RuntimeBackgroundSnapshot(
    val state: RuntimeBackgroundState,
    val updatedAtEpochMillis: Long,
)

enum class RuntimeBackgroundStartResult {
    START_REQUESTED,
    ALREADY_REQUESTED_OR_ACTIVE,
    START_NOT_ALLOWED,
    START_FAILED,
}

enum class RuntimeBackgroundEvent {
    START_REQUESTED,
    ACTIVE,
    STOP_REQUESTED,
    STOPPED,
    RECOVERED_AFTER_PROCESS_DEATH,
    START_NOT_ALLOWED,
    START_FAILED,
}

fun interface RuntimeBackgroundEventListener {
    fun onEvent(event: RuntimeBackgroundEvent)
}
