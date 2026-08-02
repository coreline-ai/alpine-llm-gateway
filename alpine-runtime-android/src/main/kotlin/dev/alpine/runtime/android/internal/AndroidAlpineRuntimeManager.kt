package dev.alpine.runtime.android.internal

import android.content.Context
import android.os.Build
import dev.alpine.runtime.android.AndroidRuntimeConfiguration
import dev.alpine.runtime.api.AlpineRuntimeManager
import dev.alpine.runtime.api.RuntimeCommandRequest
import dev.alpine.runtime.api.RuntimeCommandResult
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeEvent
import dev.alpine.runtime.api.RuntimeEventKind
import dev.alpine.runtime.api.RuntimeEventListener
import dev.alpine.runtime.api.RuntimeHealth
import dev.alpine.runtime.api.RuntimeInstallRequest
import dev.alpine.runtime.api.RuntimeInstallResult
import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.api.RuntimeOperationException
import dev.alpine.runtime.api.RuntimeProcessInfo
import dev.alpine.runtime.api.RuntimeSession
import dev.alpine.runtime.api.RuntimeStartRequest
import dev.alpine.runtime.api.RuntimeState
import dev.alpine.runtime.api.RuntimeStateListener
import dev.alpine.runtime.api.RuntimeStopReason
import dev.alpine.runtime.api.RuntimeSubscription
import dev.alpine.runtime.api.RuntimeTerminalRequest
import dev.alpine.runtime.api.RuntimeTerminalSession
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class AndroidAlpineRuntimeManager(
    appContext: Context,
    private val configuration: AndroidRuntimeConfiguration,
    private val clock: () -> Long = System::currentTimeMillis,
    private val lifecycleExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val commandExecutor: ExecutorService = Executors.newCachedThreadPool(),
) : AlpineRuntimeManager {
    private val runtimeDirectory = File(appContext.filesDir, configuration.runtimeDirectoryName)
    private val workspaceDirectory = File(runtimeDirectory, configuration.workspaceDirectoryName)
    private val installer = RuntimeArtifactInstaller(
        runtimeDirectory = runtimeDirectory,
        workspaceDirectory = workspaceDirectory,
        nativeLibraryDirectory = File(appContext.applicationInfo.nativeLibraryDir),
        limits = RuntimeInstallLimits(
            maxRootfsArchiveBytes = configuration.maxRootfsArchiveBytes,
            maxRootfsExtractedBytes = configuration.maxRootfsExtractedBytes,
            maxRootfsEntries = configuration.maxRootfsEntries,
            maxNativeArtifactBytes = configuration.maxNativeArtifactBytes,
        ),
    )
    private val launcher = ProotProcessLauncher(
        cacheDirectory = appContext.cacheDir,
        environmentContributors = configuration.environmentContributors,
        processListener = configuration.processListener,
        maxOutputBytes = configuration.maxOutputBytes,
        clock = clock,
    )
    private val dnsConfigurator = AndroidDnsConfigurator(appContext)
    private val stateListeners = CopyOnWriteArrayList<RuntimeStateListener>()
    private val eventListeners = CopyOnWriteArrayList<RuntimeEventListener>()
    @Volatile private var state: RuntimeState = stateFromInspection(installer.inspect())
    @Volatile private var activeSession: AndroidRuntimeSession? = null
    @Volatile private var lastInstallRequest = RuntimeInstallRequest()

    override fun currentState(): RuntimeState = state

    override fun addStateListener(listener: RuntimeStateListener): RuntimeSubscription {
        stateListeners += listener
        runCatching { listener.onStateChanged(state) }
        return RuntimeSubscription { stateListeners -= listener }
    }

    override fun addEventListener(listener: RuntimeEventListener): RuntimeSubscription {
        eventListeners += listener
        return RuntimeSubscription { eventListeners -= listener }
    }

    override fun install(request: RuntimeInstallRequest): CompletionStage<RuntimeInstallResult> {
        lastInstallRequest = request
        val future = CompletableFuture<RuntimeInstallResult>()
        lifecycleExecutor.execute {
            if (state.lifecycle in INSTALL_BLOCKED_STATES) {
                future.completeExceptionally(RuntimeOperationException(RuntimeErrorCode.INVALID_REQUEST))
                return@execute
            }
            try {
                updateState(RuntimeState(RuntimeLifecycleState.INSTALLING, 0, state.activeVersion))
                val artifactRequest = request.artifactRequest.copy(
                    supportedAbis = Build.SUPPORTED_ABIS.toList(),
                )
                val bundle = configuration.artifactProvider.resolve(artifactRequest)
                    .toCompletableFuture()
                    .join()
                if (future.isCancelled) throw CancellationException()
                updateState(RuntimeState(RuntimeLifecycleState.INSTALLING, 35, state.activeVersion))
                val result = installer.install(
                    bundle = bundle,
                    supportedAbis = Build.SUPPORTED_ABIS.toList(),
                    forceReinstall = request.forceReinstall,
                    isCancelled = future::isCancelled,
                )
                updateState(RuntimeState(RuntimeLifecycleState.READY, 100, result.runtimeVersion))
                future.complete(result)
            } catch (error: Throwable) {
                recoverStateAfterFailure(error)
                if (!future.isCancelled) future.completeExceptionally(safeFailure(error))
            }
        }
        return future
    }

    override fun start(request: RuntimeStartRequest): CompletionStage<RuntimeSession> = submitLifecycle {
        if (request.workspacePath != GUEST_WORKSPACE_PATH || activeSession != null) {
            throw RuntimeOperationException(RuntimeErrorCode.INVALID_REQUEST)
        }
        if (state.lifecycle != RuntimeLifecycleState.READY) {
            throw RuntimeOperationException(RuntimeErrorCode.HEALTH_CHECK_FAILED)
        }
        updateState(RuntimeState(RuntimeLifecycleState.STARTING, activeVersion = state.activeVersion))
        val installed = installer.activeRuntime()
        dnsConfigurator.refresh(installed.rootfsDirectory)
        val session = AndroidRuntimeSession(
            id = UUID.randomUUID().toString(),
            startedAtEpochMillis = clock(),
            installedRuntime = installed,
            sessionEnvironment = request.environment,
            launcher = launcher,
            commandExecutor = commandExecutor,
            clock = clock,
            onStop = ::stopSession,
            onTerminalEvent = { kind, sessionId -> emit(kind, sessionId) },
        )
        launcher.openSession(session.id)
        activeSession = session
        updateState(RuntimeState(RuntimeLifecycleState.RUNNING, activeVersion = installed.runtimeVersion))
        emit(RuntimeEventKind.SESSION_STARTED, session.id)
        session
    }

    override fun stop(reason: RuntimeStopReason): CompletionStage<Void> {
        val future = CompletableFuture<Void>()
        lifecycleExecutor.execute {
            runCatching {
                if (state.lifecycle == RuntimeLifecycleState.RUNNING ||
                    state.lifecycle == RuntimeLifecycleState.STARTING
                ) {
                    updateState(RuntimeState(RuntimeLifecycleState.STOPPING, activeVersion = state.activeVersion))
                }
                activeSession?.let { session ->
                    session.markClosed()
                    launcher.stopSession(session.id)
                }
                activeSession = null
                val inspection = installer.inspect()
                updateState(stateFromInspection(inspection))
                emit(RuntimeEventKind.SESSION_STOPPED)
            }.fold(
                onSuccess = { future.complete(null) },
                onFailure = { future.completeExceptionally(safeFailure(it)) },
            )
        }
        return future
    }

    override fun repair(): CompletionStage<RuntimeInstallResult> = install(
        RuntimeInstallRequest(
            artifactRequest = lastInstallRequest.artifactRequest,
            forceReinstall = true,
        ),
    )

    override fun reset(): CompletionStage<Void> {
        val future = CompletableFuture<Void>()
        lifecycleExecutor.execute {
            runCatching {
                activeSession?.markClosed()
                launcher.stopAll()
                activeSession = null
                installer.reset()
                updateState(RuntimeState(RuntimeLifecycleState.NOT_INSTALLED))
            }.fold(
                onSuccess = { future.complete(null) },
                onFailure = { future.completeExceptionally(safeFailure(it)) },
            )
        }
        return future
    }

    override fun health(): CompletionStage<RuntimeHealth> = submitLifecycle {
        val inspection = installer.inspect()
        val healthy = inspection.condition == RuntimeInstallationCondition.READY
        RuntimeHealth(
            healthy = healthy,
            lifecycle = state.lifecycle,
            checkedAtEpochMillis = clock(),
            errorCode = if (healthy) null else RuntimeErrorCode.HEALTH_CHECK_FAILED,
            checks = inspection.checks,
        )
    }

    override fun close() {
        runCatching { stop(RuntimeStopReason.USER_REQUEST).toCompletableFuture().join() }
        lifecycleExecutor.shutdownNow()
        commandExecutor.shutdownNow()
    }

    private fun stopSession(sessionId: String): CompletionStage<Void> {
        if (activeSession?.id != sessionId) return CompletableFuture.completedFuture(null)
        return stop(RuntimeStopReason.USER_REQUEST)
    }

    private fun recoverStateAfterFailure(error: Throwable) {
        val inspection = installer.inspect()
        val next = when {
            error is CancellationException && inspection.condition == RuntimeInstallationCondition.NOT_INSTALLED ->
                RuntimeState(RuntimeLifecycleState.NOT_INSTALLED)
            inspection.condition == RuntimeInstallationCondition.READY ->
                RuntimeState(RuntimeLifecycleState.READY, activeVersion = inspection.runtimeVersion)
            inspection.condition == RuntimeInstallationCondition.REPAIR_REQUIRED ->
                RuntimeState(
                    RuntimeLifecycleState.REPAIR_REQUIRED,
                    activeVersion = inspection.runtimeVersion,
                    detailCode = errorCode(error),
                )
            else -> RuntimeState(RuntimeLifecycleState.FAILED, detailCode = errorCode(error))
        }
        updateState(next)
        val cause = unwrap(error)
        val reason = when (cause) {
            is RuntimeInstallException -> cause.message ?: cause.errorCode.name
            is RuntimeOperationException -> cause.errorCode.name
            is CancellationException -> RuntimeErrorCode.INSTALL_CANCELLED.name
            else -> RuntimeErrorCode.INTERNAL_ERROR.name
        }
        emit(
            RuntimeEventKind.ERROR,
            errorCode = errorCode(error),
            attributes = mapOf("reason" to reason.take(160)),
        )
    }

    private fun stateFromInspection(inspection: RuntimeInstallationInspection): RuntimeState = when (
        inspection.condition
    ) {
        RuntimeInstallationCondition.NOT_INSTALLED -> RuntimeState(RuntimeLifecycleState.NOT_INSTALLED)
        RuntimeInstallationCondition.READY -> RuntimeState(
            RuntimeLifecycleState.READY,
            activeVersion = inspection.runtimeVersion,
        )
        RuntimeInstallationCondition.REPAIR_REQUIRED -> RuntimeState(
            RuntimeLifecycleState.REPAIR_REQUIRED,
            activeVersion = inspection.runtimeVersion,
            detailCode = RuntimeErrorCode.HEALTH_CHECK_FAILED,
        )
    }

    private fun updateState(newState: RuntimeState) {
        state = newState
        stateListeners.forEach { listener -> runCatching { listener.onStateChanged(newState) } }
        emit(RuntimeEventKind.STATE_CHANGED, errorCode = newState.detailCode)
    }

    private fun emit(
        kind: RuntimeEventKind,
        sessionId: String? = null,
        errorCode: RuntimeErrorCode? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        val event = RuntimeEvent(kind, clock(), sessionId, errorCode, attributes)
        runCatching { configuration.eventSink.emit(event) }
        eventListeners.forEach { listener -> runCatching { listener.onEvent(event) } }
    }

    private fun <T> submitLifecycle(block: () -> T): CompletionStage<T> {
        val future = CompletableFuture<T>()
        lifecycleExecutor.execute {
            runCatching(block).fold(
                onSuccess = future::complete,
                onFailure = { future.completeExceptionally(safeFailure(it)) },
            )
        }
        return future
    }

    private fun safeFailure(error: Throwable): RuntimeOperationException = RuntimeOperationException(
        errorCode(unwrap(error)),
    )

    private fun errorCode(error: Throwable): RuntimeErrorCode = when (val cause = unwrap(error)) {
        is RuntimeOperationException -> cause.errorCode
        is RuntimeInstallException -> cause.errorCode
        is CancellationException -> RuntimeErrorCode.INSTALL_CANCELLED
        is UnsupportedOperationException -> RuntimeErrorCode.UNSUPPORTED_ABI
        else -> RuntimeErrorCode.INTERNAL_ERROR
    }

    private fun unwrap(error: Throwable): Throwable = when (error) {
        is CompletionException -> error.cause?.let(::unwrap) ?: error
        else -> error
    }

    companion object {
        private const val GUEST_WORKSPACE_PATH = "/workspace"
        private val INSTALL_BLOCKED_STATES = setOf(
            RuntimeLifecycleState.INSTALLING,
            RuntimeLifecycleState.STARTING,
            RuntimeLifecycleState.RUNNING,
            RuntimeLifecycleState.STOPPING,
        )
    }
}

private class AndroidRuntimeSession(
    override val id: String,
    override val startedAtEpochMillis: Long,
    private val installedRuntime: InstalledRuntime,
    private val sessionEnvironment: Map<String, String>,
    private val launcher: ProotProcessLauncher,
    private val commandExecutor: ExecutorService,
    private val clock: () -> Long,
    private val onStop: (String) -> CompletionStage<Void>,
    private val onTerminalEvent: (RuntimeEventKind, String) -> Unit,
) : RuntimeSession {
    @Volatile private var open = true

    override fun execute(request: RuntimeCommandRequest): CompletionStage<RuntimeCommandResult> {
        val future = CompletableFuture<RuntimeCommandResult>()
        if (!open) {
            future.completeExceptionally(RuntimeOperationException(RuntimeErrorCode.PROCESS_EXITED))
            return future
        }
        commandExecutor.execute {
            runCatching {
                launcher.execute(
                    installedRuntime,
                    id,
                    sessionEnvironment,
                    request,
                    isCancelled = future::isCancelled,
                )
            }.fold(future::complete) { error ->
                future.completeExceptionally(
                    if (error is RuntimeOperationException) error
                    else RuntimeOperationException(RuntimeErrorCode.COMMAND_FAILED),
                )
            }
        }
        return future
    }

    override fun openTerminal(request: RuntimeTerminalRequest): CompletionStage<RuntimeTerminalSession> {
        val future = CompletableFuture<RuntimeTerminalSession>()
        if (!open) {
            future.completeExceptionally(RuntimeOperationException(RuntimeErrorCode.PROCESS_EXITED))
            return future
        }
        commandExecutor.execute {
            runCatching {
                launcher.openTerminal(
                    runtime = installedRuntime,
                    sessionId = id,
                    sessionEnvironment = sessionEnvironment,
                    request = request,
                    onClosed = { onTerminalEvent(RuntimeEventKind.TERMINAL_CLOSED, id) },
                )
            }.fold(
                onSuccess = { terminal ->
                    onTerminalEvent(RuntimeEventKind.TERMINAL_OPENED, id)
                    future.complete(terminal)
                },
                onFailure = { error ->
                    future.completeExceptionally(
                        if (error is RuntimeOperationException) error
                        else RuntimeOperationException(RuntimeErrorCode.TERMINAL_UNAVAILABLE),
                    )
                },
            )
        }
        return future
    }

    override fun listProcesses(): CompletionStage<List<RuntimeProcessInfo>> =
        CompletableFuture.completedFuture(launcher.listProcesses(id))

    override fun health(): CompletionStage<RuntimeHealth> = CompletableFuture.completedFuture(
        RuntimeHealth(
            healthy = open,
            lifecycle = if (open) RuntimeLifecycleState.RUNNING else RuntimeLifecycleState.READY,
            checkedAtEpochMillis = clock(),
            errorCode = if (open) null else RuntimeErrorCode.PROCESS_EXITED,
        ),
    )

    override fun stop(reason: RuntimeStopReason): CompletionStage<Void> {
        open = false
        return onStop(id)
    }

    fun markClosed() {
        open = false
    }
}
