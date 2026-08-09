package dev.alpine.runtime.android.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativePtyBridgeInstrumentedTest {
    @Test
    fun forkptyRejectsUnsafeInputAndReportsChildExecFailure() {
        assertNull(
            "a NUL argument must not cross the JNI fork boundary",
            NativePtyBridge.forkExec(
                argv = listOf("/system/bin/sh", "-c", "printf '\u0000'"),
                environment = mapOf("PATH" to "/system/bin"),
                workingDirectory = "/",
                columns = 80,
                rows = 24,
            ),
        )
        assertNull(
            "invalid dimensions must fail before native fork",
            NativePtyBridge.forkExec(
                argv = listOf("/system/bin/sh", "-c", "exit 0"),
                environment = mapOf("PATH" to "/system/bin"),
                workingDirectory = "/",
                columns = 0,
                rows = 24,
            ),
        )

        val descriptor = NativePtyBridge.forkExec(
            argv = listOf("/does/not/exist"),
            environment = mapOf("PATH" to "/system/bin"),
            workingDirectory = "/",
            columns = 80,
            rows = 24,
        )
        assertNotNull("exec failure must remain an owned, reapable child", descriptor)
        descriptor ?: return
        try {
            assertEquals(127, NativePtyBridge.waitForChild(descriptor.childPid, 3_000))
        } finally {
            descriptor.close()
        }
    }

    @Test
    fun forkptyLifecycleSignalTerminatesTheOwnedProcessGroup() {
        val descriptor = NativePtyBridge.forkExec(
            argv = listOf("/system/bin/sh", "-c", "exec /system/bin/sleep 30"),
            environment = mapOf("PATH" to "/system/bin:/system/xbin"),
            workingDirectory = "/",
            columns = 80,
            rows = 24,
        )
        assertNotNull("forkpty direct exec must expose an owned process", descriptor)
        descriptor ?: return
        try {
            assertTrue(
                "lifecycle termination targets the forkpty-owned process group",
                NativePtyBridge.signalProcessGroup(descriptor.childPid, 15),
            )
            assertEquals(-143, NativePtyBridge.waitForChild(descriptor.childPid, 3_000))
        } finally {
            if (NativePtyBridge.isChildAlive(descriptor.childPid)) {
                NativePtyBridge.signalProcessGroup(descriptor.childPid, 9)
                NativePtyBridge.waitForChild(descriptor.childPid, 2_000)
            }
            descriptor.close()
        }
    }

    @Test
    fun forkptyExecOwnsControllingTerminalAndDeliversKernelWinchWithoutInputLoss() {
        val ready = CountDownLatch(1)
        val winch = CountDownLatch(1)
        val afterResize = CountDownLatch(1)
        val observed = StringBuilder()
        val descriptor = NativePtyBridge.forkExec(
            argv = listOf(
                "/system/bin/sh",
                "-c",
                "trap 'printf WINCH\\n' WINCH; printf READY\\n; " +
                    "while IFS= read -r line; do printf 'GOT:%s\\n' \"${'$'}line\"; " +
                    "[ \"${'$'}line\" = exit ] && exit 0; done",
            ),
            environment = mapOf("PATH" to "/system/bin:/system/xbin", "TERM" to "xterm-256color"),
            workingDirectory = "/",
            columns = 80,
            rows = 24,
        )
        assertNotNull("forkpty direct exec must be available on API 26+", descriptor)
        descriptor ?: return
        try {
            assertTrue("forkpty must return an owned child", descriptor.childPid > 0)
            assertEquals(NativePtySize(columns = 80, rows = 24), NativePtyBridge.readSize(descriptor.controlFd))
            val reader = Thread {
                val buffer = ByteArray(256)
                while (true) {
                    val count = runCatching { descriptor.input.read(buffer) }.getOrElse { -1 }
                    if (count <= 0) return@Thread
                    val chunk = buffer.copyOf(count).toString(Charsets.UTF_8)
                    synchronized(observed) { observed.append(chunk) }
                    if ("READY" in chunk || synchronized(observed) { observed.contains("READY") }) ready.countDown()
                    if ("WINCH" in chunk || synchronized(observed) { observed.contains("WINCH") }) winch.countDown()
                    if ("GOT:after-resize" in chunk || synchronized(observed) {
                            observed.contains("GOT:after-resize")
                        }
                    ) afterResize.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }
            assertTrue("shell must read from the forkpty slave", ready.await(3, TimeUnit.SECONDS))

            assertTrue("TIOCSWINSZ must apply to the forkpty master", NativePtyBridge.resize(descriptor.controlFd, 120, 40))
            assertEquals(NativePtySize(columns = 120, rows = 40), NativePtyBridge.readSize(descriptor.controlFd))
            assertTrue("kernel must deliver SIGWINCH to the foreground shell", winch.await(3, TimeUnit.SECONDS))

            descriptor.output.write("after-resize\nexit\n".toByteArray())
            descriptor.output.flush()
            assertTrue("input must continue after SIGWINCH", afterResize.await(3, TimeUnit.SECONDS))
            assertEquals(0, NativePtyBridge.waitForChild(descriptor.childPid, 3_000))
            reader.join(1_000)
        } finally {
            if (NativePtyBridge.isChildAlive(descriptor.childPid)) {
                NativePtyBridge.signalProcessGroup(descriptor.childPid, 15)
                NativePtyBridge.waitForChild(descriptor.childPid, 2_000)
            }
            descriptor.close()
        }
    }
}
