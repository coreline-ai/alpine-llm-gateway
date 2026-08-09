package dev.alpine.integrated

import android.content.Context
import dev.alpine.chat.backend.alpine.AlpineGatewayChatBackend
import dev.alpine.chat.backend.direct.AndroidDirectChatBackend
import dev.alpine.chat.provider.android.session.ChatCompletionSession
import dev.alpine.chat.routing.ChatBackend
import dev.alpine.chat.routing.ChatBackendCapabilities
import dev.alpine.chat.routing.ChatBackendPreparation
import dev.alpine.chat.routing.ChatBackendRequest
import dev.alpine.chat.routing.ChatBackendResult
import dev.alpine.chat.routing.ChatFallbackAuthorizer
import dev.alpine.chat.routing.ChatStreamEmitter
import dev.alpine.chat.routing.SafeChatRouter
import dev.alpine.llm.HostBridgeServer
import dev.alpine.llm.HostLlmResult
import dev.alpine.runtime.api.AlpineRuntimeManager
import dev.alpine.runtime.api.RuntimeSubscription
import dev.alpine.runtime.bridge.AlpineLlmBridgeConfiguration
import dev.alpine.runtime.bridge.AlpineLlmBridgeController
import dev.alpine.runtime.bridge.AlpineLlmBridgeHealth
import dev.alpine.runtime.bridge.AlpineLlmBridgeRecoveryConfiguration
import dev.alpine.runtime.bridge.AlpineLlmBridgeRecoveryLease
import dev.alpine.runtime.bridge.AlpineLlmBridgeRecoveryListener
import dev.alpine.runtime.bridge.AlpineLlmBridgeRecoveryMode
import dev.alpine.runtime.bridge.AlpineLlmBridgeRecoveryState
import dev.alpine.runtime.bridge.AlpineLlmBridgeRecoverySupervisor
import dev.alpine.runtime.bridge.AlpineLlmModel
import dev.alpine.runtime.bridge.LlmBridgeEndpointRegistry
import dev.alpine.runtime.bridge.LlmBridgeErrorCode
import dev.alpine.runtime.bridge.LlmBridgeEvent
import dev.alpine.runtime.bridge.LlmBridgeEventType
import dev.alpine.runtime.bridge.LlmBridgeLifecycleState
import dev.alpine.runtime.bridge.LlmBridgeOperationException
import dev.alpine.runtime.gateway.pack.bundled.BundledPythonGatewayArtifactProvider
import dev.alpine.runtime.host.RuntimeHostController
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class AlpineWorkspaceLlmOperation {
    IDLE,
    STARTING,
    CHECKING_HEALTH,
    RESTARTING,
    RECOVERING,
    STOPPING,
}

data class AlpineWorkspaceLlmState(
    val lifecycle: LlmBridgeLifecycleState = LlmBridgeLifecycleState.STOPPED,
    val operation: AlpineWorkspaceLlmOperation = AlpineWorkspaceLlmOperation.IDLE,
    val healthy: Boolean? = null,
    val checks: Map<String, Boolean> = emptyMap(),
    val errorCode: LlmBridgeErrorCode? = null,
    val profileLabel: String? = null,
    val model: String? = null,
    val capabilityExpiresAtEpochMillis: Long? = null,
)

fun interface AlpineWorkspaceLlmStateListener {
    fun onStateChanged(state: AlpineWorkspaceLlmState)
}

/**
 * Application-owned composition boundary for Runtime + HostBridge + Python Gateway.
 *
 * Provider credentials remain inside [ChatCompletionSession]. The guest receives only the
 * capability file created by [AlpineLlmBridgeController].
 */
class IntegratedAlpineLlmHost(
    context: Context,
    private val runtimeManager: AlpineRuntimeManager,
    private val runtimeHostController: RuntimeHostController,
    private val endpointRegistry: LlmBridgeEndpointRegistry,
    workspaceDirectory: File,
) : AutoCloseable {
    private data class OwnerKey(
        val profileId: String,
        val profileRevision: Int,
        val model: String,
    )

    private val appContext = context.applicationContext
    private val workspaceDirectory = workspaceDirectory.canonicalFile
    private val listeners = CopyOnWriteArrayList<AlpineWorkspaceLlmStateListener>()
    private val lock = Any()
    private val ownerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recoverySupervisor = AlpineLlmBridgeRecoverySupervisor.withRecoveryLease(
        healthCheck = ::healthForAutomaticRecovery,
        restartGateway = ::restartAfterUnexpectedFailure,
        configuration = AlpineLlmBridgeRecoveryConfiguration(
            // A user-visible Alpine workspace is long-lived, but recovery must never become a
            // hidden daemon or an unbounded restart loop.
            healthIntervalMillis = GATEWAY_HEALTH_INTERVAL_MILLIS,
            maxAutomaticRestarts = MAX_AUTOMATIC_GATEWAY_RESTARTS,
            initialBackoffMillis = GATEWAY_RECOVERY_INITIAL_BACKOFF_MILLIS,
            maxBackoffMillis = GATEWAY_RECOVERY_MAX_BACKOFF_MILLIS,
        ),
        listener = AlpineLlmBridgeRecoveryListener(::onRecoveryStateChanged),
    )

    @Volatile private var state = AlpineWorkspaceLlmState()
    private var ownerKey: OwnerKey? = null
    private var controller: AlpineLlmBridgeController? = null
    private var runtimeBinding: RuntimeSubscription? = null
    @Volatile private var closed = false

    fun currentState(): AlpineWorkspaceLlmState = state

    fun addStateListener(listener: AlpineWorkspaceLlmStateListener): RuntimeSubscription {
        listeners += listener
        runCatching { listener.onStateChanged(state) }
        return RuntimeSubscription { listeners -= listener }
    }

    /** Creates a router without starting Alpine. Preparation remains visible to SafeChatRouter. */
    suspend fun routerFor(session: ChatCompletionSession): SafeChatRouter = withContext(Dispatchers.IO) {
        IntegratedAlpineDependencies.routerFactoryForTests()?.let { factory ->
            return@withContext factory(session)
        }
        val activeController = controllerFor(session).toCompletableFuture().get()
        val direct = AndroidDirectChatBackend(id = DIRECT_BACKEND_ID) { requestJson ->
            session.streamForHostBridge(requestJson)
        }
        val alpine = BindingChatBackend(
            delegate = AlpineGatewayChatBackend(runtimeManager, activeController),
            onReady = {
                bindActiveSession(activeController)
                beginAutomaticRecovery(activeController)
            },
        )
        SafeChatRouter(directBackend = direct, alpineBackend = alpine)
    }

    fun start(session: ChatCompletionSession): CompletionStage<AlpineLlmBridgeHealth> =
        submit(AlpineWorkspaceLlmOperation.STARTING) {
            val active = controllerOwned(session)
            val health = if (active.currentState() == LlmBridgeLifecycleState.RUNNING) {
                active.health().toCompletableFuture().join().let { current ->
                    if (current.healthy) current else active.restart().toCompletableFuture().join()
                }
            } else {
                active.start().toCompletableFuture().join()
            }
            bindActiveSession(active)
            publishHealth(health)
            beginAutomaticRecovery(active)
            health
        }

    fun restart(session: ChatCompletionSession): CompletionStage<AlpineLlmBridgeHealth> =
        submit(AlpineWorkspaceLlmOperation.RESTARTING) {
            val active = controllerOwned(session)
            detachRuntimeSession()
            val health = if (active.currentState() == LlmBridgeLifecycleState.STOPPED) {
                active.start().toCompletableFuture().join()
            } else {
                active.restart().toCompletableFuture().join()
            }
            bindActiveSession(active)
            publishHealth(health)
            beginAutomaticRecovery(active)
            health
        }

    fun health(): CompletionStage<AlpineLlmBridgeHealth> =
        submit(AlpineWorkspaceLlmOperation.CHECKING_HEALTH) {
            val active = synchronized(lock) { controller }
                ?: throw LlmBridgeOperationException(LlmBridgeErrorCode.INVALID_STATE)
            val health = active.health().toCompletableFuture().join()
            publishHealth(health)
            health
        }

    fun stop(): CompletionStage<Void> {
        val future = CompletableFuture<Void>()
        // Revoke the supervisor generation before queueing the user-requested Stop. A stale
        // health callback may finish later, but it can no longer enqueue another restart.
        recoverySupervisor.stopMonitoring()
        update { it.copy(operation = AlpineWorkspaceLlmOperation.STOPPING) }
        ownerExecutor.execute {
            runCatching {
                val active = synchronized(lock) { controller }
                detachRuntimeSession()
                active?.stop()?.toCompletableFuture()?.join()
            }.fold(
                onSuccess = {
                    update {
                        it.copy(
                            lifecycle = LlmBridgeLifecycleState.STOPPED,
                            operation = AlpineWorkspaceLlmOperation.IDLE,
                            healthy = false,
                            checks = emptyMap(),
                            errorCode = null,
                            capabilityExpiresAtEpochMillis = null,
                        )
                    }
                    future.complete(null)
                },
                onFailure = { error ->
                    val safe = safeError(error)
                    update {
                        it.copy(
                            operation = AlpineWorkspaceLlmOperation.IDLE,
                            errorCode = safe.errorCode,
                        )
                    }
                    future.completeExceptionally(safe)
                },
            )
        }
        return future
    }

    private fun controllerFor(session: ChatCompletionSession): CompletionStage<AlpineLlmBridgeController> =
        submit(AlpineWorkspaceLlmOperation.IDLE) { controllerOwned(session) }

    private fun controllerOwned(session: ChatCompletionSession): AlpineLlmBridgeController {
        check(synchronized(lock) { !closed }) { "Alpine LLM host is closed" }
        val profile = session.profile
        val key = OwnerKey(profile.id, profile.hashCode(), session.descriptor.model)
        synchronized(lock) {
            if (ownerKey == key) return requireNotNull(controller)
        }

        recoverySupervisor.stopMonitoring()
        detachRuntimeSession()
        val previous = synchronized(lock) {
            controller.also {
                controller = null
                ownerKey = null
            }
        }
        previous?.close()

        val created = AlpineLlmBridgeController(
            runtimeManager = runtimeManager,
            workspaceDirectory = workspaceDirectory,
            gatewayArtifactProvider = BundledPythonGatewayArtifactProvider(appContext),
            endpointRegistry = endpointRegistry,
            hostBridgeFactory = {
                HostBridgeServer(
                    sessionTokenTtlMs = CAPABILITY_TTL_MILLIS,
                    streamExecutor = session::streamForHostBridge,
                    requestExecutor = { requestJson -> completeFromStream(session, requestJson) },
                )
            },
            configuration = AlpineLlmBridgeConfiguration(
                models = listOf(
                    AlpineLlmModel(
                        id = session.descriptor.model,
                        displayName = session.descriptor.model,
                        provider = profile.type.wireName,
                    ),
                ),
                defaultModel = session.descriptor.model,
                installPythonIfMissing = true,
            ),
            eventSink = ::onBridgeEvent,
        )
        synchronized(lock) {
            controller = created
            ownerKey = key
        }
        update {
            AlpineWorkspaceLlmState(
                lifecycle = LlmBridgeLifecycleState.STOPPED,
                profileLabel = session.descriptor.label,
                model = session.descriptor.model,
            )
        }
        return created
    }

    private suspend fun completeFromStream(
        session: ChatCompletionSession,
        requestJson: String,
    ): HostLlmResult {
        val stream = session.streamForHostBridge(requestJson)
        if (stream.statusCode !in 200..299) return HostLlmResult("{}", stream.statusCode)
        val text = StringBuilder()
        var finishReason = "stop"
        stream.events.collect { event ->
            val json = JSONObject(event.dataJson)
            if (json.optString("type").ifBlank { "delta" } != "delta") {
                throw IllegalStateException("normalized stream event is invalid")
            }
            text.append(json.optString("text"))
            json.optString("finish_reason").takeIf(String::isNotBlank)?.let {
                finishReason = it
            }
        }
        val body = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("message", JSONObject().put("role", "assistant").put("content", text.toString()))
                        .put("finish_reason", finishReason),
                ),
            )
            .toString()
        return HostLlmResult(body)
    }

    private fun bindActiveSession(activeController: AlpineLlmBridgeController) {
        val activeSession = activeController.activeRuntimeSession() ?: return
        synchronized(lock) {
            if (controller !== activeController) return
            runtimeBinding?.close()
            runtimeBinding = runtimeHostController.bindExternalSession(activeSession)
        }
    }

    /** Starts only after a user-selected Alpine session is already healthy. */
    private fun beginAutomaticRecovery(activeController: AlpineLlmBridgeController) {
        if (synchronized(lock) { controller === activeController } &&
            activeController.currentState() == LlmBridgeLifecycleState.RUNNING
        ) {
            recoverySupervisor.startMonitoring()
        }
    }

    private fun healthForAutomaticRecovery(): CompletionStage<AlpineLlmBridgeHealth> {
        val active = synchronized(lock) { controller }
            ?: return failedBridgeFuture(LlmBridgeErrorCode.INVALID_STATE)
        return active.health()
    }

    /**
     * Recovery owns only the Gateway/Runtime lifecycle. It does not submit a chat prompt, replay
     * a terminal command, or use the direct Provider backend.
     */
    private fun restartAfterUnexpectedFailure(
        lease: AlpineLlmBridgeRecoveryLease,
    ): CompletionStage<AlpineLlmBridgeHealth> =
        submit(AlpineWorkspaceLlmOperation.RECOVERING) {
            val active = synchronized(lock) { controller }
                ?: throw LlmBridgeOperationException(LlmBridgeErrorCode.INVALID_STATE)
            if (!ownsActiveRecovery(active, lease)) {
                throw RecoveryLeaseRevokedException()
            }
            detachRuntimeSession(active)
            if (!ownsActiveRecovery(active, lease)) {
                throw RecoveryLeaseRevokedException()
            }
            val health = if (active.currentState() == LlmBridgeLifecycleState.STOPPED) {
                active.start().toCompletableFuture().join()
            } else {
                active.restart().toCompletableFuture().join()
            }
            // `start`/`restart` cannot be interrupted safely once its runtime lifecycle call has
            // begun. If the user pressed Stop or changed owner during that narrow window, remove
            // the just-created owner instead of leaving an unwanted automatic restart alive.
            if (!ownsActiveRecovery(active, lease)) {
                runCatching { active.stop().toCompletableFuture().join() }
                detachRuntimeSession(active)
                throw RecoveryLeaseRevokedException()
            }
            bindActiveSession(active)
            publishHealth(health)
            health
        }

    private fun ownsActiveRecovery(
        active: AlpineLlmBridgeController,
        lease: AlpineLlmBridgeRecoveryLease,
    ): Boolean = lease.isActive() && synchronized(lock) {
        !closed && controller === active
    }

    private fun detachRuntimeSession(owner: AlpineLlmBridgeController? = null) {
        synchronized(lock) {
            if (owner != null && controller !== owner) return
            runtimeBinding?.close()
            runtimeBinding = null
        }
    }

    private fun publishHealth(health: AlpineLlmBridgeHealth) {
        update {
            it.copy(
                lifecycle = health.lifecycle,
                healthy = health.healthy,
                checks = health.checks.toMap(),
                errorCode = health.errorCode,
                capabilityExpiresAtEpochMillis = health.capabilityExpiresAtEpochMillis,
            )
        }
    }

    private fun onBridgeEvent(event: LlmBridgeEvent) {
        update { current ->
            current.copy(
                lifecycle = when (event.type) {
                    LlmBridgeEventType.STARTING -> LlmBridgeLifecycleState.STARTING
                    LlmBridgeEventType.STARTED -> LlmBridgeLifecycleState.RUNNING
                    LlmBridgeEventType.STOPPING -> LlmBridgeLifecycleState.STOPPING
                    LlmBridgeEventType.STOPPED -> LlmBridgeLifecycleState.STOPPED
                    LlmBridgeEventType.ERROR -> LlmBridgeLifecycleState.FAILED
                    LlmBridgeEventType.HEALTH_CHANGED -> current.lifecycle
                },
                healthy = event.healthy ?: current.healthy,
                errorCode = event.errorCode,
            )
        }
    }

    private fun onRecoveryStateChanged(recovery: AlpineLlmBridgeRecoveryState) {
        update { current ->
            when (recovery.mode) {
                AlpineLlmBridgeRecoveryMode.RECOVERING -> current.copy(
                    operation = AlpineWorkspaceLlmOperation.RECOVERING,
                    healthy = false,
                    errorCode = recovery.errorCode,
                )
                AlpineLlmBridgeRecoveryMode.EXHAUSTED -> current.copy(
                    lifecycle = LlmBridgeLifecycleState.FAILED,
                    operation = AlpineWorkspaceLlmOperation.IDLE,
                    healthy = false,
                    errorCode = recovery.errorCode ?: LlmBridgeErrorCode.GATEWAY_HEALTH_FAILED,
                )
                AlpineLlmBridgeRecoveryMode.MONITORING,
                AlpineLlmBridgeRecoveryMode.STOPPED,
                -> if (current.operation == AlpineWorkspaceLlmOperation.RECOVERING) {
                    current.copy(operation = AlpineWorkspaceLlmOperation.IDLE)
                } else {
                    current
                }
            }
        }
    }

    private fun <T> submit(
        operation: AlpineWorkspaceLlmOperation,
        block: () -> T,
    ): CompletionStage<T> {
        val future = CompletableFuture<T>()
        if (operation != AlpineWorkspaceLlmOperation.IDLE) {
            update { it.copy(operation = operation, errorCode = null) }
        }
        ownerExecutor.execute {
            runCatching(block).fold(
                onSuccess = { value ->
                    if (operation != AlpineWorkspaceLlmOperation.IDLE) {
                        update { it.copy(operation = AlpineWorkspaceLlmOperation.IDLE) }
                    }
                    future.complete(value)
                },
                onFailure = { error ->
                    if (isRecoveryLeaseRevoked(error)) {
                        // A user Stop/owner swap already owns the visible state. Do not replace
                        // it with a synthetic error while the stale recovery future unwinds.
                        future.completeExceptionally(
                            LlmBridgeOperationException(LlmBridgeErrorCode.INVALID_STATE),
                        )
                        return@fold
                    }
                    val safe = safeError(error)
                    update {
                        it.copy(
                            operation = AlpineWorkspaceLlmOperation.IDLE,
                            errorCode = safe.errorCode,
                        )
                    }
                    future.completeExceptionally(safe)
                },
            )
        }
        return future
    }

    private fun safeError(error: Throwable): LlmBridgeOperationException {
        var current: Throwable? = error
        repeat(12) {
            when (current) {
                is LlmBridgeOperationException -> return current as LlmBridgeOperationException
                is CompletionException -> current = current?.cause
                else -> current = current?.cause
            }
            if (current == null) return LlmBridgeOperationException(LlmBridgeErrorCode.INTERNAL_ERROR)
        }
        return LlmBridgeOperationException(LlmBridgeErrorCode.INTERNAL_ERROR)
    }

    private fun isRecoveryLeaseRevoked(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .take(12)
            .any { it is RecoveryLeaseRevokedException }

    private fun failedBridgeFuture(errorCode: LlmBridgeErrorCode): CompletionStage<AlpineLlmBridgeHealth> =
        CompletableFuture<AlpineLlmBridgeHealth>().also {
            it.completeExceptionally(LlmBridgeOperationException(errorCode))
        }

    private fun update(transform: (AlpineWorkspaceLlmState) -> AlpineWorkspaceLlmState) {
        val updated = synchronized(lock) { transform(state).also { state = it } }
        listeners.forEach { listener -> runCatching { listener.onStateChanged(updated) } }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        recoverySupervisor.close()
        runCatching { stop().toCompletableFuture().join() }
        detachRuntimeSession()
        val previous = synchronized(lock) {
            controller.also {
                controller = null
                ownerKey = null
            }
        }
        previous?.close()
        listeners.clear()
        ownerExecutor.shutdownNow()
    }

    private class BindingChatBackend(
        private val delegate: ChatBackend,
        private val onReady: () -> Unit,
        ) : ChatBackend {
        override val id: String = delegate.id
        override val kind = delegate.kind
        override val capabilities: ChatBackendCapabilities = delegate.capabilities

        override suspend fun prepare(request: ChatBackendRequest): ChatBackendPreparation =
            delegate.prepare(request).also { preparation ->
                if (preparation == ChatBackendPreparation.Ready) onReady()
            }

        override suspend fun stream(
            request: ChatBackendRequest,
            emitter: ChatStreamEmitter,
        ): ChatBackendResult = delegate.stream(request, emitter)
    }

    /** Internal control flow only; it is mapped to a closed lifecycle error for the supervisor. */
    private class RecoveryLeaseRevokedException : RuntimeException()

    private companion object {
        const val DIRECT_BACKEND_ID = "android-direct"
        const val CAPABILITY_TTL_MILLIS = 15L * 60_000L
        const val GATEWAY_HEALTH_INTERVAL_MILLIS = 30_000L
        const val MAX_AUTOMATIC_GATEWAY_RESTARTS = 2
        const val GATEWAY_RECOVERY_INITIAL_BACKOFF_MILLIS = 1_000L
        const val GATEWAY_RECOVERY_MAX_BACKOFF_MILLIS = 8_000L
    }
}

/** Credential-free instrumentation seam. Production must keep the factory set to `null`. */
object IntegratedAlpineDependencies {
    @Volatile
    private var testRouterFactory: ((ChatCompletionSession) -> SafeChatRouter)? = null

    internal fun routerFactoryForTests(): ((ChatCompletionSession) -> SafeChatRouter)? =
        testRouterFactory

    fun installRouterFactoryForTests(
        factory: ((ChatCompletionSession) -> SafeChatRouter)?,
    ) {
        testRouterFactory = factory
    }
}
