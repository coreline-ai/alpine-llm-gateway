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

    @Volatile private var state = AlpineWorkspaceLlmState()
    private var ownerKey: OwnerKey? = null
    private var controller: AlpineLlmBridgeController? = null
    private var runtimeBinding: RuntimeSubscription? = null
    private var closed = false

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
            onReady = { bindActiveSession(activeController) },
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
        check(!closed) { "Alpine LLM host is closed" }
        val profile = session.profile
        val key = OwnerKey(profile.id, profile.hashCode(), session.descriptor.model)
        synchronized(lock) {
            if (ownerKey == key) return requireNotNull(controller)
        }

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

    private fun detachRuntimeSession() {
        synchronized(lock) {
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

    private fun update(transform: (AlpineWorkspaceLlmState) -> AlpineWorkspaceLlmState) {
        val updated = synchronized(lock) { transform(state).also { state = it } }
        listeners.forEach { listener -> runCatching { listener.onStateChanged(updated) } }
    }

    override fun close() {
        if (closed) return
        closed = true
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

    private companion object {
        const val DIRECT_BACKEND_ID = "android-direct"
        const val CAPABILITY_TTL_MILLIS = 15L * 60_000L
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
