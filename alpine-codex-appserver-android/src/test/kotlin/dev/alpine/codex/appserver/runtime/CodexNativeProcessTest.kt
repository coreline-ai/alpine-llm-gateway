package dev.alpine.codex.appserver.runtime

import dev.alpine.codex.appserver.CodexAppServerErrorCode
import dev.alpine.codex.appserver.CodexAppServerException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexNativeProcessTest {
    @Test
    fun `close escalates to forcible termination and confirms child exit`() {
        val process = FakeProcess(destroyKills = false, forceKills = true)
        val native = CodexNativeProcess(process, requestTimeoutMs = 1_000, maxLineBytes = 1_024)

        native.close()

        assertFalse(process.isAlive)
        assertEquals(1, process.destroyCount.get())
        assertEquals(1, process.forceCount.get())
    }

    @Test
    fun `unkillable child fails closed and a repeated close retries termination`() {
        val process = FakeProcess(destroyKills = false, forceKills = false)
        val native = CodexNativeProcess(process, requestTimeoutMs = 1_000, maxLineBytes = 1_024)

        val first = assertThrows(CodexAppServerException::class.java) { native.close() }
        assertEquals(CodexAppServerErrorCode.PROCESS_TERMINATION_FAILED, first.code)
        val forceCountAfterFirst = process.forceCount.get()
        assertTrue(forceCountAfterFirst >= 2)

        val second = assertThrows(CodexAppServerException::class.java) { native.close() }
        assertEquals(CodexAppServerErrorCode.PROCESS_TERMINATION_FAILED, second.code)
        assertTrue(process.forceCount.get() > forceCountAfterFirst)
        assertTrue(process.isAlive)
    }

    private class FakeProcess(
        private val destroyKills: Boolean,
        private val forceKills: Boolean,
    ) : Process() {
        private val alive = AtomicBoolean(true)
        private val stdin = ByteArrayOutputStream()
        private val stdout = ByteArrayInputStream(ByteArray(0))
        private val stderr = ByteArrayInputStream(ByteArray(0))
        val destroyCount = AtomicInteger(0)
        val forceCount = AtomicInteger(0)

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int {
            alive.set(false)
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive.get()

        override fun exitValue(): Int {
            check(!alive.get()) { "process is alive" }
            return 0
        }

        override fun destroy() {
            destroyCount.incrementAndGet()
            if (destroyKills) alive.set(false)
        }

        override fun destroyForcibly(): Process {
            forceCount.incrementAndGet()
            if (forceKills) alive.set(false)
            return this
        }

        override fun isAlive(): Boolean = alive.get()
    }
}
