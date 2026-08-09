package dev.alpine.llm.runtimeprobe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alpine.runtime.android.AndroidRuntimeConfiguration
import dev.alpine.runtime.android.DefaultAndroidAlpineRuntimeFactory
import dev.alpine.runtime.api.RuntimeInstallRequest
import dev.alpine.runtime.api.RuntimeStartRequest
import dev.alpine.runtime.api.RuntimeStopReason
import dev.alpine.runtime.api.RuntimeTerminalRequest
import dev.alpine.runtime.api.RuntimeTerminalResizeSupport
import dev.alpine.runtime.pack.bundled.Alpine321Arm64Pack
import dev.alpine.runtime.pack.bundled.BundledRuntimeArtifactProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Samsung-only diagnostic for the unpatched PRoot binary under the forkpty direct-exec topology.
 * Marker strings are fixed test protocol values and remain in memory only; terminal payload is
 * neither logged nor persisted. The test proves the remaining SIGWINCH delivery gap and therefore
 * deliberately does not promote the product resize contract.
 */
@RunWith(AndroidJUnit4::class)
class ForkPtyProotSignalGapInstrumentedTest {
    @Test
    fun unpatchedProotKeepsInputAndWinsizeAcrossRepeatAndStorm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = DefaultAndroidAlpineRuntimeFactory().create(
            context,
            AndroidRuntimeConfiguration(
                artifactProvider = BundledRuntimeArtifactProvider(context, Alpine321Arm64Pack.create()),
                runtimeDirectoryName = "forkpty-proot-acceptance",
                enableTtyIoctlDiagnostics = true,
                ttyDiagnosticForkPtyDirect = true,
            ),
        )
        var session: dev.alpine.runtime.api.RuntimeSession? = null
        var terminal: dev.alpine.runtime.api.RuntimeTerminalSession? = null
        try {
            runtime.install(RuntimeInstallRequest()).toCompletableFuture().join()
            session = runtime.start(RuntimeStartRequest()).toCompletableFuture().join()
            terminal = session.openTerminal(RuntimeTerminalRequest(columns = 96, rows = 28))
                .toCompletableFuture().join()
            assertEquals(RuntimeTerminalResizeSupport.DYNAMIC, terminal.resizeSupport)

            val initialReady = CountDownLatch(1)
            val topologyReady = CountDownLatch(1)
            val firstWinch = CountDownLatch(1)
            val firstSize = CountDownLatch(1)
            val repeatedSize = CountDownLatch(1)
            val stormSize = CountDownLatch(1)
            val markers = ByteArrayOutputStream()
            terminal.addOutputListener { bytes ->
                synchronized(markers) {
                    markers.write(bytes)
                    val text = markers.toString(Charsets.UTF_8.name())
                    if (text.contains("FORKPTY_READY") && text.contains("FORKPTY_INITIAL=28 96")) {
                        initialReady.countDown()
                    }
                    // Interactive shells may create their own foreground process group, so being
                    // the session leader is not a validity requirement. The controlling TTY and
                    // foreground-group ownership are the signal-delivery invariants.
                    if (text.contains("FORKPTY_TOPOLOGY=1,1,")) topologyReady.countDown()
                    if (text.contains("FORKPTY_WINCH=1")) firstWinch.countDown()
                    if (text.contains("FORKPTY_AFTER=40 120")) firstSize.countDown()
                    if (text.contains("FORKPTY_REPEAT=24 80")) repeatedSize.countDown()
                    if (text.contains("FORKPTY_STORM=40 120")) stormSize.countDown()
                }
            }

            terminal.write(
                (
                    "stty -echo; count=0; " +
                        "trap 'count=\$((count + 1)); printf \"FORKPTY_WINCH=%s\\n\" \"\$count\"' WINCH; " +
                        "set -- \$(cat /proc/\$\$/stat); " +
                        "tty=0; [ \"\$7\" != 0 ] && tty=1; " +
                        "foreground=0; [ \"\$5\" = \"\$8\" ] && foreground=1; " +
                        "session=0; [ \"\$5\" = \"\$6\" ] && session=1; " +
                        "printf 'FORKPTY_TOPOLOGY=%s,%s,%s\\n' \"\$tty\" \"\$foreground\" \"\$session\"; " +
                        "printf 'FORKPTY_READY\\n'; printf 'FORKPTY_INITIAL='; stty size\n"
                    ).toByteArray(),
            ).toCompletableFuture().join()
            assertTrue("unpatched PRoot initial interactive terminal", initialReady.await(15, TimeUnit.SECONDS))
            assertTrue("guest shell owns the controlling foreground PTY group", topologyReady.await(5, TimeUnit.SECONDS))

            terminal.resize(120, 40).toCompletableFuture().join()
            assertFalse(
                "forkpty keeps PRoot input and winsize healthy but unpatched PRoot still does not deliver SIGWINCH",
                firstWinch.await(5, TimeUnit.SECONDS),
            )
            terminal.write("printf 'FORKPTY_AFTER='; stty size\n".toByteArray()).toCompletableFuture().join()
            assertTrue("input and dynamic size continue after first resize", firstSize.await(10, TimeUnit.SECONDS))

            terminal.resize(80, 24).toCompletableFuture().join()
            terminal.write("printf 'FORKPTY_REPEAT='; stty size\n".toByteArray()).toCompletableFuture().join()
            assertTrue("repeat resize keeps the terminal responsive", repeatedSize.await(10, TimeUnit.SECONDS))

            listOf(120 to 40, 80 to 24, 120 to 40, 80 to 24, 120 to 40).forEach { (columns, rows) ->
                terminal.resize(columns, rows).toCompletableFuture().join()
            }
            terminal.write("printf 'FORKPTY_STORM='; stty size\n".toByteArray()).toCompletableFuture().join()
            assertTrue("resize storm leaves final winsize and input usable", stormSize.await(10, TimeUnit.SECONDS))

            terminal.closeAsync().toCompletableFuture().join()
            assertFalse("terminal must close without keeping its child session open", terminal.isOpen)
            assertTrue(
                "terminal close must remove its tracked host process",
                session.listProcesses().toCompletableFuture().join().isEmpty(),
            )
            session.stop(RuntimeStopReason.USER_REQUEST).toCompletableFuture().join()
        } finally {
            runCatching { terminal?.closeAsync()?.toCompletableFuture()?.join() }
            runCatching { session?.stop(RuntimeStopReason.USER_REQUEST)?.toCompletableFuture()?.join() }
            runtime.close()
        }
    }
}
