package dev.alpine.codex.appserver.runtime

import dev.alpine.codex.appserver.CodexAppServerErrorCode
import dev.alpine.codex.appserver.CodexAppServerException
import dev.alpine.codex.appserver.protocol.CodexRpcClient
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

data class CodexProcessDiagnostics(
    val stderrBytes: Long,
    val stderrLines: Long,
)

internal fun interface CodexProcessStarter {
    fun start(layout: CodexAppServerLayout): Process
}

internal object DefaultCodexProcessStarter : CodexProcessStarter {
    override fun start(layout: CodexAppServerLayout): Process {
        val builder = ProcessBuilder(
            listOf(
                layout.binary.absolutePath,
                "app-server",
                "--listen",
                "stdio://",
            ),
        )
        builder.directory(layout.workspace)
        builder.redirectErrorStream(false)
        builder.environment().apply {
            clear()
            putAll(CodexProcessEnvironment.values(layout))
        }
        return builder.start()
    }
}

internal object CodexProcessEnvironment {
    fun values(layout: CodexAppServerLayout): Map<String, String> = mapOf(
        "CODEX_HOME" to layout.home.absolutePath,
        "HOME" to layout.home.absolutePath,
        "TMPDIR" to layout.temporary.absolutePath,
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "PATH" to "/system/bin",
        // The official static Linux binary cannot discover Android's CA store or netd resolver.
        // Trust remains Android system-only; the authenticated loopback proxy supplies DNS only.
        "CODEX_CA_CERTIFICATE" to layout.caBundle.absolutePath,
        "SSL_CERT_FILE" to layout.caBundle.absolutePath,
        "HTTPS_PROXY" to layout.httpsProxy,
        "https_proxy" to layout.httpsProxy,
        "ALL_PROXY" to layout.httpsProxy,
        "all_proxy" to layout.httpsProxy,
        "NO_PROXY" to "localhost,127.0.0.1,[::1]",
        "no_proxy" to "localhost,127.0.0.1,[::1]",
    )
}

/** Owns exactly one directly executed Codex process. Raw stderr is drained but never retained. */
class CodexNativeProcess internal constructor(
    private val process: Process,
    requestTimeoutMs: Long,
    maxLineBytes: Int,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val stderrBytes = AtomicLong(0)
    private val stderrLines = AtomicLong(0)
    private val stderrThread = drainStderr(process.errorStream)
    val rpc = CodexRpcClient(
        input = process.inputStream,
        output = process.outputStream,
        requestTimeoutMs = requestTimeoutMs,
        maxLineBytes = maxLineBytes,
    )

    /** A live OS child with a terminal RPC channel is not reusable. */
    fun isAlive(): Boolean = process.isAlive && !closed.get() && rpc.isOpen()

    fun diagnostics(): CodexProcessDiagnostics = CodexProcessDiagnostics(
        stderrBytes = stderrBytes.get(),
        stderrLines = stderrLines.get(),
    )

    private fun drainStderr(input: InputStream): Thread = thread(
        start = true,
        isDaemon = true,
        name = "codex-appserver-stderr",
    ) {
        val buffer = ByteArray(8 * 1024)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                stderrBytes.updateAndGet { previous -> saturatingAdd(previous, count.toLong()) }
                val lines = buffer.take(count).count { it == '\n'.code.toByte() }
                stderrLines.updateAndGet { previous -> saturatingAdd(previous, lines.toLong()) }
            }
        } catch (_: Exception) {
            // Process teardown closes the stream. Diagnostics intentionally retain no raw bytes.
        }
    }

    override fun close() {
        val firstClose = closed.compareAndSet(false, true)
        if (firstClose) {
            rpc.close()
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
        if (process.isAlive) process.destroy()
        try {
            if (!process.waitFor(GRACEFUL_CLOSE_SECONDS, TimeUnit.SECONDS) && process.isAlive) {
                process.destroyForcibly()
                if (!process.waitFor(FORCED_CLOSE_SECONDS, TimeUnit.SECONDS) && process.isAlive) {
                    // Retry once so a transient signal delivery race cannot be reported as a
                    // successful close. A still-live child must block reconnect rather than
                    // creating a duplicate App Server process.
                    process.destroyForcibly()
                    process.waitFor(FORCED_CLOSE_SECONDS, TimeUnit.SECONDS)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            if (process.isAlive) process.destroyForcibly()
        }
        stderrThread.interrupt()
        if (process.isAlive) {
            throw CodexAppServerException(
                CodexAppServerErrorCode.PROCESS_TERMINATION_FAILED,
            )
        }
    }

    companion object {
        private const val GRACEFUL_CLOSE_SECONDS = 2L
        private const val FORCED_CLOSE_SECONDS = 2L

        internal fun launch(
            layout: CodexAppServerLayout,
            starter: CodexProcessStarter = DefaultCodexProcessStarter,
            requestTimeoutMs: Long = CodexRpcClient.DEFAULT_REQUEST_TIMEOUT_MS,
            maxLineBytes: Int = CodexRpcClient.DEFAULT_MAX_LINE_BYTES,
        ): CodexNativeProcess = try {
            CodexNativeProcess(starter.start(layout), requestTimeoutMs, maxLineBytes)
        } catch (failure: CodexAppServerException) {
            throw failure
        } catch (failure: Exception) {
            throw CodexAppServerException(CodexAppServerErrorCode.PROCESS_START_FAILED, failure)
        }
    }
}

private fun saturatingAdd(first: Long, second: Long): Long =
    if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second
