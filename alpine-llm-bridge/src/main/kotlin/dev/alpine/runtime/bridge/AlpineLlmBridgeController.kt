package dev.alpine.runtime.bridge

import dev.alpine.llm.AlpineLlmGatewayClient
import dev.alpine.llm.HostBridgeServer
import dev.alpine.runtime.api.AlpineRuntimeManager
import dev.alpine.runtime.api.RuntimeCommandRequest
import dev.alpine.runtime.api.RuntimeCommandResult
import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.api.RuntimeProcessInfo
import dev.alpine.runtime.api.RuntimeSession
import dev.alpine.runtime.api.RuntimeStartRequest
import dev.alpine.runtime.api.RuntimeStopReason
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

enum class LlmBridgeLifecycleState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
}

enum class LlmBridgeErrorCode {
    INVALID_STATE,
    ARTIFACT_INVALID,
    PROTOCOL_MISMATCH,
    CAPABILITY_WRITE_FAILED,
    PYTHON_UNAVAILABLE,
    GATEWAY_INSTALL_FAILED,
    GATEWAY_START_FAILED,
    GATEWAY_HEALTH_FAILED,
    BRIDGE_HEALTH_FAILED,
    RUNTIME_FAILED,
    INTERNAL_ERROR,
}

class LlmBridgeOperationException(
    val errorCode: LlmBridgeErrorCode,
) : RuntimeException(errorCode.name)

data class AlpineLlmModel(
    val id: String,
    val displayName: String = id,
    val provider: String = "android-host",
) {
    init {
        require(id.isNotBlank()) { "model id must not be blank" }
        require(displayName.isNotBlank()) { "model displayName must not be blank" }
        require(provider.isNotBlank()) { "model provider must not be blank" }
    }
}

data class AlpineLlmBridgeConfiguration @JvmOverloads constructor(
    val models: List<AlpineLlmModel>,
    val defaultModel: String,
    val gatewayPort: Int = 8787,
    val installPythonIfMissing: Boolean = false,
    val preparationTimeoutMillis: Long = 180_000L,
    val gatewayStartupTimeoutMillis: Long = 30_000L,
    val gatewayCommandTimeoutMillis: Long = 24L * 60 * 60 * 1_000,
) {
    init {
        require(models.isNotEmpty()) { "models must not be empty (fail-closed)" }
        require(models.map { it.id }.toSet().size == models.size) { "model ids must be unique" }
        require(defaultModel in models.map { it.id }) { "defaultModel must be allowed" }
        require(gatewayPort in 1..65535) { "gatewayPort must be between 1 and 65535" }
        require(preparationTimeoutMillis > 0) { "preparationTimeoutMillis must be positive" }
        require(gatewayStartupTimeoutMillis > 0) { "gatewayStartupTimeoutMillis must be positive" }
        require(gatewayCommandTimeoutMillis > 0) { "gatewayCommandTimeoutMillis must be positive" }
    }
}

data class AlpineLlmBridgeHealth(
    val healthy: Boolean,
    val lifecycle: LlmBridgeLifecycleState,
    val gatewayVersion: String? = null,
    val protocolVersion: String? = null,
    val capabilityExpiresAtEpochMillis: Long? = null,
    val errorCode: LlmBridgeErrorCode? = null,
    val checks: Map<String, Boolean> = emptyMap(),
)

enum class LlmBridgeEventType {
    STARTING,
    STARTED,
    STOPPING,
    STOPPED,
    HEALTH_CHANGED,
    ERROR,
}

/** Closed event shape: no arbitrary text, command, URL, credential, or exception can be emitted. */
data class LlmBridgeEvent(
    val type: LlmBridgeEventType,
    val timestampEpochMillis: Long,
    val errorCode: LlmBridgeErrorCode? = null,
    val healthy: Boolean? = null,
)

fun interface LlmBridgeEventSink {
    fun emit(event: LlmBridgeEvent)

    companion object {
        val NONE = LlmBridgeEventSink { }
    }
}

internal data class GatewayProtocolHealth(
    val healthy: Boolean,
    val version: String?,
    val protocolVersion: String?,
)

internal fun interface GatewayHealthProbe {
    fun check(baseUrl: String): GatewayProtocolHealth
}

/**
 * Single lifecycle owner for Host Bridge, Alpine runtime session, and Python Gateway.
 *
 * The only guest-visible credential is a short-lived Host Bridge capability stored in the
 * app-private workspace. Android OAuth access/refresh tokens remain inside the host provider.
 */
class AlpineLlmBridgeController internal constructor(
    private val runtimeManager: AlpineRuntimeManager,
    workspaceDirectory: File,
    private val gatewayArtifactProvider: PythonGatewayArtifactProvider,
    private val endpointRegistry: LlmBridgeEndpointRegistry,
    private val hostBridgeFactory: () -> HostBridgeServer,
    private val configuration: AlpineLlmBridgeConfiguration,
    private val eventSink: LlmBridgeEventSink,
    private val clock: () -> Long,
    private val lifecycleExecutor: ExecutorService,
    private val healthProbe: GatewayHealthProbe,
) : AutoCloseable {
    @JvmOverloads
    constructor(
        runtimeManager: AlpineRuntimeManager,
        workspaceDirectory: File,
        gatewayArtifactProvider: PythonGatewayArtifactProvider,
        endpointRegistry: LlmBridgeEndpointRegistry,
        hostBridgeFactory: () -> HostBridgeServer,
        configuration: AlpineLlmBridgeConfiguration,
        eventSink: LlmBridgeEventSink = LlmBridgeEventSink.NONE,
    ) : this(
        runtimeManager = runtimeManager,
        workspaceDirectory = workspaceDirectory,
        gatewayArtifactProvider = gatewayArtifactProvider,
        endpointRegistry = endpointRegistry,
        hostBridgeFactory = hostBridgeFactory,
        configuration = configuration,
        eventSink = eventSink,
        clock = System::currentTimeMillis,
        lifecycleExecutor = Executors.newSingleThreadExecutor(),
        healthProbe = GatewayHealthProbe { baseUrl ->
            val json = AlpineLlmGatewayClient(
                baseUrl = baseUrl,
                connectTimeoutMs = 2_000,
                readTimeoutMs = 2_000,
                maxResponseBytes = 64 * 1024,
            ).health()
            GatewayProtocolHealth(
                healthy = json.optString("status") == "ok",
                version = json.optString("version").ifBlank { null },
                protocolVersion = json.optString("protocol_version").ifBlank { null },
            )
        },
    )

    private val workspaceDirectory = workspaceDirectory.canonicalFile
    private val privateDirectory = File(this.workspaceDirectory, PRIVATE_DIRECTORY_NAME)
    private val artifactFile = File(privateDirectory, GATEWAY_ARCHIVE_FILE_NAME)
    private val capabilityFile = File(privateDirectory, CAPABILITY_FILE_NAME)
    private val configFile = File(privateDirectory, CONFIG_FILE_NAME)
    private val lifecycleLock = Any()

    @Volatile private var state = LlmBridgeLifecycleState.STOPPED
    @Volatile private var session: RuntimeSession? = null
    @Volatile private var bridge: HostBridgeServer? = null
    @Volatile private var endpoint: HostBridgeServer.Endpoint? = null
    @Volatile private var gatewayProcess: CompletableFuture<RuntimeCommandResult>? = null
    @Volatile private var activeManifest: PythonGatewayArtifactManifest? = null

    init {
        require(this.workspaceDirectory.path.isNotBlank())
    }

    fun currentState(): LlmBridgeLifecycleState = state

    fun start(): CompletionStage<AlpineLlmBridgeHealth> = submitLifecycle {
        startOwned()
    }

    fun restart(): CompletionStage<AlpineLlmBridgeHealth> = submitLifecycle {
        stopOwned()
        startOwned()
    }

    fun stop(): CompletionStage<Void> {
        val future = CompletableFuture<Void>()
        lifecycleExecutor.execute {
            runCatching { stopOwned() }.fold(
                onSuccess = { future.complete(null) },
                onFailure = { future.completeExceptionally(safeFailure(it)) },
            )
        }
        return future
    }

    fun health(): CompletionStage<AlpineLlmBridgeHealth> = submitLifecycle {
        healthOwned(emit = true)
    }

    /** Executes the packaged llmctl inside Alpine. Cancellation terminates the guest process. */
    fun executeLlmctl(
        arguments: List<String>,
        timeoutMillis: Long = 180_000L,
    ): CompletionStage<RuntimeCommandResult> {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(arguments.size <= 128 && arguments.all { it.length <= 16_384 && '\u0000' !in it }) {
            "llmctl arguments exceed the safety limit"
        }
        val activeSession = session
        val manifest = activeManifest
        if (state != LlmBridgeLifecycleState.RUNNING || activeSession == null || manifest == null) {
            return failedFuture(LlmBridgeOperationException(LlmBridgeErrorCode.INVALID_STATE))
        }
        return activeSession.execute(
            RuntimeCommandRequest(
                executable = manifest.entrypoints.getValue("llmctl"),
                arguments = arguments,
                timeoutMillis = timeoutMillis,
            ),
        )
    }

    fun listProcesses(): CompletionStage<List<RuntimeProcessInfo>> {
        val activeSession = session
        if (state != LlmBridgeLifecycleState.RUNNING || activeSession == null) {
            return failedFuture(LlmBridgeOperationException(LlmBridgeErrorCode.INVALID_STATE))
        }
        return activeSession.listProcesses()
    }

    override fun close() {
        runCatching { stop().toCompletableFuture().get(10, TimeUnit.SECONDS) }
        lifecycleExecutor.shutdownNow()
    }

    private fun startOwned(): AlpineLlmBridgeHealth {
        synchronized(lifecycleLock) {
            if (state !in setOf(LlmBridgeLifecycleState.STOPPED, LlmBridgeLifecycleState.FAILED)) {
                throw LlmBridgeOperationException(LlmBridgeErrorCode.INVALID_STATE)
            }
            state = LlmBridgeLifecycleState.STARTING
        }
        emit(LlmBridgeEventType.STARTING)
        try {
            if (runtimeManager.currentState().lifecycle != RuntimeLifecycleState.READY) {
                throw LlmBridgeOperationException(LlmBridgeErrorCode.INVALID_STATE)
            }
            val artifact = gatewayArtifactProvider.resolve().toCompletableFuture().join()
            if (artifact.manifest.protocolVersion != PROTOCOL_VERSION) {
                throw LlmBridgeOperationException(LlmBridgeErrorCode.PROTOCOL_MISMATCH)
            }
            stageVerifiedArtifact(artifact)

            val createdBridge = hostBridgeFactory()
            bridge = createdBridge
            val createdEndpoint = createdBridge.start()
            endpoint = createdEndpoint
            writePrivateText(capabilityFile, createdEndpoint.sessionToken)
            writePrivateText(configFile, gatewayConfig(createdEndpoint))
            endpointRegistry.update(
                LlmBridgeEndpoint(
                    endpointUrl = createdEndpoint.url,
                    credentialFilePath = GUEST_CAPABILITY_PATH,
                ),
            )

            val runtimeSession = runtimeManager.start(
                RuntimeStartRequest(
                    environment = mapOf(
                        "ALPINE_LLM_URL" to gatewayBaseUrl(),
                        "ALPINE_LLM_CONFIG" to GUEST_CONFIG_PATH,
                    ),
                ),
            ).toCompletableFuture().join()
            session = runtimeSession
            prepareGateway(runtimeSession, artifact.manifest)
            activeManifest = artifact.manifest
            val process = runtimeSession.execute(
                RuntimeCommandRequest(
                    executable = artifact.manifest.entrypoints.getValue("gatewayd"),
                    arguments = listOf("serve", "--config", GUEST_CONFIG_PATH),
                    timeoutMillis = configuration.gatewayCommandTimeoutMillis,
                ),
            ).toCompletableFuture()
            gatewayProcess = process
            waitForGateway(process, artifact.manifest)
            state = LlmBridgeLifecycleState.RUNNING
            emit(LlmBridgeEventType.STARTED)
            return healthOwned(emit = false)
        } catch (error: Throwable) {
            cleanupOwned()
            state = LlmBridgeLifecycleState.FAILED
            val safe = safeFailure(error)
            emit(LlmBridgeEventType.ERROR, safe.errorCode)
            throw safe
        }
    }

    private fun stopOwned() {
        if (state == LlmBridgeLifecycleState.STOPPED) return
        state = LlmBridgeLifecycleState.STOPPING
        emit(LlmBridgeEventType.STOPPING)
        cleanupOwned()
        state = LlmBridgeLifecycleState.STOPPED
        emit(LlmBridgeEventType.STOPPED)
    }

    private fun cleanupOwned() {
        gatewayProcess?.cancel(true)
        gatewayProcess = null
        runCatching { session?.stop(RuntimeStopReason.USER_REQUEST)?.toCompletableFuture()?.join() }
        session = null
        activeManifest = null
        capabilityFile.delete()
        configFile.delete()
        endpointRegistry.clear()
        runCatching { bridge?.stop() }
        bridge = null
        endpoint = null
    }

    private fun healthOwned(emit: Boolean): AlpineLlmBridgeHealth {
        val currentEndpoint = endpoint
        val currentSession = session
        val manifest = activeManifest
        val checks = linkedMapOf(
            "owner_running" to (state == LlmBridgeLifecycleState.RUNNING),
            "runtime_session" to (currentSession != null),
            "gateway_process" to (gatewayProcess?.isDone == false),
            "capability_file" to capabilityFile.isFile,
        )
        var gatewayHealth: GatewayProtocolHealth? = null
        if (currentEndpoint != null) {
            checks["capability_ttl"] = clock() < currentEndpoint.expiresAtEpochMillis
            val bridgeHealth = runCatching {
                AlpineLlmGatewayClient(
                    baseUrl = currentEndpoint.url,
                    connectTimeoutMs = 2_000,
                    readTimeoutMs = 2_000,
                    maxResponseBytes = 64 * 1024,
                ).health()
            }.getOrNull()
            checks["host_bridge"] = bridgeHealth?.optString("status") == "ok" &&
                bridgeHealth.optString("protocol_version") == PROTOCOL_VERSION &&
                bridgeHealth.optBoolean("capability_valid")
        } else {
            checks["capability_ttl"] = false
            checks["host_bridge"] = false
        }
        gatewayHealth = runCatching { healthProbe.check(gatewayBaseUrl()) }.getOrNull()
        checks["python_gateway"] = gatewayHealth?.healthy == true
        checks["protocol"] = gatewayHealth?.protocolVersion == PROTOCOL_VERSION &&
            manifest?.protocolVersion == PROTOCOL_VERSION
        val healthy = checks.values.all { it }
        val error = when {
            checks["host_bridge"] != true -> LlmBridgeErrorCode.BRIDGE_HEALTH_FAILED
            checks["python_gateway"] != true || checks["gateway_process"] != true ->
                LlmBridgeErrorCode.GATEWAY_HEALTH_FAILED
            !healthy -> LlmBridgeErrorCode.RUNTIME_FAILED
            else -> null
        }
        val result = AlpineLlmBridgeHealth(
            healthy = healthy,
            lifecycle = state,
            gatewayVersion = gatewayHealth?.version,
            protocolVersion = gatewayHealth?.protocolVersion,
            capabilityExpiresAtEpochMillis = currentEndpoint?.expiresAtEpochMillis,
            errorCode = error,
            checks = checks,
        )
        if (emit) emit(LlmBridgeEventType.HEALTH_CHANGED, error, healthy)
        return result
    }

    private fun stageVerifiedArtifact(bundle: PythonGatewayArtifactBundle) {
        if (!privateDirectory.exists() && !privateDirectory.mkdirs()) {
            throw LlmBridgeOperationException(LlmBridgeErrorCode.CAPABILITY_WRITE_FAILED)
        }
        val descriptor = bundle.artifact.descriptor
        val temporary = File(privateDirectory, "$GATEWAY_ARCHIVE_FILE_NAME.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            bundle.artifact.openStream().use { input ->
                FileOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > descriptor.sizeBytes) {
                            throw LlmBridgeOperationException(LlmBridgeErrorCode.ARTIFACT_INVALID)
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            if (size != descriptor.sizeBytes || !actual.equals(descriptor.sha256, ignoreCase = true)) {
                throw LlmBridgeOperationException(LlmBridgeErrorCode.ARTIFACT_INVALID)
            }
            if (artifactFile.exists() && !artifactFile.delete()) {
                throw LlmBridgeOperationException(LlmBridgeErrorCode.ARTIFACT_INVALID)
            }
            if (!temporary.renameTo(artifactFile)) {
                throw LlmBridgeOperationException(LlmBridgeErrorCode.ARTIFACT_INVALID)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun writePrivateText(target: File, value: String) {
        try {
            if (!privateDirectory.exists() && !privateDirectory.mkdirs()) {
                throw IllegalStateException()
            }
            val temporary = File(privateDirectory, "${target.name}.tmp")
            temporary.writeText(value, Charsets.UTF_8)
            temporary.setReadable(false, false)
            temporary.setWritable(false, false)
            temporary.setExecutable(false, false)
            if (!temporary.setReadable(true, true) || !temporary.setWritable(true, true)) {
                throw IllegalStateException()
            }
            if (target.exists() && !target.delete()) throw IllegalStateException()
            if (!temporary.renameTo(target)) throw IllegalStateException()
        } catch (_: Exception) {
            throw LlmBridgeOperationException(LlmBridgeErrorCode.CAPABILITY_WRITE_FAILED)
        }
    }

    private fun gatewayConfig(endpoint: HostBridgeServer.Endpoint): String {
        val catalog = JSONArray()
        configuration.models.forEach { model ->
            catalog.put(
                JSONObject()
                    .put("id", model.id)
                    .put("display_name", model.displayName)
                    .put("provider", model.provider)
                    .put("modalities", JSONArray(listOf("text_input", "text_output"))),
            )
        }
        return JSONObject()
            .put("host", LOOPBACK)
            .put("port", configuration.gatewayPort)
            .put("provider", "android-host-bridge")
            .put("base_url", "${endpoint.url}/v1")
            .put("api_key_file", GUEST_CAPABILITY_PATH)
            .put("default_model", configuration.defaultModel)
            .put("allowed_models", JSONArray(configuration.models.map { it.id }))
            .put("model_catalog", catalog)
            .put("allow_passthrough", false)
            .put("provider_retry_max_attempts", 1)
            .toString()
    }

    private fun prepareGateway(session: RuntimeSession, manifest: PythonGatewayArtifactManifest) {
        val pythonPolicy = if (configuration.installPythonIfMissing) {
            "command -v python3 >/dev/null 2>&1 || apk add --no-cache python3"
        } else {
            "command -v python3 >/dev/null 2>&1 || exit $PYTHON_MISSING_EXIT_CODE"
        }
        val command = listOf(
            "set -eu",
            pythonPolicy,
            "rm -rf /opt/alpine-llm-gateway",
            "tar -xzf $GUEST_GATEWAY_ARCHIVE_PATH -C /",
            "test -x ${manifest.entrypoints.getValue("llmctl")}",
            "test -x ${manifest.entrypoints.getValue("gatewayd")}",
            "python3 -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)'",
        ).joinToString("; ")
        val result = session.execute(
            RuntimeCommandRequest(
                executable = "/bin/sh",
                arguments = listOf("-lc", command),
                timeoutMillis = configuration.preparationTimeoutMillis,
            ),
        ).toCompletableFuture().join()
        if (result.exitCode == PYTHON_MISSING_EXIT_CODE) {
            throw LlmBridgeOperationException(LlmBridgeErrorCode.PYTHON_UNAVAILABLE)
        }
        if (result.exitCode != 0 || result.timedOut) {
            throw LlmBridgeOperationException(LlmBridgeErrorCode.GATEWAY_INSTALL_FAILED)
        }
    }

    private fun waitForGateway(
        process: CompletableFuture<RuntimeCommandResult>,
        manifest: PythonGatewayArtifactManifest,
    ) {
        val deadline = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(configuration.gatewayStartupTimeoutMillis)
        while (System.nanoTime() < deadline) {
            if (process.isDone) {
                throw LlmBridgeOperationException(LlmBridgeErrorCode.GATEWAY_START_FAILED)
            }
            val health = runCatching { healthProbe.check(gatewayBaseUrl()) }.getOrNull()
            if (health?.healthy == true) {
                if (health.protocolVersion != manifest.protocolVersion ||
                    health.version != manifest.packageVersion
                ) {
                    throw LlmBridgeOperationException(LlmBridgeErrorCode.PROTOCOL_MISMATCH)
                }
                return
            }
            Thread.sleep(STARTUP_POLL_MILLIS)
        }
        throw LlmBridgeOperationException(LlmBridgeErrorCode.GATEWAY_HEALTH_FAILED)
    }

    private fun gatewayBaseUrl(): String = "http://$LOOPBACK:${configuration.gatewayPort}"

    private fun emit(
        type: LlmBridgeEventType,
        errorCode: LlmBridgeErrorCode? = null,
        healthy: Boolean? = null,
    ) {
        runCatching { eventSink.emit(LlmBridgeEvent(type, clock(), errorCode, healthy)) }
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

    private fun safeFailure(error: Throwable): LlmBridgeOperationException {
        val causes = generateSequence(error) { it.cause }.toList()
        return causes.filterIsInstance<LlmBridgeOperationException>().firstOrNull()
            ?: LlmBridgeOperationException(LlmBridgeErrorCode.INTERNAL_ERROR)
    }

    private fun <T> failedFuture(error: Throwable): CompletionStage<T> =
        CompletableFuture<T>().also { it.completeExceptionally(error) }

    private companion object {
        const val PROTOCOL_VERSION = "1"
        const val LOOPBACK = "127.0.0.1"
        const val PRIVATE_DIRECTORY_NAME = ".alpine-runtime"
        const val GATEWAY_ARCHIVE_FILE_NAME = "alpine-llm-gateway.tar.gz"
        const val CAPABILITY_FILE_NAME = "bridge.capability"
        const val CONFIG_FILE_NAME = "gateway-config.json"
        const val GUEST_GATEWAY_ARCHIVE_PATH =
            "/workspace/$PRIVATE_DIRECTORY_NAME/$GATEWAY_ARCHIVE_FILE_NAME"
        const val GUEST_CAPABILITY_PATH =
            "/workspace/$PRIVATE_DIRECTORY_NAME/$CAPABILITY_FILE_NAME"
        const val GUEST_CONFIG_PATH = "/workspace/$PRIVATE_DIRECTORY_NAME/$CONFIG_FILE_NAME"
        const val PYTHON_MISSING_EXIT_CODE = 42
        const val STARTUP_POLL_MILLIS = 100L
    }
}
