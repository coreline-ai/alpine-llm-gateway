package dev.alpine.runtime.api

/** Stable lifecycle states exposed to host applications. */
enum class RuntimeLifecycleState {
    NOT_INSTALLED,
    INSTALLING,
    READY,
    STARTING,
    RUNNING,
    STOPPING,
    REPAIR_REQUIRED,
    FAILED,
}

/** Immutable state snapshot. Detail values are safe, closed error codes rather than raw exceptions. */
data class RuntimeState @JvmOverloads constructor(
    val lifecycle: RuntimeLifecycleState,
    val progressPercent: Int? = null,
    val activeVersion: String? = null,
    val detailCode: RuntimeErrorCode? = null,
) {
    init {
        require(progressPercent == null || progressPercent in 0..100) {
            "progressPercent must be in 0..100"
        }
    }
}

enum class RuntimeErrorCode {
    UNSUPPORTED_ABI,
    ARTIFACT_NOT_FOUND,
    ARTIFACT_INTEGRITY_FAILED,
    STORAGE_UNAVAILABLE,
    INSTALL_CANCELLED,
    PROCESS_START_FAILED,
    PROCESS_EXITED,
    COMMAND_FAILED,
    TERMINAL_UNAVAILABLE,
    TERMINAL_RESIZE_UNSUPPORTED,
    HEALTH_CHECK_FAILED,
    BRIDGE_UNAVAILABLE,
    INVALID_REQUEST,
    INTERNAL_ERROR,
}

/** Public failure with a stable code and no raw provider/process exception text. */
class RuntimeOperationException(
    val errorCode: RuntimeErrorCode,
) : RuntimeException(errorCode.name)

enum class RuntimeEventKind {
    STATE_CHANGED,
    INSTALL_PROGRESS,
    SESSION_STARTED,
    SESSION_STOPPED,
    COMMAND_FINISHED,
    TERMINAL_OPENED,
    TERMINAL_CLOSED,
    HEALTH_CHANGED,
    ERROR,
}

/** Structured telemetry/event payload. Implementations must not place secrets in attributes. */
data class RuntimeEvent @JvmOverloads constructor(
    val kind: RuntimeEventKind,
    val timestampEpochMillis: Long,
    val sessionId: String? = null,
    val errorCode: RuntimeErrorCode? = null,
    val attributes: Map<String, String> = emptyMap(),
)

fun interface RuntimeEventSink {
    fun emit(event: RuntimeEvent)
}

fun interface RuntimeStateListener {
    fun onStateChanged(state: RuntimeState)
}

fun interface RuntimeEventListener {
    fun onEvent(event: RuntimeEvent)
}

fun interface RuntimeSubscription : AutoCloseable {
    override fun close()
}

enum class RuntimeHostProcessEventKind {
    STARTED,
    STOPPED,
}

/** Hook used by a host app to own Foreground Service/background policy. */
data class RuntimeHostProcessEvent(
    val kind: RuntimeHostProcessEventKind,
    val sessionId: String,
    val processId: Long,
    val timestampEpochMillis: Long,
)

fun interface RuntimeHostProcessListener {
    fun onProcessEvent(event: RuntimeHostProcessEvent)
}
