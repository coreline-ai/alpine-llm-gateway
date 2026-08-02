package dev.alpine.llm.bridgeprobe

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.alpine.llm.HostBridgeServer
import dev.alpine.llm.HostLlmResult
import dev.alpine.llm.HostLlmStreamEvent
import dev.alpine.llm.HostLlmStreamResult
import dev.alpine.runtime.android.AndroidRuntimeConfiguration
import dev.alpine.runtime.android.DefaultAndroidAlpineRuntimeFactory
import dev.alpine.runtime.api.RuntimeInstallRequest
import dev.alpine.runtime.api.RuntimeOperationException
import dev.alpine.runtime.bridge.AlpineLlmBridgeConfiguration
import dev.alpine.runtime.bridge.AlpineLlmBridgeController
import dev.alpine.runtime.bridge.AlpineLlmModel
import dev.alpine.runtime.bridge.LlmBridgeEndpointRegistry
import dev.alpine.runtime.bridge.LlmBridgeEnvironmentContributor
import dev.alpine.runtime.bridge.LlmBridgeOperationException
import dev.alpine.runtime.gateway.pack.bundled.BundledPythonGatewayArtifactProvider
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimeArtifactProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** Device E2E for Alpine llmctl -> Python Gateway -> Android Host Bridge. */
class LlmBridgeProbeActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        runButton.setOnClickListener { runProbe() }
        if (intent.getBooleanExtra(EXTRA_AUTO_RUN, false)) runProbe()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): ScrollView {
        val padding = (20 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            text = "Ready\nABI=${Build.SUPPORTED_ABIS.joinToString()}"
        }
        runButton = Button(this).apply { text = "Run Phase 4 LLM bridge probe" }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
            addView(
                runButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                status,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return ScrollView(this).apply { addView(body) }
    }

    private fun runProbe() {
        runButton.isEnabled = false
        status.text = "Installing Python and starting the secure LLM bridge…"
        executor.execute {
            val result = executeProbe()
            resultFile.writeText(result.toString(2))
            runOnUiThread {
                status.text = result.toString(2)
                runButton.isEnabled = true
            }
        }
    }

    private fun executeProbe(): JSONObject {
        val startedAt = System.currentTimeMillis()
        val base = JSONObject()
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("target_sdk", applicationInfo.targetSdkVersion)
            .put("model", Build.MODEL)
            .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
        return runCatching {
            val processEvents = mutableListOf<String>()
            val bridgeEvents = mutableListOf<String>()
            val registry = LlmBridgeEndpointRegistry()
            val runtimeDirectoryName = "alpine-llm-phase4-probe"
            val runtime = DefaultAndroidAlpineRuntimeFactory().create(
                this,
                AndroidRuntimeConfiguration(
                    artifactProvider = BundledRuntimeArtifactProvider(
                        this,
                        Alpine321Arm64Pack.create(),
                    ),
                    environmentContributors = listOf(LlmBridgeEnvironmentContributor(registry)),
                    processListener = { event ->
                        synchronized(processEvents) {
                            processEvents += "${event.kind}:${event.processId}"
                        }
                    },
                    runtimeDirectoryName = runtimeDirectoryName,
                ),
            )
            runtime.install(RuntimeInstallRequest()).toCompletableFuture().join()
            val workspace = File(filesDir, "$runtimeDirectoryName/workspace")
            val controller = AlpineLlmBridgeController(
                runtimeManager = runtime,
                workspaceDirectory = workspace,
                gatewayArtifactProvider = BundledPythonGatewayArtifactProvider(this),
                endpointRegistry = registry,
                hostBridgeFactory = {
                    HostBridgeServer(
                        sessionTokenTtlMs = 15 * 60_000L,
                        eventSink = { event ->
                            synchronized(bridgeEvents) { bridgeEvents += event.type.name }
                        },
                        streamExecutor = { request -> fakeStream(request) },
                        requestExecutor = { request -> fakeCompletion(request) },
                    )
                },
                configuration = AlpineLlmBridgeConfiguration(
                    models = listOf(AlpineLlmModel("bridge-test", "Bridge Test")),
                    defaultModel = "bridge-test",
                    installPythonIfMissing = true,
                    preparationTimeoutMillis = 240_000L,
                    gatewayStartupTimeoutMillis = 45_000L,
                ),
                eventSink = { event ->
                    synchronized(bridgeEvents) {
                        bridgeEvents += buildString {
                            append("OWNER_").append(event.type.name)
                            event.errorCode?.let { append(":").append(it.name) }
                        }
                    }
                },
            )
            try {
                val started = controller.start().toCompletableFuture().join()
                val capability = File(workspace, ".alpine-runtime/bridge.capability")
                val firstCapability = capability.readText()
                val configText = File(workspace, ".alpine-runtime/gateway-config.json").readText()

                val models = controller.executeLlmctl(listOf("models"))
                    .toCompletableFuture().join()
                val run = controller.executeLlmctl(
                    listOf("run", "--model", "bridge-test", "--prompt", "hello"),
                ).toCompletableFuture().join()
                val stream = controller.executeLlmctl(
                    listOf(
                        "run", "--model", "bridge-test", "--prompt", "stream-me",
                        "--stream", "--format", "jsonl",
                    ),
                ).toCompletableFuture().join()

                val baselineProcesses = controller.listProcesses().toCompletableFuture().join().size
                val cancelFuture = controller.executeLlmctl(
                    listOf(
                        "run", "--model", "bridge-test", "--prompt", "cancel-me",
                        "--stream", "--format", "jsonl",
                    ),
                    timeoutMillis = 120_000L,
                ).toCompletableFuture()
                val cancelStarted = waitForProcessCount(controller, baselineProcesses + 1, 5_000L)
                val cancelAccepted = cancelFuture.cancel(true)
                val cancelCleaned = waitForProcessCount(controller, baselineProcesses, 5_000L)

                val restarted = controller.restart().toCompletableFuture().join()
                val secondCapability = capability.readText()
                val restartRun = controller.executeLlmctl(
                    listOf("run", "--model", "bridge-test", "--prompt", "restart"),
                ).toCompletableFuture().join()
                val finalHealth = controller.health().toCompletableFuture().join()

                val modelsOut = models.standardOutput.toString(Charsets.UTF_8)
                val runOut = run.standardOutput.toString(Charsets.UTF_8)
                val streamOut = stream.standardOutput.toString(Charsets.UTF_8)
                val restartOut = restartRun.standardOutput.toString(Charsets.UTF_8)
                val tokenNotInConfig = !configText.contains(firstCapability)
                val success = started.healthy && restarted.healthy && finalHealth.healthy &&
                    models.exitCode == 0 && modelsOut.contains("bridge-test") &&
                    run.exitCode == 0 && runOut.contains("bridge-ok") &&
                    stream.exitCode == 0 && streamOut.contains("stream-") &&
                    streamOut.contains("ok") && cancelStarted && cancelAccepted && cancelCleaned &&
                    firstCapability != secondCapability && tokenNotInConfig &&
                    restartRun.exitCode == 0 && restartOut.contains("bridge-ok")
                base
                    .put("success", success)
                    .put("gateway_version", finalHealth.gatewayVersion)
                    .put("protocol_version", finalHealth.protocolVersion)
                    .put("models_exit", models.exitCode)
                    .put("models_stdout", modelsOut)
                    .put("run_exit", run.exitCode)
                    .put("run_stdout", runOut)
                    .put("stream_exit", stream.exitCode)
                    .put("stream_stdout", streamOut)
                    .put("cancel_process_started", cancelStarted)
                    .put("cancel_accepted", cancelAccepted)
                    .put("cancel_process_cleaned", cancelCleaned)
                    .put("capability_rotated", firstCapability != secondCapability)
                    .put("capability_absent_from_config", tokenNotInConfig)
                    .put("health_checks", JSONObject(finalHealth.checks))
            } finally {
                controller.close()
                val healthAfterStop = runtime.health().toCompletableFuture().join()
                base
                    .put("runtime_lifecycle_after_stop", healthAfterStop.lifecycle.name)
                    .put("process_events", synchronized(processEvents) { processEvents.joinToString(",") })
                    .put("bridge_events", synchronized(bridgeEvents) { bridgeEvents.joinToString(",") })
                runtime.close()
            }
        }.getOrElse { error ->
            val runtimeError = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<RuntimeOperationException>()
                .firstOrNull()
            val bridgeError = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<LlmBridgeOperationException>()
                .firstOrNull()
            base
                .put("success", false)
                .put(
                    "error",
                    bridgeError?.errorCode?.name
                        ?: runtimeError?.errorCode?.name
                        ?: "LLM_BRIDGE_PROBE_FAILED",
                )
        }.put("elapsed_ms", System.currentTimeMillis() - startedAt)
    }

    private fun fakeCompletion(request: String): HostLlmResult {
        val model = JSONObject(request).optString("model", "bridge-test")
        return HostLlmResult(
            JSONObject()
                .put("id", "chatcmpl_bridge_probe")
                .put("model", model)
                .put(
                    "choices",
                    JSONArray().put(
                        JSONObject()
                            .put("index", 0)
                            .put(
                                "message",
                                JSONObject().put("role", "assistant").put("content", "bridge-ok"),
                            )
                            .put("finish_reason", "stop"),
                    ),
                )
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1))
                .toString(),
        )
    }

    private fun fakeStream(request: String): HostLlmStreamResult {
        val cancellationProbe = request.contains("cancel-me")
        return HostLlmStreamResult(
            events = flow {
                if (cancellationProbe) {
                    repeat(600) {
                        emit(HostLlmStreamEvent.delta("tick"))
                        delay(100)
                    }
                } else {
                    emit(HostLlmStreamEvent.delta("stream-"))
                    delay(100)
                    emit(HostLlmStreamEvent.delta("ok", finishReason = "stop"))
                }
            },
        )
    }

    private fun waitForProcessCount(
        controller: AlpineLlmBridgeController,
        expected: Int,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (controller.listProcesses().toCompletableFuture().join().size == expected) return true
            Thread.sleep(100)
        }
        return false
    }

    private val resultFile: File
        get() = File(filesDir, "llm-bridge-probe-result.json")

    companion object {
        const val EXTRA_AUTO_RUN = "auto_run"
    }
}
