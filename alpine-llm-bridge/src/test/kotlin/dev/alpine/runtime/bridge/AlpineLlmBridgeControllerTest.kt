package dev.alpine.runtime.bridge

import dev.alpine.llm.HostBridgeServer
import dev.alpine.llm.HostLlmResult
import dev.alpine.runtime.api.AlpineRuntimeManager
import dev.alpine.runtime.api.RuntimeCommandRequest
import dev.alpine.runtime.api.RuntimeCommandResult
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
import dev.alpine.runtime.api.RuntimeTerminalRequest
import dev.alpine.runtime.api.RuntimeTerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors

class AlpineLlmBridgeControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `single owner stages capability starts gateway and cleans secrets on stop`() {
        val fixture = fixture()
        val events = mutableListOf<LlmBridgeEvent>()
        val controller = fixture.controller(LlmBridgeEventSink(events::add))

        val started = controller.start().toCompletableFuture().join()
        val privateDirectory = fixture.privateDirectory
        val capability = privateDirectory.resolve("bridge.capability")
        val config = privateDirectory.resolve("gateway-config.json")
        val capabilityValue = capability.readText()

        assertTrue(started.healthy)
        assertEquals(LlmBridgeLifecycleState.RUNNING, controller.currentState())
        assertTrue(capabilityValue.length >= 32)
        assertFalse(config.readText().contains(capabilityValue))
        assertFalse(config.readText().contains("oauth", ignoreCase = true))
        assertTrue(config.readText().contains("\"api_key_file\""))
        val environment = LlmBridgeEnvironmentContributor(fixture.registry).contribute(
            dev.alpine.runtime.api.RuntimeEnvironmentContext("session", "/workspace"),
        )
        assertEquals(
            "/workspace/.alpine-runtime/bridge.capability",
            environment["ALPINE_LLM_CREDENTIAL_FILE"],
        )
        assertFalse(environment.values.contains(capabilityValue))

        val command = controller.executeLlmctl(listOf("models")).toCompletableFuture().join()
        assertEquals(0, command.exitCode)
        assertEquals("/usr/local/bin/llmctl", fixture.runtime.session.lastRequest?.executable)

        controller.stop().toCompletableFuture().join()

        assertEquals(LlmBridgeLifecycleState.STOPPED, controller.currentState())
        assertFalse(capability.exists())
        assertFalse(config.exists())
        assertTrue(privateDirectory.resolve("alpine-llm-gateway.tar.gz").isFile)
        assertTrue(LlmBridgeEnvironmentContributor(fixture.registry).contribute(
            dev.alpine.runtime.api.RuntimeEnvironmentContext("session", "/workspace"),
        ).isEmpty())
        assertTrue(events.all { it.toString().contains(capabilityValue).not() })
        controller.close()
    }

    @Test
    fun `restart rotates capability and leaves one gateway owner`() {
        val fixture = fixture()
        val controller = fixture.controller()
        controller.start().toCompletableFuture().join()
        val capability = fixture.privateDirectory.resolve("bridge.capability")
        val first = capability.readText()

        val restarted = controller.restart().toCompletableFuture().join()
        val second = capability.readText()

        assertTrue(restarted.healthy)
        assertNotEquals(first, second)
        assertEquals(2, fixture.runtime.startCount)
        assertEquals(1, fixture.runtime.activeSessionCount)
        controller.close()
    }

    @Test
    fun `artifact checksum mismatch fails closed before capability creation`() {
        val fixture = fixture(corruptDescriptor = true)
        val controller = fixture.controller()

        val error = assertThrows(Exception::class.java) {
            controller.start().toCompletableFuture().join()
        }
        val safe = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<LlmBridgeOperationException>()
            .first()

        assertEquals(LlmBridgeErrorCode.ARTIFACT_INVALID, safe.errorCode)
        assertFalse(fixture.privateDirectory.resolve("bridge.capability").exists())
        assertEquals(0, fixture.runtime.startCount)
        controller.close()
    }

    @Test
    fun `gateway process crash is reported by closed health state`() {
        val fixture = fixture()
        val controller = fixture.controller()
        controller.start().toCompletableFuture().join()

        fixture.runtime.session.crashGateway()
        val health = controller.health().toCompletableFuture().join()

        assertFalse(health.healthy)
        assertEquals(false, health.checks["gateway_process"])
        assertEquals(LlmBridgeErrorCode.GATEWAY_HEALTH_FAILED, health.errorCode)
        controller.close()
    }

    @Test
    fun `configuration rejects an empty model allowlist`() {
        assertThrows(IllegalArgumentException::class.java) {
            AlpineLlmBridgeConfiguration(emptyList(), "auto")
        }
    }

    private fun fixture(corruptDescriptor: Boolean = false): Fixture {
        val workspace = temporaryFolder.newFolder("workspace-${System.nanoTime()}")
        val payload = "gateway-layer".toByteArray()
        val hash = sha256(payload)
        val descriptor = PythonGatewayArtifactDescriptor(
            id = "alpine-llm-gateway",
            packageVersion = "0.3.0",
            protocolVersion = "1",
            minimumPythonVersion = "3.11",
            sha256 = if (corruptDescriptor) "0".repeat(64) else hash,
            sizeBytes = payload.size.toLong(),
            license = "NOASSERTION",
        )
        val manifest = PythonGatewayArtifactManifest(
            packageId = descriptor.id,
            packageVersion = descriptor.packageVersion,
            protocolVersion = descriptor.protocolVersion,
            entrypoints = mapOf(
                "llmctl" to "/usr/local/bin/llmctl",
                "gatewayd" to "/usr/local/bin/llm-gatewayd",
            ),
        )
        val provider = PythonGatewayArtifactProvider {
            CompletableFuture.completedFuture(
                PythonGatewayArtifactBundle(
                    manifest,
                    object : PythonGatewayArtifact {
                        override val descriptor = descriptor
                        override fun openStream() = ByteArrayInputStream(payload)
                    },
                ),
            )
        }
        return Fixture(
            workspace = workspace,
            runtime = ImmediateRuntimeManager(),
            registry = LlmBridgeEndpointRegistry(),
            provider = provider,
        )
    }

    private fun Fixture.controller(eventSink: LlmBridgeEventSink = LlmBridgeEventSink.NONE) =
        AlpineLlmBridgeController(
            runtimeManager = runtime,
            workspaceDirectory = workspace,
            gatewayArtifactProvider = provider,
            endpointRegistry = registry,
            hostBridgeFactory = { HostBridgeServer { HostLlmResult("{}") } },
            configuration = AlpineLlmBridgeConfiguration(
                models = listOf(AlpineLlmModel("bridge-test", "Bridge Test")),
                defaultModel = "bridge-test",
            ),
            eventSink = eventSink,
            clock = System::currentTimeMillis,
            lifecycleExecutor = Executors.newSingleThreadExecutor(),
            healthProbe = GatewayHealthProbe {
                GatewayProtocolHealth(true, "0.3.0", "1")
            },
        )

    private data class Fixture(
        val workspace: java.io.File,
        val runtime: ImmediateRuntimeManager,
        val registry: LlmBridgeEndpointRegistry,
        val provider: PythonGatewayArtifactProvider,
    ) {
        val privateDirectory: java.io.File
            get() = workspace.resolve(".alpine-runtime")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private class ImmediateRuntimeManager : AlpineRuntimeManager {
    var state = RuntimeState(RuntimeLifecycleState.READY, activeVersion = "test")
    var startCount = 0
    var activeSessionCount = 0
    lateinit var session: ImmediateRuntimeSession

    override fun currentState(): RuntimeState = state
    override fun addStateListener(listener: RuntimeStateListener) = RuntimeSubscription { }
    override fun addEventListener(listener: RuntimeEventListener) = RuntimeSubscription { }
    override fun install(request: RuntimeInstallRequest): CompletionStage<RuntimeInstallResult> =
        CompletableFuture.completedFuture(RuntimeInstallResult("test", emptyList(), true))

    override fun start(request: RuntimeStartRequest): CompletionStage<RuntimeSession> {
        startCount++
        activeSessionCount++
        state = RuntimeState(RuntimeLifecycleState.RUNNING, activeVersion = "test")
        session = ImmediateRuntimeSession {
            activeSessionCount--
            state = RuntimeState(RuntimeLifecycleState.READY, activeVersion = "test")
        }
        return CompletableFuture.completedFuture(session)
    }

    override fun stop(reason: RuntimeStopReason): CompletionStage<Void> = completedVoid()
    override fun repair(): CompletionStage<RuntimeInstallResult> = install(RuntimeInstallRequest())
    override fun reset(): CompletionStage<Void> = completedVoid()
    override fun health(): CompletionStage<RuntimeHealth> = CompletableFuture.completedFuture(
        RuntimeHealth(true, state.lifecycle, System.currentTimeMillis()),
    )
}

private class ImmediateRuntimeSession(private val onStop: () -> Unit) : RuntimeSession {
    override val id = "test-session"
    override val startedAtEpochMillis = System.currentTimeMillis()
    var lastRequest: RuntimeCommandRequest? = null
    private var stopped = false
    private val gatewayProcess = CompletableFuture<RuntimeCommandResult>()

    fun crashGateway() {
        gatewayProcess.complete(RuntimeCommandResult(1))
    }

    override fun execute(request: RuntimeCommandRequest): CompletionStage<RuntimeCommandResult> {
        lastRequest = request
        return if (request.executable.endsWith("llm-gatewayd")) {
            gatewayProcess
        } else {
            CompletableFuture.completedFuture(RuntimeCommandResult(0))
        }
    }

    override fun openTerminal(request: RuntimeTerminalRequest): CompletionStage<RuntimeTerminalSession> =
        CompletableFuture<RuntimeTerminalSession>().also {
            it.completeExceptionally(UnsupportedOperationException())
        }

    override fun listProcesses(): CompletionStage<List<RuntimeProcessInfo>> =
        CompletableFuture.completedFuture(emptyList())

    override fun health(): CompletionStage<RuntimeHealth> = CompletableFuture.completedFuture(
        RuntimeHealth(!stopped, RuntimeLifecycleState.RUNNING, System.currentTimeMillis()),
    )

    override fun stop(reason: RuntimeStopReason): CompletionStage<Void> {
        if (!stopped) {
            stopped = true
            gatewayProcess.cancel(true)
            onStop()
        }
        return completedVoid()
    }
}

private fun completedVoid(): CompletionStage<Void> =
    CompletableFuture<Void>().also { it.complete(null) }
