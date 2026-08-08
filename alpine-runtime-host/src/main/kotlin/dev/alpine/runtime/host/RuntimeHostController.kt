package dev.alpine.runtime.host

import dev.alpine.runtime.api.AlpineRuntimeManager
import dev.alpine.runtime.api.RuntimeCommandRequest
import dev.alpine.runtime.api.RuntimeCommandResult
import dev.alpine.runtime.api.RuntimeDeveloperToolProfile
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
import dev.alpine.runtime.api.RuntimePackageMutationAllowlistPolicy
import dev.alpine.runtime.api.RuntimePackageMutationOutcome
import dev.alpine.runtime.api.RuntimePackageMutationRequest
import dev.alpine.runtime.api.RuntimePackageMutationResult
import dev.alpine.runtime.api.RuntimePackageMutator
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
    MUTATING_PACKAGES,
    CHECKING_TOOL,
}

/** The host keeps only a closed result, never tool stdout/stderr, for the package UI. */
enum class RuntimeToolSmokeOutcome {
    COMPLETED,
    FAILED,
}

/** A bounded, host-owned terminal tab. Output never leaves the Runtime Host boundary. */
data class RuntimeTerminalSummary(
    val id: String,
    val title: String,
    val open: Boolean,
    val resizeSupport: RuntimeTerminalResizeSupport,
)

/** A single closed-tab summary. It deliberately excludes guest output, commands, and process IDs. */
data class RuntimeTerminalExit(
    val terminalId: String,
    val title: String,
    val exitCode: Int?,
)

data class RuntimeHostState @JvmOverloads constructor(
    val runtimeState: RuntimeState,
    val operation: RuntimeHostOperation = RuntimeHostOperation.IDLE,
    val health: RuntimeHealth? = null,
    val sessionActive: Boolean = false,
    val terminalActive: Boolean = false,
    val terminalResizeSupport: RuntimeTerminalResizeSupport = RuntimeTerminalResizeSupport.INITIAL_SIZE_ONLY,
    val terminalText: String = "",
    val terminalScreen: RuntimeTerminalScreen? = null,
    val terminalOutputTruncated: Boolean = false,
    val terminalSessions: List<RuntimeTerminalSummary> = emptyList(),
    val selectedTerminalId: String? = null,
    val lastTerminalExit: RuntimeTerminalExit? = null,
    val commandOutput: String = "",
    val packageOutcome: RuntimePackageInstallOutcome? = null,
    val packageMutationAction: dev.alpine.runtime.api.RuntimePackageAction? = null,
    val packageMutationOutcome: RuntimePackageMutationOutcome? = null,
    val toolSmokeProfileId: String? = null,
    val toolSmokeOutcome: RuntimeToolSmokeOutcome? = null,
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
    @Volatile private var state = RuntimeHostState(manager.currentState())
    @Volatile private var session: RuntimeSession? = null
    private val terminals = LinkedHashMap<String, ManagedTerminal>()
    private var selectedTerminalId: String? = null
    private var nextTerminalNumber = 1

    private data class ManagedTerminal(
        val session: RuntimeTerminalSession,
        val buffer: BoundedByteBuffer,
        var title: String,
        val columns: Int,
        val rows: Int,
        var outputSubscription: RuntimeSubscription? = null,
    )

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
                terminalActive = selectedTerminal()?.session?.isOpen == true,
                lastTerminalExit = if (runtimeState.lifecycle in ACTIVE_SESSION_STATES) {
                    current.lastTerminalExit
                } else {
                    null
                },
                lastErrorCode = runtimeState.detailCode ?: current.lastErrorCode,
            ).withTerminalProjection()
        }
    }
    private val runtimeEventSubscription = manager.addEventListener { event ->
        if (event.kind == RuntimeEventKind.TERMINAL_CLOSED) {
            removeClosedTerminals(
                terminalId = event.attributes[TERMINAL_EVENT_ID],
                exitCode = event.attributes[TERMINAL_EXIT_CODE]
                    ?.toIntOrNull()
                    ?.takeIf { it in 0..MAX_PROCESS_EXIT_CODE },
            )
        }
    }

    fun currentState(): RuntimeHostState = state

    fun addStateListener(listener: RuntimeHostStateListener): RuntimeSubscription {
        listeners += listener
        runCatching { listener.onStateChanged(state) }
        return RuntimeSubscription { listeners -= listener }
    }

    /**
     * Makes a Runtime session owned by another lifecycle controller available to terminal/package
     * UI. Closing the returned binding only detaches host references; it never stops the session.
     */
    fun bindExternalSession(externalSession: RuntimeSession): RuntimeSubscription {
        require(manager.currentState().lifecycle in ACTIVE_SESSION_STATES) {
            "external session requires an active runtime"
        }
        synchronized(lock) {
            require(session == null || session === externalSession) {
                "a different runtime session is already bound"
            }
            session = externalSession
        }
        update { it.copy(sessionActive = true) }
        return RuntimeSubscription {
            val detached = synchronized(lock) {
                if (session === externalSession) {
                    clearSessionReferences()
                    true
                } else {
                    false
                }
            }
            if (detached) update {
                it.copy(
                    sessionActive = false,
                    terminalActive = false,
                    lastTerminalExit = null,
                ).withTerminalProjection()
            }
        }
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
            update {
                it.copy(
                    sessionActive = false,
                    terminalActive = false,
                    lastTerminalExit = null,
                ).withTerminalProjection()
            }
        }

    fun repair(): CompletionStage<RuntimeInstallResult> =
        track(RuntimeHostOperation.REPAIRING, manager::repair)

    fun reset(): CompletionStage<Void> = track(RuntimeHostOperation.RESETTING, manager::reset) {
        clearSessionReferences()
        update {
            it.copy(
                sessionActive = false,
                terminalActive = false,
                terminalText = "",
                terminalScreen = null,
                terminalOutputTruncated = false,
                terminalSessions = emptyList(),
                selectedTerminalId = null,
                lastTerminalExit = null,
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

    /** Opens the selected tab when it is alive, preserving the legacy one-terminal call contract. */
    fun openTerminal(request: RuntimeTerminalRequest = RuntimeTerminalRequest()): CompletionStage<RuntimeTerminalSession> {
        selectedTerminal()?.session?.takeIf { it.isOpen }?.let { return CompletableFuture.completedFuture(it) }
        terminals.values.firstOrNull { it.session.isOpen }?.let { existing ->
            selectedTerminalId = existing.session.id
            update { it.withTerminalProjection() }
            return CompletableFuture.completedFuture(existing.session)
        }
        return openAdditionalTerminal(request)
    }

    /** Creates an independently controllable terminal tab in the same Runtime session. */
    fun openAdditionalTerminal(
        request: RuntimeTerminalRequest = RuntimeTerminalRequest(),
    ): CompletionStage<RuntimeTerminalSession> {
        val active = session ?: return failed(RuntimeErrorCode.PROCESS_EXITED)
        if (synchronized(lock) { terminals.values.count { it.session.isOpen } >= MAX_OPEN_TERMINALS }) {
            return failed(RuntimeErrorCode.TERMINAL_UNAVAILABLE)
        }
        return track(RuntimeHostOperation.OPENING_TERMINAL, { active.openTerminal(request) }) { opened ->
            opened ?: return@track
            val managed = ManagedTerminal(
                session = opened,
                buffer = BoundedByteBuffer(maxTerminalBufferBytes),
                title = "Terminal ${nextTerminalNumber++}",
                columns = request.columns,
                rows = request.rows,
            )
            synchronized(lock) {
                terminals[opened.id] = managed
                selectedTerminalId = opened.id
            }
            managed.outputSubscription = opened.addOutputListener { bytes ->
                managed.buffer.append(bytes)
                update {
                    it.withTerminalProjection()
                }
            }
            update {
                it.copy(lastTerminalExit = null).withTerminalProjection()
            }
        }
    }

    fun selectTerminal(id: String): Boolean = synchronized(lock) {
        val terminal = terminals[id]?.takeIf { it.session.isOpen } ?: return false
        selectedTerminalId = terminal.session.id
        update { it.withTerminalProjection() }
        true
    }

    fun renameTerminal(id: String, title: String): Boolean {
        val safeTitle = title.trim().replace(Regex("\\s+"), " ").take(MAX_TERMINAL_TITLE_LENGTH)
        if (safeTitle.isEmpty()) return false
        return synchronized(lock) {
            val terminal = terminals[id] ?: return false
            terminal.title = safeTitle
            update { it.withTerminalProjection() }
            true
        }
    }

    @JvmOverloads
    fun sendTerminalInput(text: String, appendNewline: Boolean = true): CompletionStage<Void> {
        val active = selectedTerminal()?.session?.takeIf { it.isOpen }
            ?: return failed(RuntimeErrorCode.TERMINAL_UNAVAILABLE)
        val payload = if (appendNewline) "$text\n" else text
        return active.write(payload.toByteArray(StandardCharsets.UTF_8))
    }

    fun resizeTerminal(columns: Int, rows: Int): CompletionStage<Void> =
        selectedTerminal()?.session?.takeIf { it.isOpen }?.resize(columns, rows)
            ?: failed(RuntimeErrorCode.TERMINAL_UNAVAILABLE)

    fun signalTerminal(signal: RuntimeTerminalSignal): CompletionStage<Void> =
        selectedTerminal()?.session?.takeIf { it.isOpen }?.signal(signal)
            ?: failed(RuntimeErrorCode.TERMINAL_UNAVAILABLE)

    @JvmOverloads
    fun closeTerminal(id: String? = selectedTerminalId): CompletionStage<Void> {
        val active = synchronized(lock) { id?.let { terminals[it] } }
            ?: return CompletableFuture.completedFuture(null)
        return active.session.closeAsync().whenComplete { _, _ ->
            val exited = synchronized(lock) { removeTerminalLocked(active.session.id) }
            update {
                it.copy(lastTerminalExit = exited ?: it.lastTerminalExit).withTerminalProjection()
            }
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

    fun mutatePackages(
        request: RuntimePackageMutationRequest,
        policy: RuntimePackageMutationAllowlistPolicy,
        approval: RuntimePackageApproval,
    ): CompletionStage<RuntimePackageMutationResult> {
        val active = session ?: return failed(RuntimeErrorCode.PROCESS_EXITED)
        return track(
            RuntimeHostOperation.MUTATING_PACKAGES,
            { RuntimePackageMutator(policy).mutate(active, request, approval) },
        ) { result ->
            result ?: return@track
            update {
                it.copy(
                    packageMutationAction = result.action,
                    packageMutationOutcome = result.outcome,
                )
            }
        }
    }

    /**
     * Runs an inspectable, fixed first-run command for a developer-tool profile. stdout/stderr is
     * deliberately not projected into UI state: even a replaced guest binary must not turn this
     * status affordance into an arbitrary text/secret disclosure channel.
     */
    fun runToolSmoke(profile: RuntimeDeveloperToolProfile): CompletionStage<RuntimeCommandResult> {
        val active = session ?: return failed(RuntimeErrorCode.PROCESS_EXITED)
        val stage = track(
            RuntimeHostOperation.CHECKING_TOOL,
            { active.execute(profile.smokeRequest) },
        ) { result ->
            val succeeded = result != null && result.exitCode == 0 && !result.timedOut
            update {
                it.copy(
                    toolSmokeProfileId = profile.id,
                    toolSmokeOutcome = if (succeeded) {
                        RuntimeToolSmokeOutcome.COMPLETED
                    } else {
                        RuntimeToolSmokeOutcome.FAILED
                    },
                )
            }
        }
        stage.whenComplete { _, error ->
            if (error != null) {
                update {
                    it.copy(
                        toolSmokeProfileId = profile.id,
                        toolSmokeOutcome = RuntimeToolSmokeOutcome.FAILED,
                    )
                }
            }
        }
        return stage
    }

    override fun close() {
        runtimeStateSubscription.close()
        runtimeEventSubscription.close()
        listeners.clear()
        synchronized(lock) { clearTerminalReferenceLocked() }
        // Runtime ownership remains with the host Application/Service. Closing a screen controller
        // must never silently stop a long-running session during rotation.
    }

    private fun clearSessionReferences() {
        synchronized(lock) {
            session = null
            clearTerminalReferenceLocked()
        }
    }

    private fun clearTerminalReference() {
        synchronized(lock) { clearTerminalReferenceLocked() }
    }

    private fun clearTerminalReferenceLocked() {
        terminals.values.forEach { terminal -> runCatching { terminal.outputSubscription?.close() } }
        terminals.clear()
        selectedTerminalId = null
    }

    private fun selectedTerminal(): ManagedTerminal? = synchronized(lock) {
        selectedTerminalId?.let(terminals::get)?.takeIf { it.session.isOpen }
    }

    private fun removeClosedTerminals(terminalId: String?, exitCode: Int?) {
        val exited = synchronized(lock) {
            if (terminalId != null) {
                removeTerminalLocked(terminalId, exitCode)
            } else {
                terminals.values.filterNot { it.session.isOpen }
                    .map { it.session.id }
                    .mapNotNull(::removeTerminalLocked)
                    .lastOrNull()
            }
        }
        update { it.copy(lastTerminalExit = exited ?: it.lastTerminalExit).withTerminalProjection() }
    }

    private fun removeTerminalLocked(id: String, exitCode: Int? = null): RuntimeTerminalExit? {
        val removed = terminals.remove(id) ?: return null
        runCatching { removed.outputSubscription?.close() }
        if (selectedTerminalId == id) {
            selectedTerminalId = terminals.values.lastOrNull { it.session.isOpen }?.session?.id
        }
        return RuntimeTerminalExit(
            terminalId = removed.session.id,
            title = removed.title,
            exitCode = exitCode,
        )
    }

    private fun RuntimeHostState.withTerminalProjection(): RuntimeHostState {
        val summaries = terminals.values.map { terminal ->
            RuntimeTerminalSummary(
                id = terminal.session.id,
                title = terminal.title,
                open = terminal.session.isOpen,
                resizeSupport = terminal.session.resizeSupport,
            )
        }
        val selectedId = this@RuntimeHostController.selectedTerminalId
        val selected = selectedId?.let { id -> summaries.firstOrNull { it.id == id } }
        val selectedTerminal = selectedId?.let(terminals::get)?.takeIf { it.session.isOpen }
        return copy(
            terminalActive = selected?.open == true,
            terminalResizeSupport = selected?.resizeSupport
                ?: RuntimeTerminalResizeSupport.INITIAL_SIZE_ONLY,
            terminalText = selectedTerminal?.buffer?.text().orEmpty(),
            terminalScreen = selectedTerminal?.let { terminal ->
                terminal.buffer.screen(terminal.columns, terminal.rows)
            },
            terminalOutputTruncated = selectedTerminal?.buffer?.truncated == true,
            terminalSessions = summaries,
            selectedTerminalId = selected?.id,
        )
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
        fun screen(columns: Int, rows: Int): RuntimeTerminalScreen? =
            bytes.takeIf { it.isNotEmpty() }?.toString(StandardCharsets.UTF_8)?.let { raw ->
                RuntimeAnsiTerminalScreenRenderer.render(raw, columns, rows)
            }

        @Synchronized
        fun clear() {
            bytes = ByteArray(0)
            truncated = false
        }
    }

    companion object {
        private const val MAX_OPEN_TERMINALS = 8
        private const val MAX_TERMINAL_TITLE_LENGTH = 48
        private const val MAX_PROCESS_EXIT_CODE = 255
        private const val TERMINAL_EVENT_ID = "terminal_id"
        private const val TERMINAL_EXIT_CODE = "exit_code"
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
