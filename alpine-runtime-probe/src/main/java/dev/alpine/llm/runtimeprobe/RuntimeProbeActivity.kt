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
import java.util.concurrent.atomic.AtomicInteger

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
                ttyDiagnostic = intent.getBooleanExtra(EXTRA_TTY_DIAGNOSTIC, false),
                ttyResizeStress = intent.getBooleanExtra(EXTRA_TTY_RESIZE_STRESS, false),
                ttyDisableProotSeccomp = intent.getBooleanExtra(EXTRA_TTY_DISABLE_PROOT_SECCOMP, false),
                ttyVirtualWinsizeNoWrite = intent.getBooleanExtra(EXTRA_TTY_VIRTUAL_WINSIZE_NO_WRITE, false),
                ttyVirtualWinsizeNoRequest = intent.getBooleanExtra(EXTRA_TTY_VIRTUAL_WINSIZE_NO_REQUEST, false),
                ttyVirtualWinsizeSkipResize = intent.getBooleanExtra(EXTRA_TTY_VIRTUAL_WINSIZE_SKIP_RESIZE, false),
                ttyDisablePrimaryTraceeForeground = intent.getBooleanExtra(EXTRA_TTY_DISABLE_PRIMARY_TRACEE_FOREGROUND, false),
                ttyUsePatchedProot = intent.getBooleanExtra(EXTRA_TTY_USE_PATCHED_PROOT, true),
                ttySkipTerminalWinsizeReads = intent.getBooleanExtra(EXTRA_TTY_SKIP_TERMINAL_WINSIZE_READS, false),
            )
            RESULT_FILE.writeText(result.toString(2))
            runOnUiThread {
                status.text = result.toString(2)
                runButton.isEnabled = true
            }
        }
    }

    private fun executeProbe(
        resetAfter: Boolean,
        ttyDiagnostic: Boolean,
        ttyResizeStress: Boolean,
        ttyDisableProotSeccomp: Boolean,
        ttyVirtualWinsizeNoWrite: Boolean,
        ttyVirtualWinsizeNoRequest: Boolean,
        ttyVirtualWinsizeSkipResize: Boolean,
        ttyDisablePrimaryTraceeForeground: Boolean,
        ttyUsePatchedProot: Boolean,
        ttySkipTerminalWinsizeReads: Boolean,
    ): JSONObject {
        val nativeProot = File(
            applicationInfo.nativeLibraryDir,
            if (ttyDiagnostic && ttyUsePatchedProot) {
                TtyDiagnosticRuntimePack.LAUNCHER_LIBRARY_NAME
            } else {
                "libproot.so"
            },
        )
        val nativeLoader = File(applicationInfo.nativeLibraryDir, "libproot-loader.so")
        val ttyWinsizeHelper = File(
            applicationInfo.nativeLibraryDir,
            TtyDiagnosticRuntimePack.GUEST_HELPER_LIBRARY_NAME,
        )
        val ttySessionLauncher = File(
            applicationInfo.nativeLibraryDir,
            TtyDiagnosticRuntimePack.SESSION_LAUNCHER_LIBRARY_NAME,
        )
        val hostPtyResizeControl = File(
            applicationInfo.nativeLibraryDir,
            TtyDiagnosticRuntimePack.HOST_PTY_RESIZE_CONTROL_LIBRARY_NAME,
        )
        val ttyWinsizeHelperPackaged = ttyDiagnostic &&
            ttyWinsizeHelper.isFile &&
            ttyWinsizeHelper.canExecute() &&
            ttyWinsizeHelper.length() in 1L..MAX_TTY_WINSIZE_HELPER_BYTES.toLong()
        val startedAt = System.currentTimeMillis()
        val base = JSONObject()
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("target_sdk", applicationInfo.targetSdkVersion)
            .put("supported_abis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("native_proot_exists", nativeProot.isFile)
            .put("native_proot_executable", nativeProot.canExecute())
            .put("native_loader_exists", nativeLoader.isFile)
            .put("native_loader_executable", nativeLoader.canExecute())
            .put("tty_diagnostic_requested", ttyDiagnostic)
            .put("tty_resize_stress_requested", ttyDiagnostic && ttyResizeStress)
            .put("tty_proot_seccomp_disabled_requested", ttyDiagnostic && ttyDisableProotSeccomp)
            .put("tty_virtual_winsize_no_write_requested", ttyDiagnostic && ttyVirtualWinsizeNoWrite)
            .put("tty_virtual_winsize_no_request_requested", ttyDiagnostic && ttyVirtualWinsizeNoRequest)
            .put("tty_virtual_winsize_skip_resize_requested", ttyDiagnostic && ttyVirtualWinsizeSkipResize)
            .put("tty_primary_tracee_foreground_disabled_requested", ttyDiagnostic && ttyDisablePrimaryTraceeForeground)
            .put("tty_patched_proot_requested", ttyDiagnostic && ttyUsePatchedProot)
            .put("tty_terminal_winsize_reads_skipped_requested", ttySkipTerminalWinsizeReads)
            .put("tty_winsize_helper_packaged", ttyWinsizeHelperPackaged)
            .put(
                "tty_session_launcher_packaged",
                ttyDiagnostic && ttySessionLauncher.isFile && ttySessionLauncher.canExecute(),
            )
            .put(
                "host_pty_resize_control_packaged",
                ttyDiagnostic && hostPtyResizeControl.isFile && hostPtyResizeControl.canExecute(),
            )
        return runCatching {
            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull()
                ?: throw RuntimeOperationException(RuntimeErrorCode.UNSUPPORTED_ABI)
            if (ttyDiagnostic && primaryAbi != "arm64-v8a") {
                throw RuntimeOperationException(RuntimeErrorCode.UNSUPPORTED_ABI)
            }
            val hostPtyResizeControlPassed = !ttyDiagnostic ||
                runHostPtyResizeControl(hostPtyResizeControl)
            val artifactProvider: RuntimeArtifactProvider = when (primaryAbi) {
                "arm64-v8a" -> BundledRuntimeArtifactProvider(
                    this,
                    if (ttyDiagnostic && ttyUsePatchedProot) {
                        TtyDiagnosticRuntimePack.create()
                    } else {
                        Alpine321Arm64Pack.create()
                    },
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
                            processEvents += event.kind.name
                        }
                    },
                    eventSink = { event ->
                        synchronized(runtimeEvents) {
                            runtimeEvents += buildString {
                                append(event.kind)
                                event.errorCode?.let { append(":").append(it) }
                                event.attributes["reason"]?.let { append(":").append(it) }
                                event.attributes["exit_code"]?.let { append(":exit=").append(it) }
                            }
                        }
                    },
                    runtimeDirectoryName = "alpine-runtime-probe-${primaryAbi.replace('-', '_')}",
                    enableTtyIoctlDiagnostics = ttyDiagnostic && primaryAbi == "arm64-v8a",
                    disableProotSeccompForTtyDiagnostic =
                        ttyDiagnostic && ttyDisableProotSeccomp && primaryAbi == "arm64-v8a",
                    ttyDiagnosticGuestHelperFileName = if (ttyWinsizeHelperPackaged) {
                        TtyDiagnosticRuntimePack.GUEST_HELPER_LIBRARY_NAME
                    } else {
                        null
                    },
                    ttyDiagnosticSessionLauncherFileName = if (
                        ttyDiagnostic && ttySessionLauncher.isFile && ttySessionLauncher.canExecute()
                    ) {
                        TtyDiagnosticRuntimePack.SESSION_LAUNCHER_LIBRARY_NAME
                    } else {
                        null
                    },
                    ttyDiagnosticVirtualResize =
                        ttyDiagnostic && ttySessionLauncher.isFile && ttySessionLauncher.canExecute(),
                    ttyDiagnosticVirtualResizeNoWrite =
                        ttyDiagnostic && ttyVirtualWinsizeNoWrite &&
                            ttySessionLauncher.isFile && ttySessionLauncher.canExecute(),
                    ttyDiagnosticVirtualResizeNoRequest =
                        ttyDiagnostic && ttyVirtualWinsizeNoRequest &&
                            ttySessionLauncher.isFile && ttySessionLauncher.canExecute(),
                    ttyDiagnosticDisablePrimaryTraceeForeground =
                        ttyDiagnostic && ttyDisablePrimaryTraceeForeground,
                ),
            )
            try {
                val install = runtime.install(RuntimeInstallRequest()).toCompletableFuture().join()
                val ttyWinsizeHelperBound = ttyDiagnostic && ttyWinsizeHelperPackaged
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
                val terminalResizeCommandReady = CountDownLatch(1)
                val terminalResizeRecoveryReady = CountDownLatch(1)
                val terminalRepeatFirstReady = CountDownLatch(1)
                val terminalRepeatSecondReady = CountDownLatch(1)
                val terminalStormReady = CountDownLatch(1)
                val terminalTopologyReady = CountDownLatch(1)
                val terminalPrompt = CountDownLatch(1)
                val terminalEchoOffReady = CountDownLatch(1)
                val terminalWinchArmedReady = CountDownLatch(1)
                val terminalFollowUpReady = CountDownLatch(1)
                val ttyWinsizeHelperExecutionReady = CountDownLatch(1)
                val terminalAfterHelperReady = CountDownLatch(1)
                val terminalSafeFollowUpReady = CountDownLatch(1)
                val terminalWinchSignals = AtomicInteger(0)
                val terminalWinchAcks = List(MAX_PROBE_WINCH_SIGNALS + 1) {
                    CountDownLatch(if (it == 0) 0 else 1)
                }
                fun awaitWinchSignalCount(expected: Int, timeoutSeconds: Long): Boolean {
                    if (expected !in 1..MAX_PROBE_WINCH_SIGNALS) return false
                    return terminalWinchAcks[expected].await(timeoutSeconds, TimeUnit.SECONDS)
                }
                fun recordWinchSignalCount(observed: Int) {
                    while (true) {
                        val current = terminalWinchSignals.get()
                        if (observed <= current || terminalWinchSignals.compareAndSet(current, observed)) {
                            for (index in 1..minOf(observed, MAX_PROBE_WINCH_SIGNALS)) {
                                terminalWinchAcks[index].countDown()
                            }
                            return
                        }
                    }
                }
                val terminal = session.openTerminal(
                    dev.alpine.runtime.api.RuntimeTerminalRequest(columns = 96, rows = 28),
                ).toCompletableFuture().join()
                terminal.addOutputListener { bytes ->
                    synchronized(terminalOutput) {
                        terminalOutput.write(bytes)
                        val current = terminalOutput.toString(Charsets.UTF_8.name())
                        if (current.contains("/workspace #")) terminalPrompt.countDown()
                        if (current.contains("terminal_size=28 96") ||
                            current.contains("terminal_size=skipped")) {
                            terminalReady.countDown()
                        }
                        if (
                            current.contains("terminal_after_resize=40x120") ||
                            current.contains("terminal_after_resize=28x96") ||
                            current.contains("terminal_after_resize=skipped") ||
                            current.contains("terminal_after_resize=unexpected")
                        ) {
                            terminalResizeReady.countDown()
                        }
                        if (current.contains("terminal_after_resize_command_dispatched")) {
                            terminalResizeCommandReady.countDown()
                        }
                        if (current.contains("terminal_after_resize_recovery=")) {
                            terminalResizeRecoveryReady.countDown()
                        }
                        if (current.contains("terminal_after_repeat_first=")) {
                            terminalRepeatFirstReady.countDown()
                        }
                        if (current.contains("terminal_after_repeat_second=")) {
                            terminalRepeatSecondReady.countDown()
                        }
                        if (current.contains("terminal_after_storm=")) {
                            terminalStormReady.countDown()
                        }
                        if (current.contains("terminal_tty_topology=")) {
                            terminalTopologyReady.countDown()
                        }
                        if (current.contains("terminal_echo_off_ready")) {
                            terminalEchoOffReady.countDown()
                        }
                        if (current.contains("terminal_winch_armed")) {
                            terminalWinchArmedReady.countDown()
                        }
                        val observedWinchSignals = TtyProbeMarkers.receivedWinchCount(current)
                        if (observedWinchSignals > terminalWinchSignals.get()) {
                            recordWinchSignalCount(observedWinchSignals)
                        }
                        if (current.contains("tty_helper_follow_up")) {
                            terminalFollowUpReady.countDown()
                        }
                        if (current.contains("terminal_safe_follow_up")) {
                            terminalSafeFollowUpReady.countDown()
                        }
                        if (TTY_WINSIZE_HELPER_EXECUTED.find(current) != null) {
                            ttyWinsizeHelperExecutionReady.countDown()
                        }
                        if (
                            current.contains("terminal_after_helper=40x120") ||
                            current.contains("terminal_after_helper=28x96") ||
                            current.contains("terminal_after_helper=unexpected")
                        ) {
                            terminalAfterHelperReady.countDown()
                        }
                    }
                }
                val terminalPrompted = terminalPrompt.await(5, TimeUnit.SECONDS)
                terminal.write(
                    "stty -echo; printf 'terminal_echo_off_ready\\n'\n".toByteArray(Charsets.UTF_8),
                ).toCompletableFuture().join()
                val terminalEchoDisabled = terminalEchoOffReady.await(5, TimeUnit.SECONDS)
                terminal.write(
                    ("winch_count=0; " +
                        "trap 'winch_count=\$((winch_count + 1)); printf \"terminal_winch_received_%s\\n\" \"\$winch_count\"' WINCH; " +
                        "printf 'terminal_%s\\n' winch_armed; " +
                        "set -- \$(cat /proc/self/stat); " +
                        "tty=0; [ \"\$7\" != 0 ] && tty=1; " +
                        "foreground=0; [ \"\$5\" = \"\$8\" ] && foreground=1; " +
                        "session=0; [ \"\$5\" = \"\$6\" ] && session=1; " +
                        "printf 'terminal_tty_topology=%s,%s,%s\\n' \"\$tty\" \"\$foreground\" \"\$session\"; " +
                        "printf 'terminal=ok\\n'; " +
                        "printf 'terminal_mode=%s\\n' \"\${ALPINE_TERMINAL_MODE:-unknown}\"; " +
                        "printf 'terminal_resize_channel=%s\\n' \"\${ALPINE_TERMINAL_RESIZE_CHANNEL:-unknown}\"; " +
                        if (ttySkipTerminalWinsizeReads) {
                            "printf 'terminal_size=skipped\\n'"
                        } else {
                            "printf 'terminal_size='; stty size"
                        } + "\n")
                        .toByteArray(Charsets.UTF_8),
                ).toCompletableFuture().join()
                val terminalResponded = terminalReady.await(15, TimeUnit.SECONDS)
                val terminalTopologyResponded = terminalTopologyReady.await(15, TimeUnit.SECONDS)
                val terminalWinchArmed = terminalWinchArmedReady.await(5, TimeUnit.SECONDS)
                val terminalResizeSupport = terminal.resizeSupport
                val terminalWinchRelayMode = if (ttyDiagnostic) {
                    TtyDiagnosticRuntimePack.SIGWINCH_RELAY_MODE
                } else {
                    "NOT_REQUESTED"
                }
                val terminalWinchForwarded = terminalWinchRelayMode == TtyProbeMarkers.WINCH_FORWARDED
                val terminalWinchWithheld = TtyProbeMarkers.withholdsGuestWinch(terminalWinchRelayMode)
                val terminalWinchExpected = TtyProbeMarkers.expectsGuestWinch(terminalWinchRelayMode)
                val terminalVirtualWinsize = TtyProbeMarkers.isVirtualWinsizeMode(terminalWinchRelayMode)
                val terminalTraceeForeground =
                    terminalWinchRelayMode == TtyProbeMarkers.WINCH_TRACEE_FOREGROUND
                fun resizeErrorCodeFor(columns: Int, rows: Int): RuntimeErrorCode? =
                    runCatching {
                        terminal.resize(columns = columns, rows = rows).toCompletableFuture().join()
                    }.exceptionOrNull()
                        ?.let { error ->
                            generateSequence(error) { it.cause }
                                .filterIsInstance<RuntimeOperationException>()
                                .firstOrNull()
                                ?.errorCode
                        }
                val terminalResizeAttempted = !ttyVirtualWinsizeSkipResize
                val resizeErrorCode = if (terminalResizeAttempted) {
                    resizeErrorCodeFor(columns = 120, rows = 40)
                } else {
                    null
                }
                // Keep the first fixed marker immediate. The preceding ordered
                // control proved that a ptrace-delivered WINCH is not surfaced
                // while ash remains blocked in read. The later recovery marker
                // classifies whether that one immediate write is merely lost or
                // whether the terminal stays unusable after signal delivery.
                val terminalResizeCommandAfterWinch = false
                val terminalWinchBeforeResizeCommand = if (terminalResizeCommandAfterWinch) {
                    awaitWinchSignalCount(expected = 1, timeoutSeconds = 5)
                } else {
                    false
                }
                terminal.write(
                    terminalSizeMarkerCommand(
                        tag = "terminal_after_resize",
                        rows = if (ttyDiagnostic) 40 else 28,
                        columns = if (ttyDiagnostic) 120 else 96,
                        skipWinsizeRead = ttySkipTerminalWinsizeReads,
                    ).toByteArray(Charsets.UTF_8),
                ).toCompletableFuture().join()
                val terminalResizeResponded = terminalResizeReady.await(15, TimeUnit.SECONDS)
                val terminalResizeCommandResponded = terminalResizeCommandReady.await(5, TimeUnit.SECONDS)
                val terminalWinchReceived = terminalWinchBeforeResizeCommand ||
                    (terminalWinchExpected && awaitWinchSignalCount(expected = 1, timeoutSeconds = 5))
                // If the immediate fixed marker woke a ptrace-delivered WINCH
                // but was itself lost, a second fixed marker tells us whether
                // the input path resumes after that first interruption. It is
                // a Probe-only classification; no terminal payload is replayed
                // and production must never re-execute a user command.
                val terminalResizeRecoveryAttempted = terminalTraceeForeground &&
                    terminalWinchReceived && !terminalResizeCommandAfterWinch
                if (terminalResizeRecoveryAttempted) {
                    terminal.write(
                        terminalSizeMarkerCommand(
                            tag = "terminal_after_resize_recovery",
                            rows = 40,
                            columns = 120,
                        ).toByteArray(Charsets.UTF_8),
                    ).toCompletableFuture().join()
                }
                val terminalResizeRecoveryResponded = !terminalResizeRecoveryAttempted ||
                    terminalResizeRecoveryReady.await(5, TimeUnit.SECONDS)
                val repeatFirstResizeError = if (ttyDiagnostic && ttyResizeStress) {
                    resizeErrorCodeFor(columns = 80, rows = 24)
                } else {
                    null
                }
                val terminalRepeatFirstWinchBeforeCommand = if (
                    ttyDiagnostic && ttyResizeStress && terminalResizeCommandAfterWinch
                ) {
                    awaitWinchSignalCount(expected = 2, timeoutSeconds = 5)
                } else {
                    false
                }
                if (ttyDiagnostic && ttyResizeStress) {
                    terminal.write(
                        terminalSizeMarkerCommand("terminal_after_repeat_first", rows = 24, columns = 80)
                            .toByteArray(Charsets.UTF_8),
                    ).toCompletableFuture().join()
                }
                val terminalRepeatFirstResponded = !(ttyDiagnostic && ttyResizeStress) ||
                    terminalRepeatFirstReady.await(5, TimeUnit.SECONDS)
                val terminalRepeatFirstWinchReceived = terminalRepeatFirstWinchBeforeCommand ||
                    (ttyDiagnostic && ttyResizeStress && terminalWinchExpected &&
                        awaitWinchSignalCount(expected = 2, timeoutSeconds = 5))
                val repeatSecondResizeError = if (ttyDiagnostic && ttyResizeStress) {
                    resizeErrorCodeFor(columns = 120, rows = 40)
                } else {
                    null
                }
                val terminalRepeatSecondWinchBeforeCommand = if (
                    ttyDiagnostic && ttyResizeStress && terminalResizeCommandAfterWinch
                ) {
                    awaitWinchSignalCount(expected = 3, timeoutSeconds = 5)
                } else {
                    false
                }
                if (ttyDiagnostic && ttyResizeStress) {
                    terminal.write(
                        terminalSizeMarkerCommand("terminal_after_repeat_second", rows = 40, columns = 120)
                            .toByteArray(Charsets.UTF_8),
                    ).toCompletableFuture().join()
                }
                val terminalRepeatSecondResponded = !(ttyDiagnostic && ttyResizeStress) ||
                    terminalRepeatSecondReady.await(5, TimeUnit.SECONDS)
                val terminalRepeatSecondWinchReceived = terminalRepeatSecondWinchBeforeCommand ||
                    (ttyDiagnostic && ttyResizeStress && terminalWinchExpected &&
                        awaitWinchSignalCount(expected = 3, timeoutSeconds = 5))
                val stormSteps = listOf(
                    80 to 24,
                    120 to 40,
                    80 to 24,
                    120 to 40,
                    80 to 24,
                    120 to 40,
                    80 to 24,
                    120 to 40,
                )
                val winchSignalsBeforeStorm = terminalWinchSignals.get()
                val stormResizeErrorCodes = if (ttyDiagnostic && ttyResizeStress) {
                    stormSteps.map { (columns, rows) -> resizeErrorCodeFor(columns, rows) }
                } else {
                    emptyList()
                }
                val terminalStormWinchBeforeCommand = if (
                    ttyDiagnostic && ttyResizeStress && terminalResizeCommandAfterWinch
                ) {
                    awaitWinchSignalCount(expected = winchSignalsBeforeStorm + 1, timeoutSeconds = 5)
                } else {
                    false
                }
                if (ttyDiagnostic && ttyResizeStress) {
                    terminal.write(
                        terminalSizeMarkerCommand("terminal_after_storm", rows = 40, columns = 120)
                            .toByteArray(Charsets.UTF_8),
                    ).toCompletableFuture().join()
                }
                val terminalStormResponded = !(ttyDiagnostic && ttyResizeStress) ||
                    terminalStormReady.await(5, TimeUnit.SECONDS)
                val terminalStormWinchReceived = terminalStormWinchBeforeCommand ||
                    (ttyDiagnostic && ttyResizeStress && terminalWinchExpected &&
                        awaitWinchSignalCount(expected = winchSignalsBeforeStorm + 1, timeoutSeconds = 5))
                val ttyWinsizeHelperCommand = if (ttyWinsizeHelperBound) {
                    "; printf 'tty_%s\\n' helper_follow_up; " +
                        "if [ -f /workspace/$TTY_WINSIZE_HELPER_FILE_NAME ] && " +
                        "[ -x /workspace/$TTY_WINSIZE_HELPER_FILE_NAME ]; then " +
                        "printf 'tty_winsize_helper_%s=%s\\n' available 1; " +
                        "else printf 'tty_winsize_helper_%s=%s\\n' available 0; fi; " +
                        "if /workspace/$TTY_WINSIZE_HELPER_FILE_NAME; then " +
                        "printf 'tty_winsize_helper_%s=%s\\n' executed 1; " +
                        "else printf 'tty_winsize_helper_%s=%s\\n' executed 0; fi; " +
                        "size_after_helper=\"\$(stty size)\"; tag_after_helper=terminal_after_helper; " +
                        "if [ \"\$size_after_helper\" = '40 120' ]; then " +
                        "printf '%s=%sx%s\\n' \"\$tag_after_helper\" 40 120; " +
                        "elif [ \"\$size_after_helper\" = '28 96' ]; then " +
                        "printf '%s=%sx%s\\n' \"\$tag_after_helper\" 28 96; " +
                        "else printf '%s=%s\\n' \"\$tag_after_helper\" unexpected; fi"
                } else {
                    ""
                }
                terminal.write(
                    ("printf 'terminal_resize_channel_after=%s\\n' \"\${ALPINE_TERMINAL_RESIZE_CHANNEL:-unknown}\"; " +
                        "printf 'terminal_safe_follow_up\\n'" +
                        ttyWinsizeHelperCommand +
                        "\n")
                        .toByteArray(Charsets.UTF_8),
                ).toCompletableFuture().join()
                val terminalFollowUpResponded = !ttyDiagnostic ||
                    (ttyWinsizeHelperBound && terminalFollowUpReady.await(5, TimeUnit.SECONDS))
                val ttyWinsizeHelperExecutionResponded = !ttyDiagnostic ||
                    (ttyWinsizeHelperBound && ttyWinsizeHelperExecutionReady.await(5, TimeUnit.SECONDS))
                val terminalAfterHelperResponded = !ttyDiagnostic ||
                    (ttyWinsizeHelperBound && terminalAfterHelperReady.await(5, TimeUnit.SECONDS))
                val terminalSafeFollowUpResponded = !ttySkipTerminalWinsizeReads ||
                    terminalSafeFollowUpReady.await(5, TimeUnit.SECONDS)
                val terminalStdout = synchronized(terminalOutput) {
                    terminalOutput.toString(Charsets.UTF_8.name())
                }
                // Read and erase the bounded Probe-private record before any
                // terminal cleanup command can issue a later ioctl.
                val diagnosticAtResize = if (ttyDiagnostic) readTtyDiagnosticSummary() else null
                val terminalTopology = TOPOLOGY_PATTERN.find(terminalStdout)
                    ?.groupValues
                    ?.drop(1)
                val expectedResizeChannel = if (ttyDiagnostic) {
                    "probe-proot-virtual-winsize"
                } else {
                    "unsupported"
                }
                val dynamicResizeCallSucceeded = terminalResizeAttempted && resizeErrorCode == null
                val dynamicSizeAfterResizeMatches =
                    terminalStdout.contains("terminal_after_resize=40x120")
                val initialSizeAfterResizeMatches =
                    terminalStdout.contains("terminal_after_resize=28x96")
                val dynamicSizeAfterHelperMatches =
                    terminalStdout.contains("terminal_after_helper=40x120")
                val initialSizeAfterHelperMatches =
                    terminalStdout.contains("terminal_after_helper=28x96")
                val repeatFirstMarkerOutcome = if (ttyDiagnostic && ttyResizeStress) {
                    TtyProbeMarkers.markerOutcome(
                        terminalStdout,
                        tag = "terminal_after_repeat_first",
                        expected = "24x80",
                    )
                } else {
                    TtyProbeMarkers.MISSING
                }
                val repeatSecondMarkerOutcome = if (ttyDiagnostic && ttyResizeStress) {
                    TtyProbeMarkers.markerOutcome(
                        terminalStdout,
                        tag = "terminal_after_repeat_second",
                        expected = "40x120",
                    )
                } else {
                    TtyProbeMarkers.MISSING
                }
                val stormMarkerOutcome = if (ttyDiagnostic && ttyResizeStress) {
                    TtyProbeMarkers.markerOutcome(
                        terminalStdout,
                        tag = "terminal_after_storm",
                        expected = "40x120",
                    )
                } else {
                    TtyProbeMarkers.MISSING
                }
                val ttyWinsizeHelperState = TTY_WINSIZE_STATE.find(terminalStdout)
                    ?.groupValues
                    ?.getOrNull(1)
                val ttyWinsizeHelperAvailableValue = TTY_WINSIZE_HELPER_AVAILABLE.find(terminalStdout)
                    ?.groupValues
                    ?.getOrNull(1) == "1"
                val ttyWinsizeHelperExecutedValue = TTY_WINSIZE_HELPER_EXECUTED.find(terminalStdout)
                    ?.groupValues
                    ?.getOrNull(1) == "1"
                val ttyWinsizeHelperAvailabilityResponded = !ttyDiagnostic ||
                    TTY_WINSIZE_HELPER_AVAILABLE.find(terminalStdout) != null
                val ttyWinsizeHelperResponded = !ttyDiagnostic ||
                    (ttyWinsizeHelperBound && TTY_WINSIZE_STATE.find(terminalStdout) != null)
                val ttyWinsizeHelperDiagnosticPassed = !ttyDiagnostic || (
                    ttyWinsizeHelperAvailabilityResponded &&
                        ttyWinsizeHelperAvailableValue &&
                        terminalFollowUpResponded &&
                        ttyWinsizeHelperExecutionResponded &&
                        ttyWinsizeHelperExecutedValue &&
                        ttyWinsizeHelperResponded &&
                        terminalAfterHelperResponded
                    )
                val resizeContractPassed = when (terminalResizeSupport) {
                    dev.alpine.runtime.api.RuntimeTerminalResizeSupport.DYNAMIC ->
                        dynamicResizeCallSucceeded && dynamicSizeAfterResizeMatches
                    dev.alpine.runtime.api.RuntimeTerminalResizeSupport.INITIAL_SIZE_ONLY ->
                        resizeErrorCode == dev.alpine.runtime.api.RuntimeErrorCode.TERMINAL_RESIZE_UNSUPPORTED &&
                            initialSizeAfterResizeMatches
                }
                val terminalWinchSignalCount = terminalWinchSignals.get()
                val relaySignalContractPassed = when {
                    !ttyDiagnostic -> true
                    terminalVirtualWinsize ->
                        TtyProbeMarkers.signalCountMatchesRelayMode(
                            terminalWinchRelayMode,
                            terminalWinchSignalCount,
                        ) && diagnosticAtResize?.virtualWinsizeVerified == true
                    terminalWinchForwarded -> terminalWinchReceived &&
                        diagnosticAtResize?.relayPathVerified == true
                    terminalTraceeForeground -> terminalWinchReceived &&
                        diagnosticAtResize?.traceeForegroundVerified == true
                    terminalWinchWithheld -> TtyProbeMarkers.signalCountMatchesRelayMode(
                        terminalWinchRelayMode,
                        terminalWinchSignalCount,
                    ) && diagnosticAtResize?.relayWithheldVerified == true
                    else -> false
                }
                val repeatedSignalContractPassed = when {
                    !ttyDiagnostic || !ttyResizeStress -> true
                    terminalVirtualWinsize -> TtyProbeMarkers.signalCountMatchesRelayMode(
                        terminalWinchRelayMode,
                        terminalWinchSignalCount,
                    )
                    terminalWinchExpected ->
                        terminalRepeatFirstWinchReceived && terminalRepeatSecondWinchReceived
                    terminalWinchWithheld -> TtyProbeMarkers.signalCountMatchesRelayMode(
                        terminalWinchRelayMode,
                        terminalWinchSignalCount,
                    )
                    else -> false
                }
                val stormSignalContractPassed = when {
                    !ttyDiagnostic || !ttyResizeStress -> true
                    terminalVirtualWinsize -> TtyProbeMarkers.signalCountMatchesRelayMode(
                        terminalWinchRelayMode,
                        terminalWinchSignalCount,
                    )
                    terminalWinchExpected -> terminalStormWinchReceived
                    terminalWinchWithheld -> TtyProbeMarkers.signalCountMatchesRelayMode(
                        terminalWinchRelayMode,
                        terminalWinchSignalCount,
                    )
                    else -> false
                }
                val repeatedResizeContractPassed = !(ttyDiagnostic && ttyResizeStress) || (
                    repeatFirstResizeError == null &&
                        repeatSecondResizeError == null &&
                        repeatedSignalContractPassed &&
                        terminalRepeatFirstResponded &&
                        terminalRepeatSecondResponded &&
                        repeatFirstMarkerOutcome == TtyProbeMarkers.MATCHED &&
                        repeatSecondMarkerOutcome == TtyProbeMarkers.MATCHED
                )
                val resizeStormContractPassed = !(ttyDiagnostic && ttyResizeStress) || (
                    stormResizeErrorCodes.all { it == null } &&
                        stormSignalContractPassed &&
                        terminalStormResponded &&
                        stormMarkerOutcome == TtyProbeMarkers.MATCHED &&
                        relaySignalContractPassed
                    )
                val resizeRelayContractPassed = relaySignalContractPassed
                val expectedTerminalMode = if (ttyDiagnostic) {
                    TtyDiagnosticRuntimePack.TERMINAL_MODE
                } else {
                    "native-pty"
                }
                val terminalModeNativePty = terminalStdout.contains(
                    "terminal=ok\r\nterminal_mode=$expectedTerminalMode",
                )
                val terminalResizeChannelMatches =
                    terminalStdout.contains("terminal_resize_channel=$expectedResizeChannel")
                val terminalResizeChannelAfterMatches =
                    terminalStdout.contains("terminal_resize_channel_after=$expectedResizeChannel")
                val terminalInitialSizeMatches = terminalStdout.contains("terminal_size=28 96")
                terminal.write("stty echo\n".toByteArray(Charsets.UTF_8)).toCompletableFuture().join()
                terminal.closeAsync().toCompletableFuture().join()
                val terminalExitEventObserved = synchronized(runtimeEvents) {
                    runtimeEvents.any { it.startsWith("TERMINAL_CLOSED:exit=") }
                }
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
                val expectedMachine = when (primaryAbi) {
                    "arm64-v8a" -> "aarch64"
                    "x86_64" -> "x86_64"
                    else -> ""
                }
                val commandProbeOk = execution.exitCode == 0 && stdout.contains("probe=ok")
                val guestMachineMatchesPrimaryAbi = stdout.contains("machine=$expectedMachine")
                val guestAlpineReleasePresent = stdout.contains("alpine=3.21.3")
                val restartProbeOk = restartExecution.exitCode == 0 && restartStdout.contains("restart=ok")
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
                        commandProbeOk &&
                            guestMachineMatchesPrimaryAbi &&
                            guestAlpineReleasePresent &&
                            terminalResponded &&
                            terminalResizeResponded &&
                            terminalResizeCommandResponded &&
                            terminalTopologyResponded &&
                            terminalTopology != null &&
                            resizeContractPassed &&
                            terminalPrompted &&
                            terminalEchoDisabled &&
                            ttyWinsizeHelperDiagnosticPassed &&
                            terminalModeNativePty &&
                            terminalResizeChannelMatches &&
                            terminalResizeChannelAfterMatches &&
                            terminalInitialSizeMatches &&
                            repeatedResizeContractPassed &&
                            resizeStormContractPassed &&
                            terminalExitEventObserved &&
                            restartProbeOk &&
                            (!ttyDiagnostic || diagnosticAtResize?.topologyVerified == true) &&
                            (!ttyDiagnostic || diagnosticAtResize?.hostSignalRecorderVerified == true) &&
                            (!ttyDiagnostic || hostPtyResizeControlPassed) &&
                            resizeRelayContractPassed &&
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
                    .put("command_probe_ok", commandProbeOk)
                    .put("guest_machine_matches_primary_abi", guestMachineMatchesPrimaryAbi)
                    .put("guest_alpine_release_present", guestAlpineReleasePresent)
                    .put("stderr_empty", execution.standardError.isEmpty())
                    .put("terminal_responded", terminalResponded)
                    .put("terminal_resize_responded", terminalResizeResponded)
                    .put("terminal_resize_command_responded", terminalResizeCommandResponded)
                    .put("terminal_resize_recovery_attempted", terminalResizeRecoveryAttempted)
                    .put("terminal_resize_recovery_responded", terminalResizeRecoveryResponded)
                    .put("terminal_resize_support", terminalResizeSupport.name)
                    .put(
                        "tty_proot_seccomp_mode",
                        if (ttyDiagnostic && ttyDisableProotSeccomp) "disabled_probe" else "default",
                    )
                    .put("tty_virtual_winsize_no_write_active", ttyDiagnostic && ttyVirtualWinsizeNoWrite)
                    .put("tty_virtual_winsize_no_request_active", ttyDiagnostic && ttyVirtualWinsizeNoRequest)
                    .put("tty_virtual_winsize_skip_resize_active", ttyDiagnostic && ttyVirtualWinsizeSkipResize)
                    .put("tty_primary_tracee_foreground_disabled_active", ttyDiagnostic && ttyDisablePrimaryTraceeForeground)
                    .put("tty_patched_proot_active", ttyDiagnostic && ttyUsePatchedProot)
                    .put("tty_terminal_winsize_reads_skipped_active", ttySkipTerminalWinsizeReads)
                    .put("terminal_winch_relay_mode", terminalWinchRelayMode)
                    .put("terminal_winch_expected", terminalWinchExpected)
                    .put("terminal_resize_command_after_winch", terminalResizeCommandAfterWinch)
                    .put("terminal_resize_attempted", terminalResizeAttempted)
                    .put("terminal_resize_error", resizeErrorCode?.name)
                    .put("terminal_dynamic_resize_call_succeeded", dynamicResizeCallSucceeded)
                    .put("terminal_dynamic_size_after_resize_matches", dynamicSizeAfterResizeMatches)
                    .put("terminal_initial_size_after_resize_matches", initialSizeAfterResizeMatches)
                    .put("terminal_dynamic_size_after_helper_matches", dynamicSizeAfterHelperMatches)
                    .put("terminal_initial_size_after_helper_matches", initialSizeAfterHelperMatches)
                    .put("terminal_resize_contract_passed", resizeContractPassed)
                    .put("terminal_repeated_resize_contract_passed", repeatedResizeContractPassed)
                    .put("terminal_repeat_first_responded", terminalRepeatFirstResponded)
                    .put("terminal_repeat_second_responded", terminalRepeatSecondResponded)
                    .put("terminal_repeat_first_winch_received", terminalRepeatFirstWinchReceived)
                    .put("terminal_repeat_second_winch_received", terminalRepeatSecondWinchReceived)
                    .put("terminal_repeat_first_marker", repeatFirstMarkerOutcome)
                    .put("terminal_repeat_second_marker", repeatSecondMarkerOutcome)
                    .put("terminal_resize_storm_contract_passed", resizeStormContractPassed)
                    .put("terminal_resize_storm_responded", terminalStormResponded)
                    .put("terminal_resize_storm_winch_received", terminalStormWinchReceived)
                    .put("terminal_resize_storm_marker", stormMarkerOutcome)
                    .put("terminal_winch_signal_count", terminalWinchSignalCount)
                    .put("terminal_virtual_winsize_probe", terminalVirtualWinsize)
                    .put(
                        "terminal_virtual_winsize_control_verified",
                        diagnosticAtResize?.virtualWinsizeVerified ?: !ttyDiagnostic,
                    )
                    .put(
                        "terminal_resize_storm_request_count",
                        if (ttyDiagnostic && ttyResizeStress) stormSteps.size else 0,
                    )
                    .put("host_pty_resize_control_passed", hostPtyResizeControlPassed)
                    .put("tty_winsize_helper_bound", ttyWinsizeHelperBound)
                    .put("terminal_follow_up_responded", terminalFollowUpResponded)
                    .put("tty_winsize_helper_availability_responded", ttyWinsizeHelperAvailabilityResponded)
                    .put("tty_winsize_helper_available", ttyWinsizeHelperAvailableValue)
                    .put("tty_winsize_helper_execution_responded", ttyWinsizeHelperExecutionResponded)
                    .put("tty_winsize_helper_executed", ttyWinsizeHelperExecutedValue)
                    .put("tty_winsize_helper_responded", ttyWinsizeHelperResponded)
                    .put("terminal_after_helper_responded", terminalAfterHelperResponded)
                    .put("terminal_safe_follow_up_responded", terminalSafeFollowUpResponded)
                    .put("tty_winsize_helper_state", ttyWinsizeHelperState ?: JSONObject.NULL)
                    .put("terminal_prompted", terminalPrompted)
                    .put("terminal_echo_disabled_for_probe", terminalEchoDisabled)
                    .put("terminal_winch_armed", terminalWinchArmed)
                    .put("terminal_winch_received", terminalWinchReceived)
                    .put("terminal_resize_relay_contract_passed", resizeRelayContractPassed)
                    .put("terminal_mode_native_pty", terminalModeNativePty)
                    .put("terminal_resize_channel_matches", terminalResizeChannelMatches)
                    .put("terminal_resize_channel_after_matches", terminalResizeChannelAfterMatches)
                    .put("terminal_initial_size_matches", terminalInitialSizeMatches)
                    .put("terminal_topology_responded", terminalTopologyResponded)
                    .put("terminal_tty_attached", terminalTopology?.getOrNull(0) == "1")
                    .put("terminal_pgrp_is_foreground", terminalTopology?.getOrNull(1) == "1")
                    .put("terminal_pgrp_is_session_leader", terminalTopology?.getOrNull(2) == "1")
                    .put("terminal_exit_event_observed", terminalExitEventObserved)
                    .put("restart_probe_ok", restartProbeOk)
                    .put(
                        "tty_diagnostic",
                        diagnosticAtResize?.toJson() ?: JSONObject().put("record_present", false),
                    )
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
            // Fixed class names distinguish launcher/process failures without
            // persisting a provider/native exception message, command, path,
            // PID, or terminal output.
            val errorClasses = generateSequence(error) { it.cause }
                .map { it.javaClass.simpleName }
                .filter { it.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}")) }
                .take(4)
                .toList()
            base
                .put("success", false)
                .put("error_type", safeError?.javaClass?.simpleName ?: "ProbeFailure")
                .put("error", safeError?.errorCode?.name ?: "RUNTIME_PROBE_FAILED")
                .put("error_classes", errorClasses.joinToString(","))
        }.put("elapsed_ms", System.currentTimeMillis() - startedAt)
    }

    /**
     * Runs a self-contained native control that has no access to the app
     * terminal stream. Its only accepted output is one fixed PASS marker;
     * output is bounded and never persisted in the Probe report.
     */
    private fun runHostPtyResizeControl(control: File): Boolean {
        if (!control.isFile || !control.canExecute() ||
            control.length() !in 1L..MAX_HOST_PTY_RESIZE_CONTROL_BYTES.toLong()) {
            return false
        }
        return runCatching {
            val process = ProcessBuilder(control.absolutePath)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                false
            } else {
                val output = process.inputStream.use { input ->
                    val bounded = ByteArrayOutputStream()
                    val buffer = ByteArray(64)
                    while (bounded.size() < MAX_HOST_PTY_RESIZE_CONTROL_OUTPUT_BYTES) {
                        val count = input.read(
                            buffer,
                            0,
                            minOf(buffer.size, MAX_HOST_PTY_RESIZE_CONTROL_OUTPUT_BYTES - bounded.size()),
                        )
                        if (count < 0) break
                        bounded.write(buffer, 0, count)
                    }
                    bounded.toString(Charsets.US_ASCII.name())
                }
                process.exitValue() == 0 && output == "host_resize_control=PASS\n"
            }
        }.getOrDefault(false)
    }

    private val RESULT_FILE: File
        get() = File(filesDir, "runtime-probe-result.json")

    /** Probe-only fixed-size marker; never interpolates terminal output or user input. */
    private fun terminalSizeMarkerCommand(
        tag: String,
        rows: Int,
        columns: Int,
        skipWinsizeRead: Boolean = false,
    ): String {
        require(tag.matches(Regex("terminal_after_[a-z_]+")))
        require(rows in 1..1_000 && columns in 1..1_000)
        val commandAcknowledgement = if (tag == "terminal_after_resize") {
            "printf 'terminal_after_resize_command_dispatched\\n'; "
        } else {
            ""
        }
        if (skipWinsizeRead) return commandAcknowledgement + "printf '$tag=skipped\\n'\n"
        return commandAcknowledgement + "size=\"\$(stty size)\"; " +
            "if [ \"\$size\" = '$rows $columns' ]; then " +
            "printf '$tag=${rows}x${columns}\\n'; " +
            "else printf '$tag=unexpected\\n'; fi\\n"
    }

    private fun readTtyDiagnosticSummary(): TtyDiagnosticSummary? {
        val file = File(cacheDir, TTY_DIAGNOSTIC_FILE_NAME)
        return try {
            if (!file.isFile || file.length() !in 1..MAX_TTY_DIAGNOSTIC_BYTES.toLong()) return null
            val bytes = file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_TTY_DIAGNOSTIC_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val diagnosticText = bytes.toString(Charsets.US_ASCII)
            val winchStages = diagnosticText
                .lineSequence()
                .mapNotNull { line -> TTY_SIGNAL_DIAGNOSTIC_RECORD.matchEntire(line)?.groupValues?.get(1) }
                .toList()
            val records = diagnosticText
                .lineSequence()
                .mapNotNull { line -> TTY_DIAGNOSTIC_RECORD.matchEntire(line) }
                .map { match ->
                    TtyDiagnosticRecord(
                        event = if (match.groupValues[1] == "TIOCGWINSZ") {
                            TtyDiagnosticEvent.GET_WINSIZE
                        } else {
                            TtyDiagnosticEvent.SET_WINSIZE
                        },
                        stage = if (match.groupValues[2] == "before_extensions") {
                            TtyDiagnosticStage.BEFORE_EXTENSIONS
                        } else {
                            TtyDiagnosticStage.AFTER_EXTENSIONS
                        },
                        traceeKind = match.groupValues[3],
                        fdIsTty = match.groupValues[4] == "1",
                        sameSlaveDevice = match.groupValues[5] == "1",
                        rows = match.groupValues[6].toInt(),
                        columns = match.groupValues[7].toInt(),
                        result = match.groupValues[8].toInt(),
                    )
                }
                .toList()
            // A source-level bootstrap failure can occur before the first
            // traced ioctl. Retain only its fixed signal/topology stages so
            // the Probe reports that failure instead of collapsing it into an
            // indistinguishable missing diagnostic file.
            if (records.isEmpty() && winchStages.isEmpty()) return null
            TtyDiagnosticSummary(records, winchStages)
        } finally {
            file.delete()
        }
    }

    private data class TtyDiagnosticRecord(
        val event: TtyDiagnosticEvent,
        val stage: TtyDiagnosticStage,
        /** Fixed source-derived class only; never a path, PID, or command. */
        val traceeKind: String,
        val fdIsTty: Boolean,
        val sameSlaveDevice: Boolean,
        val rows: Int,
        val columns: Int,
        val result: Int,
    )

    private enum class TtyDiagnosticEvent {
        GET_WINSIZE,
        SET_WINSIZE,
    }

    private enum class TtyDiagnosticStage {
        BEFORE_EXTENSIONS,
        AFTER_EXTENSIONS,
    }

    private data class TtyDiagnosticSummary(
        val records: List<TtyDiagnosticRecord>,
        /** Closed Probe-local enum; never a PID, path, command, or terminal payload. */
        val winchStages: List<String>,
    ) {
        private val matchingPtyPairs = records.zipWithNext()
            .mapIndexedNotNull { index, (before, after) ->
                if (
                    before.event == TtyDiagnosticEvent.GET_WINSIZE &&
                    after.event == TtyDiagnosticEvent.GET_WINSIZE &&
                    before.stage == TtyDiagnosticStage.BEFORE_EXTENSIONS &&
                    after.stage == TtyDiagnosticStage.AFTER_EXTENSIONS &&
                    before.fdIsTty && after.fdIsTty &&
                    before.sameSlaveDevice && after.sameSlaveDevice &&
                    before.traceeKind == after.traceeKind &&
                    before.result == 0 && after.result == 0
                ) {
                    TtyDiagnosticPair(afterIndex = index + 1, before = before, after = after)
                } else {
                    null
                }
            }
        private val effective: TtyDiagnosticRecord? = matchingPtyPairs.lastOrNull()?.after ?: records.lastOrNull()
        private val requestedResizePair: TtyDiagnosticPair? = matchingPtyPairs.lastOrNull {
            it.before.rows == 40 && it.before.columns == 120
        }
        private fun countPairs(
            traceeKind: String,
            rows: Int,
            columns: Int,
        ): Int = matchingPtyPairs.count { pair ->
            pair.after.traceeKind == traceeKind &&
                pair.after.rows == rows &&
                pair.after.columns == columns
        }
        private fun sizeClass(pair: TtyDiagnosticPair): String = when {
            pair.after.rows == 28 && pair.after.columns == 96 -> "initial"
            pair.after.rows == 40 && pair.after.columns == 120 -> "dynamic"
            else -> "other"
        }
        private val safeGetSequence: String = matchingPtyPairs.joinToString(",") { pair ->
            "${pair.after.traceeKind}:${sizeClass(pair)}"
        }
        private fun countWinchStage(stage: String): Int = winchStages.count { it == stage }

        val topologyVerified: Boolean
            get() = matchingPtyPairs.isNotEmpty()

        val hostSignalRecorderVerified: Boolean
            get() = countWinchStage("host_self_test_dispatched") == 1 &&
                countWinchStage("host_received") >= 1

        /**
         * The Probe session supervisor must reach its direct PRoot child, and
         * PRoot must relay only to its internally known primary tracee. The
         * tracee signal stop/restart pair proves ptrace reinjection. Counts
         * intentionally omit PIDs, group IDs and terminal payload. This is an
         * initial-shell diagnostic, not production TUI proof.
         */
        val relayPathVerified: Boolean
            get() = countWinchStage("relay_supervisor_direct_proot_dispatched") >= 1 &&
                countWinchStage("relay_primary_tracee_dispatched") >= 1 &&
                countWinchStage("tracee_stopped") >= 1 &&
                countWinchStage("tracee_restarted") >= 1

        /**
         * relay17 isolation must prove that the supervisor reached direct PRoot
         * while PRoot deliberately did not inject SIGWINCH into a guest tracee.
         * All values are fixed stage counts; no terminal payload is retained.
         */
        val relayWithheldVerified: Boolean
            get() = countWinchStage("relay_supervisor_direct_proot_dispatched") >= 1 &&
                countWinchStage("relay_primary_tracee_withheld") >= 1 &&
                countWinchStage("relay_primary_tracee_dispatched") == 0 &&
                countWinchStage("tracee_stopped") == 0 &&
                countWinchStage("tracee_restarted") == 0

        /**
         * relay21 is source-level terminal topology, not a host-side signal
         * workaround. The session supervisor acknowledges the fixed resize
         * control byte without signalling PRoot; PRoot moves only its known
         * initial tracee group to the tty foreground group, verifies that
         * relation, and confirms that the active same-PTY guest tracee remains
         * in that physical foreground group. The direct PRoot recorder must
         * then see only its self-test while the guest trap is delivered through
         * normal ptrace stop/restart flow.
         */
        val traceeForegroundVerified: Boolean
            get() = countWinchStage("primary_tracee_foreground_assigned") >= 1 &&
                countWinchStage("primary_tracee_foreground_verified") >= 1 &&
                countWinchStage("primary_tracee_foreground_mismatch") == 0 &&
                countWinchStage("primary_tracee_foreground_unavailable") == 0 &&
                countWinchStage("active_tty_tracee_foreground") >= 1 &&
                countWinchStage("active_tty_tracee_background") == 0 &&
                countWinchStage("relay_supervisor_resize_acknowledged") >= 1 &&
                countWinchStage("host_self_test_dispatched") == 1 &&
                countWinchStage("host_received") == 1 &&
                countWinchStage("tracee_stopped") >= 1 &&
                countWinchStage("tracee_restarted") >= 1

        /**
         * Relay24 stores dimensions in a private inherited memfd and lets
         * PRoot consume the fixed four-byte value only on a guest TIOCGWINSZ
         * exit. No PRoot thread or post-launch signal is used.
         * The later `stty` markers are evaluated outside this parser.
         */
        val virtualWinsizeVerified: Boolean
            get() = countWinchStage("virtual_winsize_supervisor_stored") >= 1 &&
                countWinchStage("virtual_winsize_fd_ready") == 1 &&
                countWinchStage("virtual_winsize_read_applied") >= 1 &&
                countWinchStage("host_self_test_dispatched") == 1 &&
                countWinchStage("host_received") == 1 &&
                countWinchStage("tracee_stopped") == 0 &&
                countWinchStage("tracee_restarted") == 0

        private val requestedResizePostExtensionMatches: Boolean
            get() = requestedResizePair?.after?.let { it.rows == 40 && it.columns == 120 } == true
        private val requestedResizePostExtensionReverted: Boolean
            get() = requestedResizePair?.after?.let { it.rows == 28 && it.columns == 96 } == true
        private val initialWinsizeSetAfterRequestedResize: Boolean
            get() = requestedResizePair?.let { pair ->
                records.drop(pair.afterIndex + 1).any { record ->
                    record.event == TtyDiagnosticEvent.SET_WINSIZE &&
                        record.fdIsTty &&
                        record.sameSlaveDevice &&
                        record.result == 0 &&
                        record.rows == 28 &&
                        record.columns == 96
                }
            } == true

        fun toJson(): JSONObject = JSONObject()
            .put("record_present", records.isNotEmpty() || winchStages.isNotEmpty())
            .put("event_count", records.size)
            .put("matching_pty_ioctl_pair_count", matchingPtyPairs.size)
            .put("busybox_initial_pty_ioctl_pair_count", countPairs("busybox", 28, 96))
            .put("busybox_dynamic_pty_ioctl_pair_count", countPairs("busybox", 40, 120))
            .put("tty_helper_initial_pty_ioctl_pair_count", countPairs("tty_helper", 28, 96))
            .put("tty_helper_dynamic_pty_ioctl_pair_count", countPairs("tty_helper", 40, 120))
            .put("matching_pty_get_sequence", safeGetSequence)
            .put("fd_is_tty", effective?.fdIsTty ?: false)
            .put("same_slave_device", effective?.sameSlaveDevice ?: false)
            .put("ioctl_success", effective?.result == 0)
            .put("topology_verified", topologyVerified)
            .put("requested_resize_pair_present", requestedResizePair != null)
            .put("requested_resize_post_extension_matches", requestedResizePostExtensionMatches)
            .put("requested_resize_post_extension_reverted", requestedResizePostExtensionReverted)
            .put("initial_winsize_set_after_requested_resize", initialWinsizeSetAfterRequestedResize)
            .put("winch_host_self_test_dispatched_count", countWinchStage("host_self_test_dispatched"))
            .put("winch_host_received_count", countWinchStage("host_received"))
            .put("winch_relay_requested_count", countWinchStage("relay_requested"))
            .put(
                "winch_relay_foreground_dispatched_count",
                countWinchStage("relay_foreground_dispatched"),
            )
            .put(
                "winch_relay_foreground_unavailable_count",
                countWinchStage("relay_foreground_unavailable"),
            )
            .put(
                "winch_relay_supervisor_foreground_dispatched_count",
                countWinchStage("relay_supervisor_foreground_dispatched"),
            )
            .put(
                "winch_relay_supervisor_foreground_unavailable_count",
                countWinchStage("relay_supervisor_foreground_unavailable"),
            )
            .put(
                "winch_relay_supervisor_direct_proot_dispatched_count",
                countWinchStage("relay_supervisor_direct_proot_dispatched"),
            )
            .put(
                "winch_relay_supervisor_direct_proot_unavailable_count",
                countWinchStage("relay_supervisor_direct_proot_unavailable"),
            )
            .put(
                "winch_relay_primary_tracee_dispatched_count",
                countWinchStage("relay_primary_tracee_dispatched"),
            )
            .put(
                "winch_relay_primary_tracee_unavailable_count",
                countWinchStage("relay_primary_tracee_unavailable"),
            )
            .put(
                "winch_relay_primary_tracee_withheld_count",
                countWinchStage("relay_primary_tracee_withheld"),
            )
            .put(
                "winch_relay_supervisor_resize_acknowledged_count",
                countWinchStage("relay_supervisor_resize_acknowledged"),
            )
            .put(
                "winch_primary_tracee_foreground_assigned_count",
                countWinchStage("primary_tracee_foreground_assigned"),
            )
            .put(
                "winch_primary_tracee_foreground_verified_count",
                countWinchStage("primary_tracee_foreground_verified"),
            )
            .put(
                "winch_primary_tracee_foreground_mismatch_count",
                countWinchStage("primary_tracee_foreground_mismatch"),
            )
            .put(
                "winch_primary_tracee_foreground_unavailable_count",
                countWinchStage("primary_tracee_foreground_unavailable"),
            )
            .put(
                "winch_active_tty_tracee_foreground_count",
                countWinchStage("active_tty_tracee_foreground"),
            )
            .put(
                "winch_active_tty_tracee_background_count",
                countWinchStage("active_tty_tracee_background"),
            )
            .put(
                "virtual_winsize_supervisor_stored_count",
                countWinchStage("virtual_winsize_supervisor_stored"),
            )
            .put(
                "virtual_winsize_supervisor_no_write_control_count",
                countWinchStage("virtual_winsize_supervisor_no_write_control"),
            )
            .put("virtual_winsize_fd_ready_count", countWinchStage("virtual_winsize_fd_ready"))
            .put("virtual_winsize_read_applied_count", countWinchStage("virtual_winsize_read_applied"))
            .put("winch_tracee_stopped_count", countWinchStage("tracee_stopped"))
            .put("winch_tracee_restarted_count", countWinchStage("tracee_restarted"))
            .put("winch_host_signal_recorder_verified", hostSignalRecorderVerified)
            .put("winch_relay_path_verified", relayPathVerified)
            .put("winch_relay_withheld_verified", relayWithheldVerified)
            .put("winch_tracee_foreground_verified", traceeForegroundVerified)
            .put("winch_virtual_winsize_verified", virtualWinsizeVerified)
    }

    private data class TtyDiagnosticPair(
        val afterIndex: Int,
        val before: TtyDiagnosticRecord,
        val after: TtyDiagnosticRecord,
    )

    companion object {
        private val TOPOLOGY_PATTERN = Regex("terminal_tty_topology=([01]),([01]),([01])")
        private val TTY_DIAGNOSTIC_RECORD = Regex(
            "schema=1 event=(TIOCGWINSZ|TIOCSWINSZ) stage=(before_extensions|after_extensions) " +
                "tracee_kind=(busybox|tty_helper|other) fd_is_tty=([01]) " +
                "fd_rdev=[0-9]{1,20} expected_rdev=[0-9]{1,20} same_rdev=([01]) " +
                "rows=([0-9]{1,4}) columns=([0-9]{1,4}) result=(-?[0-9]{1,6})",
        )
        private val TTY_SIGNAL_DIAGNOSTIC_RECORD = Regex(
            "schema=1 event=(?:SIGWINCH|VIRTUAL_WINSIZE) stage=(host_self_test_dispatched|host_received|relay_requested|" +
                "relay_foreground_dispatched|relay_foreground_unavailable|" +
                "relay_supervisor_foreground_dispatched|relay_supervisor_foreground_unavailable|" +
                "relay_supervisor_direct_proot_dispatched|relay_supervisor_direct_proot_unavailable|" +
                "relay_primary_tracee_dispatched|relay_primary_tracee_unavailable|relay_primary_tracee_withheld|" +
                "relay_supervisor_resize_acknowledged|primary_tracee_foreground_assigned|" +
                "primary_tracee_foreground_verified|primary_tracee_foreground_mismatch|" +
                "primary_tracee_foreground_unavailable|" +
                "active_tty_tracee_foreground|active_tty_tracee_background|" +
                "virtual_winsize_supervisor_stored|virtual_winsize_supervisor_no_write_control|" +
                "virtual_winsize_fd_ready|virtual_winsize_read_applied|" +
                "tracee_stopped|tracee_restarted)",
        )
        private const val TTY_DIAGNOSTIC_FILE_NAME = "proot-tty-diagnostic.log"
        private const val MAX_TTY_DIAGNOSTIC_BYTES = 16 * 1024
        private const val TTY_WINSIZE_HELPER_FILE_NAME = "tty-winsize-probe"
        private const val MAX_TTY_WINSIZE_HELPER_BYTES = 512 * 1024
        private const val MAX_HOST_PTY_RESIZE_CONTROL_BYTES = 128 * 1024
        private const val MAX_HOST_PTY_RESIZE_CONTROL_OUTPUT_BYTES = 256
        private val TTY_WINSIZE_STATE = Regex(
            "tty_winsize_state=(dynamic|initial|unexpected|unavailable)",
        )
        private val TTY_WINSIZE_HELPER_AVAILABLE = Regex("tty_winsize_helper_available=([01])")
        private val TTY_WINSIZE_HELPER_EXECUTED = Regex("tty_winsize_helper_executed=([01])")
        private const val MAX_PROBE_WINCH_SIGNALS = 11
        const val EXTRA_AUTO_RUN = "auto_run"
        const val EXTRA_RESET_AFTER = "reset_after"
        const val EXTRA_TTY_DIAGNOSTIC = "tty_diagnostic"
        const val EXTRA_TTY_RESIZE_STRESS = "tty_resize_stress"
        const val EXTRA_TTY_DISABLE_PROOT_SECCOMP = "tty_disable_proot_seccomp"
        const val EXTRA_TTY_VIRTUAL_WINSIZE_NO_WRITE = "tty_virtual_winsize_no_write"
        const val EXTRA_TTY_VIRTUAL_WINSIZE_NO_REQUEST = "tty_virtual_winsize_no_request"
        const val EXTRA_TTY_VIRTUAL_WINSIZE_SKIP_RESIZE = "tty_virtual_winsize_skip_resize"
        const val EXTRA_TTY_DISABLE_PRIMARY_TRACEE_FOREGROUND = "tty_disable_primary_tracee_foreground"
        const val EXTRA_TTY_USE_PATCHED_PROOT = "tty_use_patched_proot"
        const val EXTRA_TTY_SKIP_TERMINAL_WINSIZE_READS = "tty_skip_terminal_winsize_reads"
    }
}
