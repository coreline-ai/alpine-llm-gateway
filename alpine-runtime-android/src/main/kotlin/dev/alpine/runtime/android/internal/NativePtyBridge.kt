package dev.alpine.runtime.android.internal

import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal object NativePtyBridge {
    private val loaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("alpine-runtime-pty")
            true
        }.getOrDefault(false)
    }

    fun open(columns: Int, rows: Int): NativePtyDescriptor? {
        if (!loaded) return null
        return runCatching { nativeOpen() }.getOrNull()?.also { descriptor ->
            if (!resize(descriptor.controlFd, columns, rows)) descriptor.close()
        }?.takeUnless { it.isClosed }
    }

    fun resize(fd: Int, columns: Int, rows: Int): Boolean =
        loaded && runCatching { nativeResize(fd, columns, rows) }.getOrDefault(false)

    /**
     * Probe-only resize request for the direct PRoot tracer child. The PRoot
     * diagnostic artifact owns all guest-side routing through its session
     * supervisor socket; product code must use [resize] and continue to
     * advertise INITIAL_SIZE_ONLY.
     */
    fun resizeAndRequestProbeRelay(
        fd: Int,
        columns: Int,
        rows: Int,
        relaySocketPath: String,
    ): Boolean = loaded && relaySocketPath.isNotBlank() && relaySocketPath.length < 100 && runCatching {
        nativeResizeAndRequestProbeRelay(fd, columns, rows, relaySocketPath)
    }.getOrDefault(false)

    /**
     * Probe-only virtual-winsize request. Unlike the older relay experiment,
     * this deliberately does not call host PTY TIOCSWINSZ. The native bridge
     * sends only validated dimensions to the debug-gated private supervisor.
     */
    fun requestProbeVirtualResize(
        columns: Int,
        rows: Int,
        relaySocketPath: String,
    ): Boolean = loaded && relaySocketPath.isNotBlank() && relaySocketPath.length < 100 && runCatching {
        nativeRequestProbeVirtualResize(columns, rows, relaySocketPath)
    }.getOrDefault(false)

    fun readSize(fd: Int): NativePtySize? =
        if (!loaded) null else decodeSize(runCatching { nativeReadSize(fd) }.getOrDefault(0L))

    private fun decodeSize(encoded: Long): NativePtySize? {
        val rows = (encoded ushr 32).toInt()
        val columns = encoded.toInt()
        return if (columns in 1..1_000 && rows in 1..1_000) {
            NativePtySize(columns = columns, rows = rows)
        } else {
            null
        }
    }

    private external fun nativeOpen(): NativePtyDescriptor?
    private external fun nativeResize(fd: Int, columns: Int, rows: Int): Boolean
    private external fun nativeResizeAndRequestProbeRelay(
        fd: Int,
        columns: Int,
        rows: Int,
        relaySocketPath: String,
    ): Boolean
    private external fun nativeRequestProbeVirtualResize(
        columns: Int,
        rows: Int,
        relaySocketPath: String,
    ): Boolean
    private external fun nativeReadSize(fd: Int): Long
}

internal data class NativePtySize(val columns: Int, val rows: Int)

internal class NativePtyDescriptor(
    readFd: Int,
    writeFd: Int,
    val controlFd: Int,
    /** Kernel device identity of the slave PTY, used only by the dev Probe. */
    val slaveDeviceId: Long,
    val slavePath: String,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val readDescriptor = ParcelFileDescriptor.adoptFd(readFd)
    private val writeDescriptor = ParcelFileDescriptor.adoptFd(writeFd)
    private val controlDescriptor = ParcelFileDescriptor.adoptFd(controlFd)
    val input: InputStream = ParcelFileDescriptor.AutoCloseInputStream(readDescriptor)
    val output: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(writeDescriptor)
    val isClosed: Boolean get() = closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { controlDescriptor.close() }
    }
}
