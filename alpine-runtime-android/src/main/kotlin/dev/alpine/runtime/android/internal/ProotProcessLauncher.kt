package dev.alpine.runtime.android.internal

import android.os.Process as AndroidProcess
import dev.alpine.runtime.api.RuntimeCommandRequest
import dev.alpine.runtime.api.RuntimeCommandResult
import dev.alpine.runtime.api.RuntimeEnvironmentContext
import dev.alpine.runtime.api.RuntimeEnvironmentContributor
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeHostProcessEvent
import dev.alpine.runtime.api.RuntimeHostProcessEventKind
import dev.alpine.runtime.api.RuntimeHostProcessListener
import dev.alpine.runtime.api.RuntimeProcessInfo
import dev.alpine.runtime.api.RuntimeOperationException
import dev.alpine.runtime.api.RuntimeSubscription
import dev.alpine.runtime.api.RuntimeTerminalOutputListener
import dev.alpine.runtime.api.RuntimeTerminalRequest
import dev.alpine.runtime.api.RuntimeTerminalResizeSupport
import dev.alpine.runtime.api.RuntimeTerminalSession
import dev.alpine.runtime.api.RuntimeTerminalSignal
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Stream

internal class ProotProcessLauncher(
    private val cacheDirectory: File,
    private val environmentContributors: List<RuntimeEnvironmentContributor>,
    private val processListener: RuntimeHostProcessListener,
    private val maxOutputBytes: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val processes = ConcurrentHashMap<Long, ProcessRecord>()
    private val processHandles = AtomicLong(1)
    private val activeSessions = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleLock = Any()

    fun openSession(sessionId: String) {
        synchronized(lifecycleLock) { activeSessions += sessionId }
    }

    fun execute(
        runtime: InstalledRuntime,
        sessionId: String,
        sessionEnvironment: Map<String, String>,
        request: RuntimeCommandRequest,
        isCancelled: () -> Boolean = { false },
    ): RuntimeCommandResult {
        if (!isSessionActive(sessionId)) {
            throw RuntimeOperationException(RuntimeErrorCode.PROCESS_EXITED)
        }
        val startedAt = clock()
        val pid = processHandles.getAndIncrement()
        val guestPidDirectory = File(runtime.workspaceDirectory, ".alpine-runtime/processes")
            .apply { mkdirs() }
        val guestPidFile = File(guestPidDirectory, "process-$pid.pid")
        val guestPidPath = "/workspace/.alpine-runtime/processes/${guestPidFile.name}"
        val command = mutableListOf(
            runtime.launcher.absolutePath,
            "-0",
            "--kill-on-exit",
            "--link2symlink",
            "-r",
            runtime.rootfsDirectory.absolutePath,
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-b",
            "${runtime.workspaceDirectory.absolutePath}:/workspace",
            "-w",
            request.workingDirectory,
            "/bin/sh",
            "-c",
            GUEST_PROCESS_WRAPPER,
            "alpine-runtime-command",
            guestPidPath,
            request.executable,
        )
        command.addAll(request.arguments)
        val process = try {
            ProcessBuilder(command)
                .directory(runtime.workspaceDirectory)
                .redirectErrorStream(false)
                .apply {
                    val guestEnvironment = linkedMapOf<String, String>()
                    guestEnvironment += sessionEnvironment
                    val context = RuntimeEnvironmentContext(
                        sessionId = sessionId,
                        workspacePath = "/workspace",
                    )
                    environmentContributors.forEach { contributor ->
                        guestEnvironment += contributor.contribute(context)
                    }
                    guestEnvironment += request.environment
                    validateGuestEnvironment(guestEnvironment)
                    environment()["TERM"] = "xterm-256color"
                    environment()["LANG"] = "C.UTF-8"
                    environment()["HOME"] = "/root"
                    environment()["PATH"] = "/usr/local/bin:/usr/bin:/bin"
                    environment()["PROOT_TMP_DIR"] = File(cacheDirectory, "proot-tmp")
                        .apply { mkdirs() }
                        .absolutePath
                    environment()["LD_LIBRARY_PATH"] = runtime.launcher.parentFile?.absolutePath.orEmpty()
                    environment()["PROOT_LOADER"] = runtime.loader.absolutePath
                    environment().putAll(guestEnvironment)
                }
                .start()
        } catch (error: RuntimeOperationException) {
            throw error
        } catch (_: Exception) {
            throw RuntimeOperationException(RuntimeErrorCode.PROCESS_START_FAILED)
        }
        // Android's public java.lang.Process stub does not expose the child PID consistently.
        // Use a stable runtime-local handle for lifecycle correlation.
        val registered = synchronized(lifecycleLock) {
            if (sessionId !in activeSessions) {
                false
            } else {
                processes[pid] = ProcessRecord(
                    process,
                    sessionId,
                    request.executable,
                    startedAt,
                    guestPidFile,
                )
                notifyProcess(RuntimeHostProcessEventKind.STARTED, sessionId, pid)
                true
            }
        }
        if (!registered) {
            terminateProcess(process, guestPidFile)
            throw RuntimeOperationException(RuntimeErrorCode.PROCESS_EXITED)
        }
        val stdout = LimitedOutputCollector(process.inputStream, maxOutputBytes)
        val stderr = LimitedOutputCollector(process.errorStream, maxOutputBytes)
        val stdoutThread = Thread(stdout, "alpine-runtime-stdout-$pid").apply {
            isDaemon = true
            start()
        }
        val stderrThread = Thread(stderr, "alpine-runtime-stderr-$pid").apply {
            isDaemon = true
            start()
        }
        try {
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis)
            var completed = false
            var cancelled = false
            while (!completed) {
                if (isCancelled()) {
                    cancelled = true
                    break
                }
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) break
                val waitMillis = minOf(
                    CANCEL_POLL_MILLIS,
                    maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)),
                )
                completed = try {
                    process.waitFor(waitMillis, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelled = true
                    false
                }
            }
            if (!completed) {
                terminateProcess(process, guestPidFile)
            }
            if (cancelled) throw CancellationException()
            joinCollector(stdoutThread)
            joinCollector(stderrThread)
            return RuntimeCommandResult(
                exitCode = if (completed) process.exitValue() else -1,
                standardOutput = stdout.bytes(),
                standardError = stderr.bytes(),
                durationMillis = clock() - startedAt,
                timedOut = !completed,
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
            joinCollector(stdoutThread)
            joinCollector(stderrThread)
            val removed = synchronized(lifecycleLock) { processes.remove(pid) }
            if (removed != null) {
                notifyProcess(RuntimeHostProcessEventKind.STOPPED, sessionId, pid)
            }
            guestPidFile.delete()
        }
    }

    fun openTerminal(
        runtime: InstalledRuntime,
        sessionId: String,
        sessionEnvironment: Map<String, String>,
        request: RuntimeTerminalRequest,
        onClosed: () -> Unit = {},
    ): RuntimeTerminalSession {
        if (!isSessionActive(sessionId)) {
            throw RuntimeOperationException(RuntimeErrorCode.PROCESS_EXITED)
        }
        val startedAt = clock()
        val pid = processHandles.getAndIncrement()
        val guestPidDirectory = File(runtime.workspaceDirectory, ".alpine-runtime/processes")
            .apply { mkdirs() }
        val guestPidFile = File(guestPidDirectory, "terminal-$pid.pid")
        val guestPidPath = "/workspace/.alpine-runtime/processes/${guestPidFile.name}"
        val resizeControlId = UUID.randomUUID().toString()
        val guestResizeFifoFile = File(guestPidDirectory, "terminal-$resizeControlId.resize")
        val guestResizeReadyFile = File(guestPidDirectory, "terminal-$resizeControlId.resize.ready")
        val guestResizeAckFile = File(guestPidDirectory, "terminal-$resizeControlId.resize.ack")
        val guestResizeStateFile = File(guestPidDirectory, "terminal-$resizeControlId.resize.state")
        val command = listOf(
            runtime.launcher.absolutePath,
            "-0",
            "--kill-on-exit",
            "--link2symlink",
            "-r",
            runtime.rootfsDirectory.absolutePath,
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-b",
            "${runtime.workspaceDirectory.absolutePath}:/workspace",
            "-w",
            request.workingDirectory,
            "/bin/sh",
            "-c",
            GUEST_TERMINAL_WRAPPER,
            "alpine-runtime-terminal",
            guestPidPath,
            request.shell,
            request.columns.toString(),
            request.rows.toString(),
        )
        val resolvedEnvironment = guestEnvironment(
            sessionId = sessionId,
            sessionEnvironment = sessionEnvironment,
            requestEnvironment = request.environment,
        )
        val terminalProcess = try {
            launchTerminalProcess(
                runtime,
                request,
                command,
                resolvedEnvironment,
                guestResizeFifoFile,
                guestResizeReadyFile,
                guestResizeAckFile,
                guestResizeStateFile,
                guestPidFile,
            )
        } catch (error: RuntimeOperationException) {
            throw error
        } catch (_: Exception) {
            throw RuntimeOperationException(RuntimeErrorCode.PROCESS_START_FAILED)
        }
        val process = terminalProcess.process
        val registered = synchronized(lifecycleLock) {
            if (sessionId !in activeSessions) {
                false
            } else {
                processes[pid] = ProcessRecord(
                    process = process,
                    sessionId = sessionId,
                    command = request.shell,
                    startedAt = startedAt,
                    guestPidFile = guestPidFile,
                )
                notifyProcess(RuntimeHostProcessEventKind.STARTED, sessionId, pid)
                true
            }
        }
        if (!registered) {
            terminateProcess(process, guestPidFile)
            throw RuntimeOperationException(RuntimeErrorCode.PROCESS_EXITED)
        }
        return ProcessRuntimeTerminalSession(
            id = UUID.randomUUID().toString(),
            process = process,
            input = terminalProcess.input,
            output = terminalProcess.output,
            guestPidFile = guestPidFile,
            maxOutputBytes = maxOutputBytes,
            resizeSupportResolver = terminalProcess.resizeSupport,
            resizeTerminal = terminalProcess.resize,
            closeIo = terminalProcess.closeIo,
            terminate = { force ->
                if (force) killProcess(process, guestPidFile) else terminateProcess(process, guestPidFile)
            },
            onClosed = {
                val removed = synchronized(lifecycleLock) { processes.remove(pid) }
                if (removed != null) {
                    notifyProcess(RuntimeHostProcessEventKind.STOPPED, sessionId, pid)
                }
                guestPidFile.delete()
                onClosed()
            },
        )
    }

    private fun launchTerminalProcess(
        runtime: InstalledRuntime,
        request: RuntimeTerminalRequest,
        command: List<String>,
        guestEnvironment: Map<String, String>,
        guestResizeFifoFile: File,
        guestResizeReadyFile: File,
        guestResizeAckFile: File,
        guestResizeStateFile: File,
        guestPidFile: File,
    ): TerminalProcess {
        val guestResizeFifoPath = "/workspace/.alpine-runtime/processes/${guestResizeFifoFile.name}"
        val guestResizeReadyPath = "/workspace/.alpine-runtime/processes/${guestResizeReadyFile.name}"
        val guestResizeAckPath = "/workspace/.alpine-runtime/processes/${guestResizeAckFile.name}"
        val guestResizeStatePath = "/workspace/.alpine-runtime/processes/${guestResizeStateFile.name}"
        val guestResizeDebugPath = "$guestResizeStatePath.debug"
        val guestResizeDebugFile = File("${guestResizeStateFile.absolutePath}.debug")
        val pty = NativePtyBridge.open(request.columns, request.rows)
        if (pty != null) {
            val guestResize = GuestTerminalResizeChannel.open(
                guestResizeFifoFile,
                guestResizeReadyFile,
                guestResizeAckFile,
                guestResizeStateFile,
                request.columns,
                request.rows,
            )
            try {
                val process = ProcessBuilder(listOf(SYSTEM_SETSID, "-c") + command)
                    .directory(runtime.workspaceDirectory)
                    .redirectInput(File(pty.slavePath))
                    .redirectOutput(File(pty.slavePath))
                    .redirectError(File(pty.slavePath))
                    .apply {
                        configureHostEnvironment(runtime, guestEnvironment)
                        environment()["COLUMNS"] = request.columns.toString()
                        environment()["LINES"] = request.rows.toString()
                        environment()["ALPINE_TERMINAL_MODE"] = "native-pty"
                        environment()["ALPINE_TERMINAL_RESIZE_CHANNEL"] =
                            if (guestResize != null) "available" else "unavailable"
                        environment()["ALPINE_TERMINAL_RESIZE_FIFO"] = guestResizeFifoPath
                        environment()["ALPINE_TERMINAL_RESIZE_READY"] = guestResizeReadyPath
                        environment()["ALPINE_TERMINAL_RESIZE_ACK"] = guestResizeAckPath
                        environment()["ALPINE_TERMINAL_RESIZE_STATE"] = guestResizeStatePath
                        environment()["ALPINE_TERMINAL_RESIZE_DEBUG"] = guestResizeDebugPath
                        environment()["PROOT_WINSIZE_FILE"] = guestResizeStateFile.absolutePath
                        environment()["PROOT_WINSIZE_DEBUG_FILE"] = guestResizeDebugFile.absolutePath
                        // Samsung's untrusted-app seccomp path may expose ioctl only after the
                        // kernel has consumed it. Terminal sessions use full ptrace so the
                        // patched TIOCGWINSZ enter/exit hooks remain ordered deterministically.
                        environment()["PROOT_NO_SECCOMP"] = "1"
                    }
                    .start()
                return TerminalProcess(
                    process = process,
                    input = pty.input,
                    output = pty.output,
                    resizeSupport = {
                        if (guestResize?.isGuestReady == true) {
                            RuntimeTerminalResizeSupport.DYNAMIC
                        } else {
                            RuntimeTerminalResizeSupport.INITIAL_SIZE_ONLY
                        }
                    },
                    resize = { columns, rows ->
                        guestResize?.resize(columns, rows) {
                            val guestPid = readGuestPid(guestPidFile)
                            val controlResized = NativePtyBridge.resize(
                                pty.controlFd,
                                columns,
                                rows,
                            )
                            val watcherPids = guestResize.participantPids()
                            val terminalPids = (
                                processDescendants(process) +
                                    listOfNotNull(guestPid) +
                                    watcherPids
                                )
                                .distinct()
                            val processResizeResults = terminalPids.associateWith { terminalPid ->
                                val terminalFd = if (terminalPid in watcherPids) 9 else 0
                                NativePtyBridge.resizeProcessTerminalFd(
                                    terminalPid,
                                    terminalFd,
                                    columns,
                                    rows,
                                )
                            }
                            val processResized = guestPid != null &&
                                processResizeResults[guestPid] == true
                            val controlSize = NativePtyBridge.readSize(pty.controlFd)
                            val processSizes = terminalPids.joinToString(",") { terminalPid ->
                                val result = if (processResizeResults[terminalPid] == true) 1 else 0
                                val terminalFd = if (terminalPid in watcherPids) 9 else 0
                                "$terminalPid/$terminalFd:$result:" +
                                    NativePtyBridge.readProcessTerminalSizeFd(
                                        terminalPid,
                                        terminalFd,
                                    ).asDiagnostic()
                            }.take(96)
                            guestResize.recordHostDiagnostic(
                                "PID ${guestPid ?: 0} CONTROL ${controlSize.asDiagnostic()} " +
                                    "PROCESSES $processSizes " +
                                    "RESULT ${if (controlResized) 1 else 0} ${if (processResized) 1 else 0}",
                            )
                            controlResized && processResized
                        } == true
                    },
                    closeIo = {
                        guestResize?.close()
                        pty.close()
                    },
                )
            } catch (_: Exception) {
                guestResize?.close()
                pty.close()
            }
        }

        guestResizeFifoFile.delete()
        guestResizeReadyFile.delete()
        guestResizeAckFile.delete()
        guestResizeStateFile.delete()

        val process = ProcessBuilder(command)
            .directory(runtime.workspaceDirectory)
            .redirectErrorStream(true)
            .apply {
                configureHostEnvironment(runtime, guestEnvironment)
                environment()["COLUMNS"] = request.columns.toString()
                environment()["LINES"] = request.rows.toString()
                environment()["ALPINE_TERMINAL_MODE"] = "interactive-pipe"
            }
            .start()
        return TerminalProcess(
            process = process,
            input = process.inputStream,
            output = process.outputStream,
            resizeSupport = { RuntimeTerminalResizeSupport.INITIAL_SIZE_ONLY },
            resize = { _, _ -> true },
            closeIo = {
                runCatching { process.inputStream.close() }
                runCatching { process.outputStream.close() }
            },
        )
    }

    fun listProcesses(sessionId: String): List<RuntimeProcessInfo> = processes.entries
        .filter { it.value.sessionId == sessionId }
        .map { (pid, record) ->
            RuntimeProcessInfo(
                processId = pid,
                command = record.command,
                state = if (record.process.isAlive) "RUNNING" else "EXITED",
                startedAtEpochMillis = record.startedAt,
            )
        }

    fun stopSession(sessionId: String) {
        val stopped = synchronized(lifecycleLock) {
            activeSessions -= sessionId
            processes.entries
                .filter { it.value.sessionId == sessionId }
                .mapNotNull { (pid, record) ->
                    if (processes.remove(pid, record)) pid to record else null
                }
        }
        stopped.forEach { (pid, record) ->
            terminateProcess(record.process, record.guestPidFile)
            record.guestPidFile.delete()
            notifyProcess(RuntimeHostProcessEventKind.STOPPED, sessionId, pid)
        }
    }

    fun stopAll() {
        val sessionIds = synchronized(lifecycleLock) {
            (activeSessions + processes.values.map { it.sessionId }).distinct()
        }
        sessionIds.forEach(::stopSession)
    }

    private fun isSessionActive(sessionId: String): Boolean =
        synchronized(lifecycleLock) { sessionId in activeSessions }

    private fun validateGuestEnvironment(environment: Map<String, String>) {
        environment.forEach { (key, value) ->
            if (!ENVIRONMENT_NAME.matches(key) || '\u0000' in value || key in RESERVED_ENVIRONMENT) {
                throw RuntimeOperationException(RuntimeErrorCode.INVALID_REQUEST)
            }
        }
    }

    private fun guestEnvironment(
        sessionId: String,
        sessionEnvironment: Map<String, String>,
        requestEnvironment: Map<String, String>,
    ): Map<String, String> = linkedMapOf<String, String>().apply {
        putAll(sessionEnvironment)
        val context = RuntimeEnvironmentContext(
            sessionId = sessionId,
            workspacePath = "/workspace",
        )
        environmentContributors.forEach { contributor -> putAll(contributor.contribute(context)) }
        putAll(requestEnvironment)
        validateGuestEnvironment(this)
    }

    private fun ProcessBuilder.configureHostEnvironment(
        runtime: InstalledRuntime,
        guestEnvironment: Map<String, String>,
    ) {
        environment()["TERM"] = "xterm-256color"
        environment()["LANG"] = "C.UTF-8"
        environment()["HOME"] = "/root"
        environment()["PATH"] = "/usr/local/bin:/usr/bin:/bin"
        environment()["PROOT_TMP_DIR"] = File(cacheDirectory, "proot-tmp")
            .apply { mkdirs() }
            .absolutePath
        environment()["LD_LIBRARY_PATH"] = runtime.launcher.parentFile?.absolutePath.orEmpty()
        environment()["PROOT_LOADER"] = runtime.loader.absolutePath
        environment().putAll(guestEnvironment)
    }

    private fun notifyProcess(kind: RuntimeHostProcessEventKind, sessionId: String, pid: Long) {
        runCatching {
            processListener.onProcessEvent(RuntimeHostProcessEvent(kind, sessionId, pid, clock()))
        }
    }

    private fun joinCollector(thread: Thread) {
        try {
            thread.join(2_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun terminateProcess(process: Process, guestPidFile: File) {
        val guestPid = readGuestPid(guestPidFile)
        val descendants = processDescendants(process)
        descendants.asReversed().forEach { pid ->
            runCatching { AndroidProcess.sendSignal(pid, SIGTERM) }
        }
        if (guestPid != null) runCatching { AndroidProcess.sendSignal(guestPid, SIGTERM) }
        process.destroy()
        val exited = try {
            process.waitFor(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!exited) {
            descendants.asReversed().forEach { pid ->
                runCatching { AndroidProcess.sendSignal(pid, SIGKILL) }
            }
            if (guestPid != null) runCatching { AndroidProcess.sendSignal(guestPid, SIGKILL) }
            process.destroyForcibly()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        }
    }

    private fun killProcess(process: Process, guestPidFile: File) {
        processDescendants(process).asReversed().forEach { pid ->
            runCatching { AndroidProcess.sendSignal(pid, SIGKILL) }
        }
        readGuestPid(guestPidFile)?.let { pid ->
            runCatching { AndroidProcess.sendSignal(pid, SIGKILL) }
        }
        process.destroyForcibly()
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
    }

    private fun processDescendants(process: Process): List<Int> = runCatching {
        @Suppress("UNCHECKED_CAST")
        val descendants = Process::class.java.getMethod("descendants").invoke(process) as Stream<Any>
        val pidMethod = Class.forName("java.lang.ProcessHandle").getMethod("pid")
        descendants.use { stream ->
            stream.iterator().asSequence().mapNotNull { handle ->
                (pidMethod.invoke(handle) as? Long)
                    ?.takeIf { it in 2..Int.MAX_VALUE.toLong() }
                    ?.toInt()
            }.toList()
        }
    }.getOrDefault(emptyList())

    private fun readGuestPid(file: File): Int? {
        repeat(5) {
            val value = runCatching {
                file.inputStream().bufferedReader(Charsets.US_ASCII).use { reader ->
                    reader.readLine()?.trim()?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
                        ?.toIntOrNull()
                }
            }.getOrNull()
            if (value != null) return value
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private data class ProcessRecord(
        val process: Process,
        val sessionId: String,
        val command: String,
        val startedAt: Long,
        val guestPidFile: File,
    )

    private data class TerminalProcess(
        val process: Process,
        val input: InputStream,
        val output: OutputStream,
        val resizeSupport: () -> RuntimeTerminalResizeSupport,
        val resize: (Int, Int) -> Boolean,
        val closeIo: () -> Unit,
    )

    private fun NativePtySize?.asDiagnostic(): String =
        this?.let { "${it.rows} ${it.columns}" } ?: "0 0"

    private class LimitedOutputCollector(
        private val input: InputStream,
        private val maxBytes: Int,
    ) : Runnable {
        private val output = ByteArrayOutputStream()

        override fun run() {
            runCatching {
                input.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = maxBytes - output.size()
                            if (remaining > 0) output.write(buffer, 0, minOf(remaining, count))
                        }
                    }
                }
            }
        }

        fun bytes(): ByteArray = synchronized(output) { output.toByteArray() }
    }

    private class ProcessRuntimeTerminalSession(
        override val id: String,
        private val process: Process,
        private val input: InputStream,
        private val output: OutputStream,
        private val guestPidFile: File,
        private val maxOutputBytes: Int,
        private val resizeSupportResolver: () -> RuntimeTerminalResizeSupport,
        private val resizeTerminal: (Int, Int) -> Boolean,
        private val closeIo: () -> Unit,
        private val terminate: (Boolean) -> Unit,
        private val onClosed: () -> Unit,
    ) : RuntimeTerminalSession {
        private val listeners = CopyOnWriteArrayList<RuntimeTerminalOutputListener>()
        private val open = AtomicBoolean(true)
        private val closed = AtomicBoolean(false)
        private val writeLock = Any()
        private val outputLock = Any()
        private var replayBuffer = ByteArray(0)

        override val isOpen: Boolean
            get() = open.get() && process.isAlive
        override val resizeSupport: RuntimeTerminalResizeSupport
            get() = resizeSupportResolver()
        init {
            Thread({ collectOutput() }, "alpine-runtime-terminal-output-$id").apply {
                isDaemon = true
                start()
            }
        }

        override fun addOutputListener(listener: RuntimeTerminalOutputListener): RuntimeSubscription {
            listeners += listener
            val replay = synchronized(outputLock) { replayBuffer.copyOf() }
            if (replay.isNotEmpty()) runCatching { listener.onOutput(replay) }
            return RuntimeSubscription { listeners -= listener }
        }

        override fun write(bytes: ByteArray): CompletionStage<Void> {
            if (bytes.isEmpty() || bytes.size > MAX_TERMINAL_WRITE_BYTES) {
                return failed(RuntimeErrorCode.INVALID_REQUEST)
            }
            if (!isOpen) return failed(RuntimeErrorCode.PROCESS_EXITED)
            return runOperation {
                synchronized(writeLock) {
                    output.write(bytes)
                    output.flush()
                }
            }
        }

        override fun resize(columns: Int, rows: Int): CompletionStage<Void> {
            if (columns !in 1..1_000 || rows !in 1..1_000) {
                return failed(RuntimeErrorCode.INVALID_REQUEST)
            }
            if (!isOpen) return failed(RuntimeErrorCode.PROCESS_EXITED)
            if (resizeSupport != RuntimeTerminalResizeSupport.DYNAMIC) {
                return failed(RuntimeErrorCode.TERMINAL_RESIZE_UNSUPPORTED)
            }
            return if (resizeTerminal(columns, rows)) {
                completedVoid()
            } else {
                failed(RuntimeErrorCode.TERMINAL_UNAVAILABLE)
            }
        }

        override fun signal(signal: RuntimeTerminalSignal): CompletionStage<Void> = when (signal) {
            RuntimeTerminalSignal.INTERRUPT -> write(byteArrayOf(3))
            RuntimeTerminalSignal.END_OF_FILE -> write(byteArrayOf(4))
            RuntimeTerminalSignal.TERMINATE -> runOperation { finish(terminateProcess = true, force = false) }
            RuntimeTerminalSignal.KILL -> runOperation { finish(terminateProcess = true, force = true) }
        }

        override fun closeAsync(): CompletionStage<Void> = runOperation {
            finish(terminateProcess = true, force = false)
        }

        private fun collectOutput() {
            runCatching {
                input.use { stream ->
                    val buffer = ByteArray(8192)
                    while (open.get()) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        val chunk = buffer.copyOf(count)
                        synchronized(outputLock) {
                            replayBuffer = boundedAppend(replayBuffer, chunk, maxOutputBytes)
                        }
                        listeners.forEach { listener ->
                            runCatching { listener.onOutput(chunk.copyOf()) }
                        }
                    }
                }
            }
            finish(terminateProcess = false, force = false)
        }

        private fun finish(terminateProcess: Boolean, force: Boolean) {
            open.set(false)
            if (!closed.compareAndSet(false, true)) return
            if (terminateProcess && process.isAlive) terminate(force)
            runCatching(closeIo)
            guestPidFile.delete()
            onClosed()
        }

        private fun runOperation(block: () -> Unit): CompletionStage<Void> = try {
            block()
            completedVoid()
        } catch (_: Exception) {
            failed(RuntimeErrorCode.COMMAND_FAILED)
        }

        private fun failed(code: RuntimeErrorCode): CompletionStage<Void> =
            CompletableFuture<Void>().also { it.completeExceptionally(RuntimeOperationException(code)) }

        private fun completedVoid(): CompletionStage<Void> = CompletableFuture.completedFuture(null)

        private fun boundedAppend(existing: ByteArray, incoming: ByteArray, limit: Int): ByteArray {
            if (incoming.size >= limit) return incoming.copyOfRange(incoming.size - limit, incoming.size)
            val keepExisting = minOf(existing.size, limit - incoming.size)
            return ByteArray(keepExisting + incoming.size).also { combined ->
                existing.copyInto(combined, 0, existing.size - keepExisting, existing.size)
                incoming.copyInto(combined, keepExisting)
            }
        }

        companion object {
            private const val MAX_TERMINAL_WRITE_BYTES = 64 * 1024
        }
    }

    companion object {
        private const val CANCEL_POLL_MILLIS = 100L
        private const val SIGTERM = 15
        private const val SIGKILL = 9
        private const val SYSTEM_SETSID = "/system/bin/setsid"
        private const val GUEST_PROCESS_WRAPPER =
            "pid_file=\$1; shift; " +
                "(umask 077; printf '%s\\n' \"\$\$\" > \"\$pid_file\"); " +
                "exec \"\$@\""
        private const val GUEST_TERMINAL_WRAPPER =
            "pid_file=\$1; shell=\$2; cols=\$3; rows=\$4; " +
                "resize_fifo=\${ALPINE_TERMINAL_RESIZE_FIFO:-}; " +
                "resize_ready=\${ALPINE_TERMINAL_RESIZE_READY:-}; " +
                "resize_ack=\${ALPINE_TERMINAL_RESIZE_ACK:-}; " +
                "resize_state=\${ALPINE_TERMINAL_RESIZE_STATE:-}; " +
            "resize_debug=\${ALPINE_TERMINAL_RESIZE_DEBUG:-}; " +
                "(umask 077; printf '%s\\n' \"\$\$\" > \"\$pid_file\"); " +
                "stty cols \"\$cols\" rows \"\$rows\" 2>/dev/null || true; " +
                "if [ -n \"\$resize_fifo\" ]; then " +
                "exec 9<&0; " +
                "(IFS=' ' read -r watcher_pid _ < /proc/self/stat; " +
                "case \"\$watcher_pid\" in *[!0-9]*|'') exit 1;; esac; " +
                "printf '%s\\n' \"\$watcher_pid\" > \"\${resize_state}.watcher.pid\"; " +
                "while IFS=' ' read -r seq next_rows next_cols; do " +
                "case \"\$seq:\$next_rows:\$next_cols\" in *[!0-9:]*) continue;; esac; " +
                "if stty rows \"\$next_rows\" cols \"\$next_cols\" <&9 2>/dev/null; then " +
                "actual_size=\$(stty size <&9 2>/dev/null || true); " +
                "if [ \"\$actual_size\" = \"\$next_rows \$next_cols\" ]; then " +
                "printf '%s %s %s\\n' \"\$seq\" \"\$next_rows\" \"\$next_cols\" > \"\$resize_ack\"; " +
                "kill -WINCH 0 2>/dev/null || true; else " +
                "state_size=\$(cat \"\$resize_state\" 2>/dev/null || true); " +
                "debug_state=\$(cat \"\$resize_debug\" 2>/dev/null || true); " +
                "host_state=\$(cat \"\${resize_state}.host\" 2>/dev/null || true); " +
                "printf 'MISMATCH %s STATE %s PROOT %s HOST %s\\n' \"\$actual_size\" \"\$state_size\" \"\$debug_state\" \"\$host_state\" > \"\$resize_ack\"; fi; else " +
                "printf 'STTY_FAILED\\n' > \"\$resize_ack\"; fi; " +
                "done < \"\$resize_fifo\") & " +
                ": > \"\$resize_ready\"; exec 9<&-; fi; " +
                "exec \"\$shell\" -i"
        private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val RESERVED_ENVIRONMENT = setOf(
            "PROOT_TMP_DIR",
            "PROOT_LOADER",
            "PROOT_WINSIZE_FILE",
            "PROOT_WINSIZE_DEBUG_FILE",
            "PROOT_NO_SECCOMP",
            "LD_LIBRARY_PATH",
        )
    }
}
