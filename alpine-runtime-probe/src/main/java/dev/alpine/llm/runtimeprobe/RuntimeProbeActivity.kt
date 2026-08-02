package dev.alpine.llm.runtimeprobe

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.alpine.runtime.android.AndroidRuntimeConfiguration
import dev.alpine.runtime.android.DefaultAndroidAlpineRuntimeFactory
import dev.alpine.runtime.api.RuntimeCommandRequest
import dev.alpine.runtime.api.RuntimeInstallRequest
import dev.alpine.runtime.api.RuntimeOperationException
import dev.alpine.runtime.api.RuntimeArtifactProvider
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeStartRequest
import dev.alpine.runtime.api.RuntimeStopReason
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimeArtifactProvider
import dev.alpine.runtime.pack.x8664.X8664RuntimeArtifactProvider
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Debug-only product-independent fixture for the Phase-3 SDK runtime gate.
 *
 * It intentionally uses the public SDK factory and executes PRoot
 * from ApplicationInfo.nativeLibraryDir. Results are persisted so adb/run-as
 * can verify the probe without parsing logcat or UI text.
 */
class RuntimeProbeActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Alpine Runtime Probe"
        setContentView(buildContent())
        runButton.setOnClickListener { runProbe() }
        if (intent.getBooleanExtra(EXTRA_AUTO_RUN, false)) runProbe()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        status = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            text = "Ready\nABI=${Build.SUPPORTED_ABIS.joinToString()}\ntargetSdk=${applicationInfo.targetSdkVersion}"
        }
        runButton = Button(this).apply { text = "Run Alpine probe" }
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
        status.text = "Installing rootfs and starting packaged PRoot…"
        executor.execute {
            val result = executeProbe(
                resetAfter = intent.getBooleanExtra(EXTRA_RESET_AFTER, false),
            )
            RESULT_FILE.writeText(result.toString(2))
            runOnUiThread {
                status.text = result.toString(2)
                runButton.isEnabled = true
            }
        }
    }

    private fun executeProbe(resetAfter: Boolean): JSONObject {
        val nativeProot = File(applicationInfo.nativeLibraryDir, "libproot.so")
        val nativeLoader = File(applicationInfo.nativeLibraryDir, "libproot-loader.so")
        val startedAt = System.currentTimeMillis()
        val base = JSONObject()
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("target_sdk", applicationInfo.targetSdkVersion)
            .put("supported_abis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("native_proot_path", nativeProot.absolutePath)
            .put("native_proot_exists", nativeProot.isFile)
            .put("native_proot_executable", nativeProot.canExecute())
            .put("native_loader_path", nativeLoader.absolutePath)
            .put("native_loader_exists", nativeLoader.isFile)
            .put("native_loader_executable", nativeLoader.canExecute())
        return runCatching {
            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull()
                ?: throw RuntimeOperationException(RuntimeErrorCode.UNSUPPORTED_ABI)
            val artifactProvider: RuntimeArtifactProvider = when (primaryAbi) {
                "arm64-v8a" -> BundledRuntimeArtifactProvider(
                    this,
                    Alpine321Arm64Pack.create(),
                )
                "x86_64" -> X8664RuntimeArtifactProvider(this)
                else -> throw RuntimeOperationException(RuntimeErrorCode.UNSUPPORTED_ABI)
            }
            val processEvents = mutableListOf<String>()
            val runtimeEvents = mutableListOf<String>()
            val runtime = DefaultAndroidAlpineRuntimeFactory().create(
                context = this,
                configuration = AndroidRuntimeConfiguration(
                    artifactProvider = artifactProvider,
                    processListener = { event ->
                        synchronized(processEvents) {
                            processEvents += "${event.kind}:${event.processId}"
                        }
                    },
                    eventSink = { event ->
                        synchronized(runtimeEvents) {
                            runtimeEvents += buildString {
                                append(event.kind)
                                event.errorCode?.let { append(":").append(it) }
                                event.attributes["reason"]?.let { append(":").append(it) }
                            }
                        }
                    },
                    runtimeDirectoryName = "alpine-runtime-probe-${primaryAbi.replace('-', '_')}",
                ),
            )
            try {
                val install = runtime.install(RuntimeInstallRequest()).toCompletableFuture().join()
                val session = runtime.start(RuntimeStartRequest()).toCompletableFuture().join()
                val command = listOf(
                    "printf 'probe=ok\\n'",
                    "printf 'alpine=' && cat /etc/alpine-release",
                    "printf 'machine=' && uname -m",
                    "printf 'uid=' && id -u",
                    "printf 'pwd=' && pwd",
                    "printf 'workspace-ok\\n' > /workspace/probe.txt",
                    "cat /workspace/probe.txt",
                ).joinToString("; ")
                val execution = session.execute(
                    RuntimeCommandRequest(
                        executable = "/bin/sh",
                        arguments = listOf("-lc", command),
                        timeoutMillis = 60_000L,
                    ),
                ).toCompletableFuture().join()
                val terminalOutput = ByteArrayOutputStream()
                val terminalReady = CountDownLatch(1)
                val terminalResizeReady = CountDownLatch(1)
                val terminalPrompt = CountDownLatch(1)
                val terminal = session.openTerminal(
                    dev.alpine.runtime.api.RuntimeTerminalRequest(columns = 96, rows = 28),
                ).toCompletableFuture().join()
                terminal.addOutputListener { bytes ->
                    synchronized(terminalOutput) {
                        terminalOutput.write(bytes)
                        val current = terminalOutput.toString(Charsets.UTF_8.name())
                        if (current.contains("/workspace #")) terminalPrompt.countDown()
                        if (current.contains("terminal_size=28 96")) {
                            terminalReady.countDown()
                        }
                        if (
                            current.contains("terminal_after_resize=40 120") ||
                            current.contains("terminal_after_resize=28 96")
                        ) {
                            terminalResizeReady.countDown()
                        }
                    }
                }
                val terminalPrompted = terminalPrompt.await(5, TimeUnit.SECONDS)
                terminal.write(
                    ("printf 'terminal=ok\\n'; " +
                        "printf 'terminal_mode=%s\\n' \"\${ALPINE_TERMINAL_MODE:-unknown}\"; " +
                        "printf 'terminal_resize_channel=%s\\n' \"\${ALPINE_TERMINAL_RESIZE_CHANNEL:-unknown}\"; " +
                        "printf 'terminal_size='; stty size\n")
                        .toByteArray(Charsets.UTF_8),
                ).toCompletableFuture().join()
                val terminalResponded = terminalReady.await(15, TimeUnit.SECONDS)
                val terminalResizeSupport = terminal.resizeSupport
                val resizeError = runCatching {
                    terminal.resize(columns = 120, rows = 40).toCompletableFuture().join()
                }.exceptionOrNull()
                val resizeErrorCode = generateSequence(resizeError) { it.cause }
                    .filterIsInstance<RuntimeOperationException>()
                    .firstOrNull()
                    ?.errorCode
                terminal.write(
                    ("printf 'terminal_resize_ack='; " +
                        "cat \"\${ALPINE_TERMINAL_RESIZE_ACK:-/dev/null}\" 2>/dev/null || true; " +
                        "printf 'terminal_after_resize='; stty size\n")
                        .toByteArray(Charsets.UTF_8),
                ).toCompletableFuture().join()
                val terminalResizeResponded = terminalResizeReady.await(15, TimeUnit.SECONDS)
                val terminalStdout = synchronized(terminalOutput) {
                    terminalOutput.toString(Charsets.UTF_8.name())
                }
                val resizeContractPassed = when (terminalResizeSupport) {
                    dev.alpine.runtime.api.RuntimeTerminalResizeSupport.DYNAMIC ->
                        resizeErrorCode == null &&
                            terminalStdout.contains("terminal_after_resize=40 120")
                    dev.alpine.runtime.api.RuntimeTerminalResizeSupport.INITIAL_SIZE_ONLY ->
                        resizeErrorCode == dev.alpine.runtime.api.RuntimeErrorCode.TERMINAL_RESIZE_UNSUPPORTED &&
                            terminalStdout.contains("terminal_after_resize=28 96")
                }
                terminal.closeAsync().toCompletableFuture().join()
                session.stop(RuntimeStopReason.USER_REQUEST).toCompletableFuture().join()
                val restartedSession = runtime.start(RuntimeStartRequest()).toCompletableFuture().join()
                val restartExecution = restartedSession.execute(
                    RuntimeCommandRequest(
                        executable = "/bin/sh",
                        arguments = listOf("-lc", "printf 'restart=ok\\n'"),
                        timeoutMillis = 60_000L,
                    ),
                ).toCompletableFuture().join()
                restartedSession.stop(RuntimeStopReason.USER_REQUEST).toCompletableFuture().join()
                val repair = runtime.repair().toCompletableFuture().join()
                val health = runtime.health().toCompletableFuture().join()
                val stdout = execution.standardOutput.toString(Charsets.UTF_8)
                val restartStdout = restartExecution.standardOutput.toString(Charsets.UTF_8)
                var resetState: String? = null
                var workspacePreserved: Boolean? = null
                if (resetAfter) {
                    runtime.reset().toCompletableFuture().join()
                    resetState = runtime.currentState().lifecycle.name
                    workspacePreserved = File(
                        filesDir,
                        "alpine-runtime-probe-${primaryAbi.replace('-', '_')}/workspace/probe.txt",
                    ).isFile
                }
                base
                    .put(
                        "success",
                        execution.exitCode == 0 &&
                            stdout.contains("probe=ok") &&
                            terminalResponded &&
                            terminalResizeResponded &&
                            resizeContractPassed &&
                            terminalPrompted &&
                            terminalStdout.contains("terminal=ok\r\nterminal_mode=native-pty") &&
                            terminalStdout.contains("terminal_size=28 96") &&
                            restartExecution.exitCode == 0 &&
                            restartStdout.contains("restart=ok") &&
                            (!resetAfter || resetState == "NOT_INSTALLED") &&
                            (!resetAfter || workspacePreserved == true),
                    )
                    .put("runtime_version", install.runtimeVersion)
                    .put("reused_install", install.reusedExistingInstall)
                    .put("repair_reused", repair.reusedExistingInstall)
                    .put("runtime_state", runtime.currentState().lifecycle.name)
                    .put("healthy", health.healthy)
                    .put("exit_code", execution.exitCode)
                    .put("timed_out", execution.timedOut)
                    .put("stdout", stdout)
                    .put("stderr", execution.standardError.toString(Charsets.UTF_8))
                    .put("terminal_responded", terminalResponded)
                    .put("terminal_resize_responded", terminalResizeResponded)
                    .put("terminal_resize_support", terminalResizeSupport.name)
                    .put("terminal_resize_error", resizeErrorCode?.name)
                    .put("terminal_prompted", terminalPrompted)
                    .put("terminal_stdout", terminalStdout)
                    .put("restart_stdout", restartStdout)
                    .put("reset_after", resetAfter)
                    .put("workspace_preserved_after_reset", workspacePreserved)
                    .put(
                        "process_events",
                        synchronized(processEvents) { processEvents.joinToString(",") },
                    )
            } finally {
                base.put(
                    "runtime_events",
                    synchronized(runtimeEvents) { runtimeEvents.joinToString(",") },
                )
                runtime.close()
            }
        }.getOrElse { error ->
            val safeError = generateSequence(error) { it.cause }
                .filterIsInstance<RuntimeOperationException>()
                .firstOrNull()
            base
                .put("success", false)
                .put("error_type", safeError?.javaClass?.simpleName ?: "ProbeFailure")
                .put("error", safeError?.errorCode?.name ?: "RUNTIME_PROBE_FAILED")
        }.put("elapsed_ms", System.currentTimeMillis() - startedAt)
    }

    private val RESULT_FILE: File
        get() = File(filesDir, "runtime-probe-result.json")

    companion object {
        const val EXTRA_AUTO_RUN = "auto_run"
        const val EXTRA_RESET_AFTER = "reset_after"
    }
}
