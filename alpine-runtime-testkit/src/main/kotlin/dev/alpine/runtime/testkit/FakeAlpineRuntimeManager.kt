package dev.alpine.runtime.testkit

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
import dev.alpine.runtime.api.RuntimeProcessInfo
import dev.alpine.runtime.api.RuntimeSession
import dev.alpine.runtime.api.RuntimeStartRequest
import dev.alpine.runtime.api.RuntimeState
import dev.alpine.runtime.api.RuntimeStateListener
import dev.alpine.runtime.api.RuntimeStopReason
import dev.alpine.runtime.api.RuntimeSubscription
import dev.alpine.runtime.api.RuntimeTerminalOutputListener
import dev.alpine.runtime.api.RuntimeTerminalRequest
import dev.alpine.runtime.api.RuntimeTerminalResizeSupport
import dev.alpine.runtime.api.RuntimeTerminalSession
import dev.alpine.runtime.api.RuntimeTerminalSignal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList

class FakeAlpineRuntimeManager @JvmOverloads constructor(
    private val dispatcher: RuntimeTestDispatcher = RuntimeTestDispatcher(),
    private val runtimeVersion: String = "test-runtime",
    private val clock: () -> Long = System::currentTimeMillis,
) : AlpineRuntimeManager {
    private val stateListeners = CopyOnWriteArrayList<RuntimeStateListener>()
    private val eventListeners = CopyOnWriteArrayList<RuntimeEventListener>()
    private var state = RuntimeState(RuntimeLifecycleState.NOT_INSTALLED)
    private var activeSession: FakeRuntimeSession? = null

    var nextCommandResult: RuntimeCommandResult = RuntimeCommandResult(0)
    var nextFailure: RuntimeErrorCode? = null

    fun testDispatcher(): RuntimeTestDispatcher = dispatcher

    override fun currentState(): RuntimeState = state

    override fun addStateListener(listener: RuntimeStateListener): RuntimeSubscription {
        stateListeners += listener
        listener.onStateChanged(state)
        return RuntimeSubscription { stateListeners -= listener }
    }

    override fun addEventListener(listener: RuntimeEventListener): RuntimeSubscription {
        eventListeners += listener
        return RuntimeSubscription { eventListeners -= listener }
    }

    override fun install(request: RuntimeInstallRequest): CompletionStage<RuntimeInstallResult> {
        updateState(RuntimeState(RuntimeLifecycleState.INSTALLING, 0))
        return scheduled {
            failIfRequested()
            updateState(RuntimeState(RuntimeLifecycleState.READY, 100, runtimeVersion))
            RuntimeInstallResult(runtimeVersion, listOf("virtual-rootfs", "virtual-proot"), false)
        }
    }

    override fun start(request: RuntimeStartRequest): CompletionStage<RuntimeSession> {
        updateState(RuntimeState(RuntimeLifecycleState.STARTING, activeVersion = runtimeVersion))
        return scheduled {
            failIfRequested()
            check(state.lifecycle == RuntimeLifecycleState.STARTING) { "runtime is not ready to start" }
            val session = FakeRuntimeSession(UUID.randomUUID().toString(), clock(), dispatcher) { nextCommandResult }
            activeSession = session
            updateState(RuntimeState(RuntimeLifecycleState.RUNNING, activeVersion = runtimeVersion))
            emit(RuntimeEventKind.SESSION_STARTED, session.id)
            session
        }
    }

    override fun stop(reason: RuntimeStopReason): CompletionStage<Void> {
        updateState(RuntimeState(RuntimeLifecycleState.STOPPING, activeVersion = runtimeVersion))
        return scheduledVoid(dispatcher) {
            activeSession = null
            updateState(RuntimeState(RuntimeLifecycleState.READY, activeVersion = runtimeVersion))
            emit(RuntimeEventKind.SESSION_STOPPED)
        }
    }

    override fun repair(): CompletionStage<RuntimeInstallResult> = install(RuntimeInstallRequest(forceReinstall = true))

    override fun reset(): CompletionStage<Void> = scheduledVoid(dispatcher) {
        activeSession = null
        updateState(RuntimeState(RuntimeLifecycleState.NOT_INSTALLED))
    }

    override fun health(): CompletionStage<RuntimeHealth> = scheduled {
        val healthy = state.lifecycle == RuntimeLifecycleState.READY || state.lifecycle == RuntimeLifecycleState.RUNNING
        RuntimeHealth(healthy, state.lifecycle, clock(), checks = mapOf("fake" to healthy))
    }

    private fun failIfRequested() {
        val failure = nextFailure ?: return
        nextFailure = null
        updateState(RuntimeState(RuntimeLifecycleState.FAILED, detailCode = failure))
        throw FakeRuntimeException(failure)
    }

    private fun updateState(newState: RuntimeState) {
        state = newState
        stateListeners.forEach { it.onStateChanged(newState) }
        emit(RuntimeEventKind.STATE_CHANGED, errorCode = newState.detailCode)
    }

    private fun emit(kind: RuntimeEventKind, sessionId: String? = null, errorCode: RuntimeErrorCode? = null) {
        val event = RuntimeEvent(kind, clock(), sessionId, errorCode)
        eventListeners.forEach { it.onEvent(event) }
    }

    private fun <T> scheduled(block: () -> T): CompletionStage<T> {
        val future = CompletableFuture<T>()
        dispatcher.dispatch {
            runCatching(block).fold(future::complete, future::completeExceptionally)
        }
        return future
    }
}

class FakeRuntimeException(val errorCode: RuntimeErrorCode) : RuntimeException(errorCode.name)

private class FakeRuntimeSession(
    override val id: String,
    override val startedAtEpochMillis: Long,
    private val dispatcher: RuntimeTestDispatcher,
    private val commandResult: () -> RuntimeCommandResult,
) : RuntimeSession {
    override fun execute(request: RuntimeCommandRequest): CompletionStage<RuntimeCommandResult> =
        scheduled(commandResult)

    override fun openTerminal(request: RuntimeTerminalRequest): CompletionStage<RuntimeTerminalSession> =
        scheduled { FakeRuntimeTerminalSession(UUID.randomUUID().toString(), dispatcher) }

    override fun listProcesses(): CompletionStage<List<RuntimeProcessInfo>> =
        scheduled { emptyList() }

    override fun health(): CompletionStage<RuntimeHealth> =
        scheduled { RuntimeHealth(true, RuntimeLifecycleState.RUNNING, System.currentTimeMillis()) }

    override fun stop(reason: RuntimeStopReason): CompletionStage<Void> = scheduledVoid(dispatcher) { }

    private fun <T> scheduled(block: () -> T): CompletionStage<T> {
        val future = CompletableFuture<T>()
        dispatcher.dispatch { runCatching(block).fold(future::complete, future::completeExceptionally) }
        return future
    }
}

private class FakeRuntimeTerminalSession(
    override val id: String,
    private val dispatcher: RuntimeTestDispatcher,
) : RuntimeTerminalSession {
    private val listeners = CopyOnWriteArrayList<RuntimeTerminalOutputListener>()
    override var isOpen: Boolean = true
        private set
    override val resizeSupport: RuntimeTerminalResizeSupport = RuntimeTerminalResizeSupport.DYNAMIC

    override fun addOutputListener(listener: RuntimeTerminalOutputListener): RuntimeSubscription {
        listeners += listener
        return RuntimeSubscription { listeners -= listener }
    }

    override fun write(bytes: ByteArray): CompletionStage<Void> = scheduledVoid(dispatcher) {
        listeners.forEach { it.onOutput(bytes.copyOf()) }
    }

    override fun resize(columns: Int, rows: Int): CompletionStage<Void> = scheduledVoid(dispatcher) {
        require(columns > 0 && rows > 0)
    }

    override fun signal(signal: RuntimeTerminalSignal): CompletionStage<Void> = scheduledVoid(dispatcher) { }

    override fun closeAsync(): CompletionStage<Void> = scheduledVoid(dispatcher) {
        isOpen = false
    }

    private fun <T> scheduled(block: () -> T): CompletionStage<T> {
        val future = CompletableFuture<T>()
        dispatcher.dispatch { runCatching(block).fold(future::complete, future::completeExceptionally) }
        return future
    }
}

private fun scheduledVoid(
    dispatcher: RuntimeTestDispatcher,
    block: () -> Unit,
): CompletionStage<Void> {
    val future = CompletableFuture<Void>()
    dispatcher.dispatch {
        runCatching(block).fold(
            onSuccess = { future.complete(null) },
            onFailure = future::completeExceptionally,
        )
    }
    return future
}
