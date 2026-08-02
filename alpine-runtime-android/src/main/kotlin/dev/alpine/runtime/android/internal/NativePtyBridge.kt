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

    fun readSize(fd: Int): NativePtySize? =
        if (!loaded) null else decodeSize(runCatching { nativeReadSize(fd) }.getOrDefault(0L))

    fun resizeProcessTerminal(pid: Int, columns: Int, rows: Int): Boolean =
        loaded && runCatching {
            nativeResizeProcessTerminal(pid, columns, rows)
        }.getOrDefault(false)

    fun resizeProcessTerminalFd(pid: Int, fd: Int, columns: Int, rows: Int): Boolean =
        loaded && runCatching {
            nativeResizeProcessTerminalFd(pid, fd, columns, rows)
        }.getOrDefault(false)

    fun readProcessTerminalSize(pid: Int): NativePtySize? =
        if (!loaded) null else decodeSize(
            runCatching { nativeReadProcessTerminalSize(pid) }.getOrDefault(0L),
        )

    fun readProcessTerminalSizeFd(pid: Int, fd: Int): NativePtySize? =
        if (!loaded) null else decodeSize(
            runCatching { nativeReadProcessTerminalSizeFd(pid, fd) }.getOrDefault(0L),
        )

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
    private external fun nativeReadSize(fd: Int): Long
    private external fun nativeResizeProcessTerminal(
        pid: Int,
        columns: Int,
        rows: Int,
    ): Boolean
    private external fun nativeResizeProcessTerminalFd(
        pid: Int,
        fd: Int,
        columns: Int,
        rows: Int,
    ): Boolean
    private external fun nativeReadProcessTerminalSize(pid: Int): Long
    private external fun nativeReadProcessTerminalSizeFd(pid: Int, fd: Int): Long
}

internal data class NativePtySize(val columns: Int, val rows: Int)

internal class NativePtyDescriptor(
    readFd: Int,
    writeFd: Int,
    val controlFd: Int,
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
