package dev.alpine.runtime.android.internal

import dev.alpine.runtime.api.RuntimeCommandRequest
import dev.alpine.runtime.api.RuntimeErrorCode
import dev.alpine.runtime.api.RuntimeHostProcessListener
import dev.alpine.runtime.api.RuntimeOperationException
import dev.alpine.runtime.api.RuntimeTerminalRequest
import dev.alpine.runtime.api.RuntimeTerminalResizeSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProotProcessLauncherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reserved host environment is rejected without being remapped to process failure`() {
        val base = temporaryFolder.newFolder("runtime")
        val runtime = InstalledRuntime(
            runtimeId = "alpine",
            runtimeVersion = "test",
            abi = "arm64-v8a",
            rootfsDirectory = File(base, "rootfs"),
            workspaceDirectory = File(base, "workspace").apply { mkdirs() },
            launcher = File(base, "libproot.so"),
            loader = File(base, "libproot-loader.so"),
        )
        val launcher = ProotProcessLauncher(
            cacheDirectory = File(base, "cache"),
            environmentContributors = emptyList(),
            processListener = RuntimeHostProcessListener { },
            maxOutputBytes = 1024,
        )
        launcher.openSession("session")

        val error = assertThrows(RuntimeOperationException::class.java) {
            launcher.execute(
                runtime = runtime,
                sessionId = "session",
                sessionEnvironment = emptyMap(),
                request = RuntimeCommandRequest(
                    executable = "/bin/sh",
                    environment = mapOf(
                        "PROOT_LOADER" to "/untrusted/loader",
                        "PROOT_TTY_DIAGNOSTIC_FILE" to "/untrusted/diagnostic",
                        "PROOT_NO_SECCOMP" to "1",
                    ),
                ),
            )
        }

        assertEquals(RuntimeErrorCode.INVALID_REQUEST, error.errorCode)
    }

    @Test
    fun `terminal size hints are runtime owned and cannot be restored by any caller environment`() {
        val base = temporaryFolder.newFolder("terminal-size-hints")
        val runtime = InstalledRuntime(
            runtimeId = "alpine",
            runtimeVersion = "test",
            abi = "arm64-v8a",
            rootfsDirectory = File(base, "rootfs"),
            workspaceDirectory = File(base, "workspace").apply { mkdirs() },
            launcher = File(base, "libproot.so"),
            loader = File(base, "libproot-loader.so"),
        )
        val launcher = ProotProcessLauncher(
            cacheDirectory = File(base, "cache"),
            environmentContributors = emptyList(),
            processListener = RuntimeHostProcessListener { },
            maxOutputBytes = 1024,
        )
        launcher.openSession("session")

        fun assertRejected(key: String, action: () -> Unit) {
            val error = assertThrows(RuntimeOperationException::class.java, action)
            assertEquals("$key must not override the PTY size", RuntimeErrorCode.INVALID_REQUEST, error.errorCode)
        }

        listOf("COLUMNS", "LINES").forEach { key ->
            assertRejected(key) {
                launcher.openTerminal(
                    runtime = runtime,
                    sessionId = "session",
                    sessionEnvironment = emptyMap(),
                    request = RuntimeTerminalRequest(environment = mapOf(key to "999")),
                )
            }
            assertRejected(key) {
                launcher.openTerminal(
                    runtime = runtime,
                    sessionId = "session",
                    sessionEnvironment = mapOf(key to "999"),
                    request = RuntimeTerminalRequest(),
                )
            }
            assertRejected(key) {
                launcher.execute(
                    runtime = runtime,
                    sessionId = "session",
                    sessionEnvironment = emptyMap(),
                    request = RuntimeCommandRequest(
                        executable = "/bin/sh",
                        environment = mapOf(key to "999"),
                    ),
                )
            }
        }
    }

    @Test
    fun `closed session cannot create a new host process`() {
        val base = temporaryFolder.newFolder("closed-runtime")
        val runtime = InstalledRuntime(
            runtimeId = "alpine",
            runtimeVersion = "test",
            abi = "arm64-v8a",
            rootfsDirectory = File(base, "rootfs"),
            workspaceDirectory = File(base, "workspace").apply { mkdirs() },
            launcher = File(base, "libproot.so"),
            loader = File(base, "libproot-loader.so"),
        )
        val launcher = ProotProcessLauncher(
            cacheDirectory = File(base, "cache"),
            environmentContributors = emptyList(),
            processListener = RuntimeHostProcessListener { },
            maxOutputBytes = 1024,
        )
        launcher.openSession("session")
        launcher.stopSession("session")

        val error = assertThrows(RuntimeOperationException::class.java) {
            launcher.execute(
                runtime,
                "session",
                emptyMap(),
                RuntimeCommandRequest(executable = "/bin/sh"),
            )
        }

        assertEquals(RuntimeErrorCode.PROCESS_EXITED, error.errorCode)
    }

    @Test
    fun `interactive terminal streams input and closes its process`() {
        val base = temporaryFolder.newFolder("terminal-runtime")
        val fakeLauncher = File(base, "libproot.so").apply {
            writeText("#!/bin/sh\nexec /bin/cat\n")
            setExecutable(true)
        }
        val runtime = InstalledRuntime(
            runtimeId = "alpine",
            runtimeVersion = "test",
            abi = "arm64-v8a",
            rootfsDirectory = File(base, "rootfs").apply { mkdirs() },
            workspaceDirectory = File(base, "workspace").apply { mkdirs() },
            launcher = fakeLauncher,
            loader = File(base, "libproot-loader.so").apply { writeText("test") },
        )
        val launcher = ProotProcessLauncher(
            cacheDirectory = File(base, "cache"),
            environmentContributors = emptyList(),
            processListener = RuntimeHostProcessListener { },
            maxOutputBytes = 1024,
        )
        launcher.openSession("session")
        val output = StringBuilder()
        val received = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var observedExitCode: Int? = null
        val terminal = launcher.openTerminal(
            runtime,
            "session",
            emptyMap(),
            RuntimeTerminalRequest(),
            onClosed = { _, exitCode ->
                observedExitCode = exitCode
                closed.countDown()
            },
        )
        terminal.addOutputListener { bytes ->
            output.append(bytes.toString(Charsets.UTF_8))
            if (output.contains("안녕 terminal")) received.countDown()
        }

        terminal.write("안녕 terminal\n".toByteArray()).toCompletableFuture().join()

        assertTrue(received.await(3, TimeUnit.SECONDS))
        assertTrue(terminal.isOpen)
        assertEquals(RuntimeTerminalResizeSupport.INITIAL_SIZE_ONLY, terminal.resizeSupport)
        val resizeError = assertThrows(java.util.concurrent.CompletionException::class.java) {
            terminal.resize(120, 40).toCompletableFuture().join()
        }
        assertEquals(
            RuntimeErrorCode.TERMINAL_RESIZE_UNSUPPORTED,
            (resizeError.cause as RuntimeOperationException).errorCode,
        )
        terminal.closeAsync().toCompletableFuture().join()
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        assertFalse(terminal.isOpen)
        assertTrue(observedExitCode != null)
        assertTrue(launcher.listProcesses("session").isEmpty())
    }
}
