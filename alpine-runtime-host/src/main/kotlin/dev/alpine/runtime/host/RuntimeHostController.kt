package dev.alpine.runtime.host

import dev.alpine.runtime.api.AlpineRuntimeManager
import dev.alpine.runtime.api.RuntimeCommandRequest
import dev.alpine.runtime.api.RuntimeCommandResult
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeEventKind
import dev.alpine.runtime.api.RuntimeHealth
import dev.alpine.runtime.api.RuntimeInstallRequest
import dev.alpine.runtime.api.RuntimeInstallResult
import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.api.RuntimeOperationException
import dev.alpine.runtime.api.RuntimePackageApproval
import dev.alpine.runtime.api.RuntimePackageInstallOutcome
import dev.alpine.runtime.api.RuntimePackageInstallRequest
import dev.alpine.runtime.api.RuntimePackageInstallResult
import dev.alpine.runtime.api.RuntimePackageInstaller
import dev.alpine.runtime.api.RuntimePackagePolicy
import dev.alpine.runtime.api.RuntimeSession
import dev.alpine.runtime.api.RuntimeStartRequest
import dev.alpine.runtime.api.RuntimeState
import dev.alpine.runtime.api.RuntimeStopReason
import dev.alpine.runtime.api.RuntimeSubscription
import dev.alpine.runtime.api.RuntimeTerminalRequest
import dev.alpine.runtime.api.RuntimeTerminalResizeSupport
import dev.alpine.runtime.api.RuntimeTerminalSession
import dev.alpine.runtime.api.RuntimeTerminalSignal
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList

enum class RuntimeHostOperation {
    IDLE,
    INSTALLING,
    STARTING,
    STOPPING,
    CHECKING_HEALTH,
    REPAIRING,
    RESETTING,
    EXECUTING,
    OPENING_TERMINAL,
    INSTALLING_PACKAGES,
}

data class RuntimeHostState @JvmOverloads constructor(
    val runtimeState: RuntimeState,
    val operation: RuntimeHostOperation = RuntimeHostOperation.IDLE,
    val health: RuntimeHealth? = null,
    val sessionActive: Boolean = false,
    val terminalActive: Boolean = false,
    val terminalResizeSupport: RuntimeTerminalResizeSupport = RuntimeTerminalResizeSupport.INITIAL_SIZE_ONLY,
    val terminalText: String = "",
    val terminalOutputTruncated: Boolean = false,
    val commandOutput: String = "",
    val packageOutcome: RuntimePackageInstallOutcome? = null,
    val lastErrorCode: RuntimeErrorCode? = null,
)

fun interface RuntimeHostStateListener {
    fun onStateChanged(state: RuntimeHostState)
}

/**
 * UI-neutral lifecycle owner used by both SDK Compose UI and fully custom host UI.
 * It stores no Android Context and exposes only closed runtime error codes.
 */
class RuntimeHostController @JvmOverloads constructor(
    private val manager: AlpineRuntimeManager,
    private val maxTerminalBufferBytes: Int = 256 * 1024,
    private val maxCommandOutputBytes: Int = 64 * 1024,
) : AutoCloseable {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<RuntimeHostStateListener>()
    private val terminalBuffer = BoundedByteBuffer(maxTerminalBufferBytes)
    @Volatile private var state = RuntimeHostState(manager.currentState())
    @Volatile private var session: RuntimeSession? = null
    @Volatile private var terminal: RuntimeTerminalSession? = null
    private var terminalOutputSubscription: RuntimeSubscription? = null

    init {
        require(maxTerminalBufferBytes > 0) { "maxTerminalBufferBytes must be positive" }
        require(maxCommandOutputBytes > 0) { "maxCommandOutputBytes must be positive" }
    }

    private val runtimeStateSubscription = manager.addStateListener { runtimeState ->
        if (runtimeState.lifecycle !in ACTIVE_SESSION_STATES) clearSessionReferences()
        update { current ->
            current.copy(
                runtimeState = runtimeState,
                health = current.health?.takeIf { it.lifecycle == runtimeState.lifecycle },
                sessionActive = session != null,
                terminalActive = terminal?.isOpen == true,
                lastErrorCode = runtimeState.detailCode ?: current.lastErrorCode,
            )
        }
    }
    private val runtimeEventSubscription = manager.addEventListener { event ->
        if (event.kind == RuntimeEventKind.TERMINAL_CLOSED) {
            clearTerminalReference()
            update { it.copy(terminalActive = false) }
        }
    }

    fun currentState(): RuntimeHostState = state

    fun addStateListener(listener: RuntimeHostStateListener): RuntimeSubscription {
        listeners += listener
        runCatching { listener.onStateChanged(state) }
        return RuntimeSubscription { listeners -= listener }
    }

    fun install(request: RuntimeInstallRequest = RuntimeInstallRequest()): CompletionStage<RuntimeInstallResult> =
        track(RuntimeHostOperation.INSTALLING, { manager.install(request) })

    fun start(request: RuntimeStartRequest = RuntimeStartRequest()): CompletionStage<RuntimeSession> {
        session?.let { return CompletableFuture.completedFuture(it) }
        return track(RuntimeHostOperation.STARTING, { manager.start(request) }) { started ->
            started ?: return@track
            session = started
            update { it.copy(sessionActive = true) }
        }
    }

    fun stop(reason: RuntimeStopReason = RuntimeStopReason.USER_REQUEST): CompletionStage<Void> =
        track(RuntimeHostOperation.STOPPING, { manager.stop(reason) }) {
            clearSessionReferences()
            update { it.copy(sessionActive = false, terminalActive = false) }
        }

    fun repair(): CompletionStage<RuntimeInstallResult> =
        track(RuntimeHostOperation.REPAIRING, manager::repair)

    fun reset(): CompletionStage<Void> = track(RuntimeHostOperation.RESETTING, manager::reset) {
        clearSessionReferences()
        terminalBuffer.clear()
        update {
            it.copy(
                sessionActive = false,
                terminalActive = false,
                terminalText = "",
                terminalOutputTruncated = false,
            )
        }
    }

    fun refreshHealth(): CompletionStage<RuntimeHealth> =
        track(RuntimeHostOperation.CHECKING_HEALTH, manager::health) { health ->
            health ?: return@track
            update { it.copy(health = health) }
        }

    fun execute(request: RuntimeCommandRequest): CompletionStage<RuntimeCommandResult> {
        val active = session ?: return failed(RuntimeErrorCode.PROCESS_EXITED)
        return track(RuntimeHostOperation.EXECUTING, { active.execute(request) }) { result ->
            result ?: return@track
            val bytes = if (result.standardOutput.isNotEmpty()) result.standardOutput else result.standardError
            update { it.copy(commandOutput = boundedText(bytes, maxCommandOutputBytes)) }
        }
    }

    fun openTerminal(request: RuntimeTerminalRequest = RuntimeTerminalRequest()): CompletionStage<RuntimeTerminalSession> {
        terminal?.takeIf { it.isOpen }?.let { return CompletableFuture.completedFuture(it) }
        val active = session ?: return failed(RuntimeErrorCode.PROCESS_EXITED)
        terminalBuffer.clear()
        return track(RuntimeHostOperation.OPENING_TERMINAL, { active.openTerminal(request) }) { opened ->
            opened ?: return@track
            terminal = opened
            terminalOutputSubscription?.close()
            terminalOutputSubscription = opened.addOutputListener { bytes ->
                terminalBuffer.append(bytes)
                update {
                    it.copy(
                        terminalActive = opened.isOpen,
                        terminalText = terminalBuffer.text(),
                        terminalOutputTruncated = terminalBuffer.truncated,
                    )
                }
            }
            update {
                it.copy(
                    terminalActive = true,
                    terminalResizeSupport = opened.resizeSupport,
                    terminalText = terminalBuffer.text(),
                    terminalOutputTruncated = false,
                )
            }
        }
    }

    @JvmOverloads
    fun sendTerminalInput(text: String, appendNewline: Boolean = true): CompletionStage<Void> {
        val active = terminal?.takeIf { it.isOpen } ?: return failed(RuntimeErrorCode.TERMINAL_UNAVAILABLE)
        val payload = if (appendNewline) "$text\n" else text
        return active.write(payload.toByteArray(StandardCharsets.UTF_8))
    }

    fun resizeTerminal(columns: Int, rows: Int): CompletionStage<Void> =
        terminal?.takeIf { it.isOpen }?.resize(columns, rows)
            ?: failed(RuntimeErrorCode.TERMINAL_UNAVAILABLE)

    fun signalTerminal(signal: RuntimeTerminalSignal): CompletionStage<Void> =
        terminal?.takeIf { it.isOpen }?.signal(signal)
            ?: failed(RuntimeErrorCode.TERMINAL_UNAVAILABLE)

    fun closeTerminal(): CompletionStage<Void> {
        val active = terminal ?: return CompletableFuture.completedFuture(null)
        return active.closeAsync().whenComplete { _, _ ->
            clearTerminalReference()
            update { it.copy(terminalActive = false) }
        }
    }

    fun installPackages(
        request: RuntimePackageInstallRequest,
        policy: RuntimePackagePolicy,
        approval: RuntimePackageApproval,
    ): CompletionStage<RuntimePackageInstallResult> {
        val active = session ?: return failed(RuntimeErrorCode.PROCESS_EXITED)
        return track(
            RuntimeHostOperation.INSTALLING_PACKAGES,
            { RuntimePackageInstaller(policy).install(active, request, approval) },
        ) { result ->
            result ?: return@track
            update { it.copy(packageOutcome = result.outcome) }
        }
    }

    override fun close() {
        runtimeStateSubscription.close()
        runtimeEventSubscription.close()
        listeners.clear()
        runCatching { terminalOutputSubscription?.close() }
        // Runtime ownership remains with the host Application/Service. Closing a screen controller
        // must never silently stop a long-running session during rotation.
    }

    private fun clearSessionReferences() {
        session = null
        clearTerminalReference()
    }

    private fun clearTerminalReference() {
        terminal = null
        terminalOutputSubscription?.close()
        terminalOutputSubscription = null
    }

    private fun <T> track(
        operation: RuntimeHostOperation,
        source: () -> CompletionStage<T>,
        onSuccess: (T?) -> Unit = {},
    ): CompletionStage<T> {
        update { it.copy(operation = operation, lastErrorCode = null) }
        val stage = runCatching(source).getOrElse { error ->
            return failed<T>(stableError(error)).also {
                update { current -> current.copy(operation = RuntimeHostOperation.IDLE, lastErrorCode = stableError(error)) }
            }
        }
        stage.whenComplete { value, error ->
            if (error == null) {
                runCatching { onSuccess(value) }
                update { it.copy(operation = RuntimeHostOperation.IDLE, lastErrorCode = null) }
            } else {
                update {
                    it.copy(
                        operation = RuntimeHostOperation.IDLE,
                        lastErrorCode = stableError(error),
                    )
                }
            }
        }
        return stage
    }

    private fun update(transform: (RuntimeHostState) -> RuntimeHostState) {
        val updated = synchronized(lock) {
            transform(state).also { state = it }
        }
        listeners.forEach { listener -> runCatching { listener.onStateChanged(updated) } }
    }

    private fun stableError(error: Throwable): RuntimeErrorCode {
        var current: Throwable? = error
        repeat(12) {
            when (current) {
                is RuntimeOperationException -> return current.errorCode
                is CompletionException -> current = current.cause
                else -> current = current?.cause
            }
            if (current == null) return RuntimeErrorCode.INTERNAL_ERROR
        }
        return RuntimeErrorCode.INTERNAL_ERROR
    }

    private fun boundedText(bytes: ByteArray, limit: Int): String {
        val bounded = if (bytes.size <= limit) bytes else bytes.copyOfRange(bytes.size - limit, bytes.size)
        return bounded.toString(StandardCharsets.UTF_8)
    }

    private fun <T> failed(code: RuntimeErrorCode): CompletionStage<T> =
        CompletableFuture<T>().also { it.completeExceptionally(RuntimeOperationException(code)) }

    private class BoundedByteBuffer(private val limit: Int) {
        private var bytes = ByteArray(0)
        var truncated: Boolean = false
            private set

        @Synchronized
        fun append(incoming: ByteArray) {
            if (incoming.isEmpty()) return
            if (incoming.size >= limit) {
                bytes = incoming.copyOfRange(incoming.size - limit, incoming.size)
                truncated = true
                return
            }
            val keep = minOf(bytes.size, limit - incoming.size)
            if (keep < bytes.size) truncated = true
            bytes = ByteArray(keep + incoming.size).also { combined ->
                bytes.copyInto(combined, 0, bytes.size - keep, bytes.size)
                incoming.copyInto(combined, keep)
            }
        }

        @Synchronized
        fun text(): String = sanitizeTerminalText(bytes.toString(StandardCharsets.UTF_8))

        @Synchronized
        fun clear() {
            bytes = ByteArray(0)
            truncated = false
        }
    }

    companion object {
        private val ANSI_OSC = Regex("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)")
        private val ANSI_CSI = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
        private val NON_TEXT_CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
        private val ACTIVE_SESSION_STATES = setOf(
            RuntimeLifecycleState.STARTING,
            RuntimeLifecycleState.RUNNING,
            RuntimeLifecycleState.STOPPING,
        )

        private fun sanitizeTerminalText(value: String): String = value
            .replace(ANSI_OSC, "")
            .replace(ANSI_CSI, "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(NON_TEXT_CONTROL, "")
    }
}
