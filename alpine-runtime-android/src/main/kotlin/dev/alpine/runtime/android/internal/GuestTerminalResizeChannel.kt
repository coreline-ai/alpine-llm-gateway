package dev.alpine.runtime.android.internal

import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object GuestTerminalResizeProtocol {
    fun command(sequence: Long, columns: Int, rows: Int): ByteArray =
        "$sequence $rows $columns\n".toByteArray(Charsets.US_ASCII)

    fun matchesAck(value: String?, sequence: Long, columns: Int, rows: Int): Boolean =
        value?.trim() == "$sequence $rows $columns"
}

/**
 * Host-to-guest resize channel for PRoot terminals.
 *
 * Android's PTY ioctl updates the Host terminal, but PRoot does not reliably expose that
 * post-start change to the tracee on every Android release. A private FIFO asks a tiny guest
 * watcher to apply the same dimensions through /dev/tty. An acknowledgement is required before
 * resize() reports success, so creating the FIFO alone can never advertise a false DYNAMIC result.
 */
internal class GuestTerminalResizeChannel private constructor(
    private val fifoFile: File,
    private val readyFile: File,
    private val ackFile: File,
    private val stateFile: File,
    private val descriptor: ParcelFileDescriptor,
    private val output: OutputStream,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val writeLock = Any()

    val isGuestReady: Boolean
        get() = !closed.get() && readyFile.isFile

    fun participantPids(): List<Int> = listOfNotNull(
        readPid(File("${stateFile.absolutePath}.watcher.pid")),
    )

    fun resize(columns: Int, rows: Int, applyHostResize: () -> Boolean): Boolean {
        if (!isGuestReady || columns !in 1..1_000 || rows !in 1..1_000) return false
        return synchronized(writeLock) {
            if (closed.get()) return@synchronized false
            val requestSequence = sequence.incrementAndGet()
            if (!writeState(columns, rows) || !applyHostResize()) return@synchronized false
            runCatching {
                output.write(GuestTerminalResizeProtocol.command(requestSequence, columns, rows))
                output.flush()
            }.getOrElse { return@synchronized false }
            awaitAck(requestSequence, columns, rows)
        }
    }

    fun recordHostDiagnostic(value: String) {
        runCatching {
            require(value.length <= 160 && value.all { it.code in 0x20..0x7e })
            File("${stateFile.absolutePath}.host").writeText("$value\n", Charsets.US_ASCII)
        }
    }

    private fun writeState(columns: Int, rows: Int): Boolean = runCatching {
        // Keep a stable inode while PRoot is tracing the guest. Some Android/SELinux
        // combinations can retain the pre-rename file view across the ptrace boundary.
        // The FIFO command is emitted only after this stream is flushed and closed, so
        // an in-place update remains ordered without exposing a partial accepted resize.
        stateFile.outputStream().buffered().use { stream ->
            stream.write("$rows $columns\n".toByteArray(Charsets.US_ASCII))
            stream.flush()
        }
        Os.chmod(stateFile.absolutePath, FIFO_MODE_OWNER_READ_WRITE)
        true
    }.getOrDefault(false)

    private fun awaitAck(sequence: Long, columns: Int, rows: Int): Boolean {
        val deadline = System.nanoTime() + ACK_TIMEOUT_NANOS
        while (System.nanoTime() < deadline && !closed.get()) {
            val ack = runCatching {
                ackFile.inputStream().bufferedReader(Charsets.US_ASCII).use { it.readLine() }
            }.getOrNull()
            if (GuestTerminalResizeProtocol.matchesAck(ack, sequence, columns, rows)) return true
            try {
                Thread.sleep(ACK_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun readPid(file: File): Int? = runCatching {
        file.inputStream().bufferedReader(Charsets.US_ASCII).use { reader ->
            reader.readLine()?.trim()
                ?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
                ?.toIntOrNull()
        }
    }.getOrNull()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }
        runCatching { descriptor.close() }
        readyFile.delete()
        ackFile.delete()
        stateFile.delete()
        File("${stateFile.absolutePath}.debug").delete()
        File("${stateFile.absolutePath}.host").delete()
        File("${stateFile.absolutePath}.watcher.pid").delete()
        fifoFile.delete()
    }

    companion object {
        private const val FIFO_MODE_OWNER_READ_WRITE = 384 // 0600
        private const val ACK_POLL_MILLIS = 10L
        private const val ACK_TIMEOUT_NANOS = 1_500_000_000L

        fun open(
            fifoFile: File,
            readyFile: File,
            ackFile: File,
            stateFile: File,
            initialColumns: Int,
            initialRows: Int,
        ): GuestTerminalResizeChannel? =
            runCatching {
                fifoFile.parentFile?.mkdirs()
                fifoFile.delete()
                readyFile.delete()
                ackFile.delete()
                stateFile.delete()
                File("${stateFile.absolutePath}.debug").delete()
                File("${stateFile.absolutePath}.host").delete()
                File("${stateFile.absolutePath}.watcher.pid").delete()
                Os.mkfifo(fifoFile.absolutePath, FIFO_MODE_OWNER_READ_WRITE)
                val descriptor = ParcelFileDescriptor.open(
                    fifoFile,
                    ParcelFileDescriptor.MODE_READ_WRITE,
                )
                val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                val channel = GuestTerminalResizeChannel(
                    fifoFile,
                    readyFile,
                    ackFile,
                    stateFile,
                    descriptor,
                    output,
                )
                if (!channel.writeState(initialColumns, initialRows)) {
                    channel.close()
                    error("initial terminal size state could not be written")
                }
                channel
            }.getOrElse {
                fifoFile.delete()
                readyFile.delete()
                ackFile.delete()
                stateFile.delete()
                null
            }
    }
}
