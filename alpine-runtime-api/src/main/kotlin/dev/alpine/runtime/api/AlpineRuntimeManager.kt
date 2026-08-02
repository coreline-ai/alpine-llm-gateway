package dev.alpine.runtime.api

import java.util.concurrent.CompletionStage

data class RuntimeInstallRequest @JvmOverloads constructor(
    val artifactRequest: RuntimeArtifactRequest = RuntimeArtifactRequest(),
    val forceReinstall: Boolean = false,
)

data class RuntimeInstallResult(
    val runtimeVersion: String,
    val installedArtifactIds: List<String>,
    val reusedExistingInstall: Boolean,
)

data class RuntimeStartRequest @JvmOverloads constructor(
    val workspacePath: String = "/workspace",
    val environment: Map<String, String> = emptyMap(),
)

data class RuntimeHealth @JvmOverloads constructor(
    val healthy: Boolean,
    val lifecycle: RuntimeLifecycleState,
    val checkedAtEpochMillis: Long,
    val errorCode: RuntimeErrorCode? = null,
    val checks: Map<String, Boolean> = emptyMap(),
)

data class RuntimeProcessInfo @JvmOverloads constructor(
    val processId: Long,
    val command: String,
    val state: String,
    val startedAtEpochMillis: Long? = null,
)

enum class RuntimeStopReason {
    USER_REQUEST,
    HOST_BACKGROUND_POLICY,
    HEALTH_FAILURE,
    RESET,
}

interface RuntimeSession : AutoCloseable {
    val id: String
    val startedAtEpochMillis: Long

    fun execute(request: RuntimeCommandRequest): CompletionStage<RuntimeCommandResult>
    fun openTerminal(request: RuntimeTerminalRequest): CompletionStage<RuntimeTerminalSession>
    fun listProcesses(): CompletionStage<List<RuntimeProcessInfo>>
    fun health(): CompletionStage<RuntimeHealth>
    fun stop(reason: RuntimeStopReason): CompletionStage<Void>

    override fun close() {
        stop(RuntimeStopReason.USER_REQUEST)
    }
}

/** App-neutral runtime entry point. Android Context and UI types belong in adapter modules only. */
interface AlpineRuntimeManager : AutoCloseable {
    fun currentState(): RuntimeState
    fun addStateListener(listener: RuntimeStateListener): RuntimeSubscription
    fun addEventListener(listener: RuntimeEventListener): RuntimeSubscription

    fun install(request: RuntimeInstallRequest): CompletionStage<RuntimeInstallResult>
    fun start(request: RuntimeStartRequest): CompletionStage<RuntimeSession>
    fun stop(reason: RuntimeStopReason): CompletionStage<Void>
    fun repair(): CompletionStage<RuntimeInstallResult>
    fun reset(): CompletionStage<Void>
    fun health(): CompletionStage<RuntimeHealth>

    override fun close() {
        stop(RuntimeStopReason.USER_REQUEST)
    }
}
