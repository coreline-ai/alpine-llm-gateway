package dev.alpine.runtime.host

import dev.alpine.runtime.api.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList

class RuntimeHostControllerTest {
    @Test
    fun `custom and compose hosts can observe the same lifecycle state`() {
        val manager = ImmediateRuntimeManager()
        val controller = RuntimeHostController(manager)
        val observed = mutableListOf<RuntimeLifecycleState>()
        controller.addStateListener { observed += it.runtimeState.lifecycle }

        controller.install().toCompletableFuture().join()
        controller.start().toCompletableFuture().join()
        controller.stop().toCompletableFuture().join()

        assertTrue(observed.contains(RuntimeLifecycleState.INSTALLING))
        assertTrue(observed.contains(RuntimeLifecycleState.RUNNING))
        assertEquals(RuntimeLifecycleState.READY, controller.currentState().runtimeState.lifecycle)
        assertFalse(controller.currentState().sessionActive)
    }

    @Test
    fun `terminal output is bounded and reset clears user-visible state`() {
        val manager = ImmediateRuntimeManager()
        val controller = RuntimeHostController(manager, maxTerminalBufferBytes = 8)
        controller.install().toCompletableFuture().join()
        controller.start().toCompletableFuture().join()
        controller.openTerminal().toCompletableFuture().join()
        controller.sendTerminalInput("123456789", appendNewline = false).toCompletableFuture().join()

        assertEquals("23456789", controller.currentState().terminalText)
        assertTrue(controller.currentState().terminalOutputTruncated)

        controller.reset().toCompletableFuture().join()
        assertEquals("", controller.currentState().terminalText)
        assertFalse(controller.currentState().terminalActive)
    }

    @Test
    fun `terminal presentation removes ansi controls without changing text`() {
        val manager = ImmediateRuntimeManager()
        val controller = RuntimeHostController(manager)
        controller.install().toCompletableFuture().join()
        controller.start().toCompletableFuture().join()
        controller.openTerminal().toCompletableFuture().join()

        controller.sendTerminalInput("\u001b[31m한글 red\u001b[0m", appendNewline = false)
            .toCompletableFuture().join()

        assertEquals("한글 red", controller.currentState().terminalText)
    }

    private class ImmediateRuntimeManager : AlpineRuntimeManager {
        private val stateListeners = CopyOnWriteArrayList<RuntimeStateListener>()
        private val eventListeners = CopyOnWriteArrayList<RuntimeEventListener>()
        private var state = RuntimeState(RuntimeLifecycleState.NOT_INSTALLED)
        private var session: ImmediateSession? = null

        override fun currentState(): RuntimeState = state
        override fun addStateListener(listener: RuntimeStateListener): RuntimeSubscription {
            stateListeners += listener
            listener.onStateChanged(state)
            return RuntimeSubscription { stateListeners -= listener }
        }
        override fun addEventListener(listener: RuntimeEventListener): RuntimeSubscription {
            eventListeners += listener
            return RuntimeSubscription { eventListeners -= listener }
        }
        override fun install(request: RuntimeInstallRequest): CompletionStage<RuntimeInstallResult> {
            setState(RuntimeState(RuntimeLifecycleState.INSTALLING, 25))
            setState(RuntimeState(RuntimeLifecycleState.READY, 100, "test"))
            return CompletableFuture.completedFuture(RuntimeInstallResult("test", listOf("rootfs"), false))
        }
        override fun start(request: RuntimeStartRequest): CompletionStage<RuntimeSession> {
            setState(RuntimeState(RuntimeLifecycleState.STARTING, activeVersion = "test"))
            return ImmediateSession().also { started ->
                session = started
                setState(RuntimeState(RuntimeLifecycleState.RUNNING, activeVersion = "test"))
            }.let { CompletableFuture.completedFuture(it) }
        }
        override fun stop(reason: RuntimeStopReason): CompletionStage<Void> {
            setState(RuntimeState(RuntimeLifecycleState.STOPPING, activeVersion = "test"))
            session = null
            setState(RuntimeState(RuntimeLifecycleState.READY, activeVersion = "test"))
            return CompletableFuture.completedFuture(null)
        }
        override fun repair(): CompletionStage<RuntimeInstallResult> = install(RuntimeInstallRequest())
        override fun reset(): CompletionStage<Void> {
            session = null
            setState(RuntimeState(RuntimeLifecycleState.NOT_INSTALLED))
            return CompletableFuture.completedFuture(null)
        }
        override fun health(): CompletionStage<RuntimeHealth> = CompletableFuture.completedFuture(
            RuntimeHealth(state.lifecycle != RuntimeLifecycleState.NOT_INSTALLED, state.lifecycle, 1),
        )
        private fun setState(value: RuntimeState) {
            state = value
            stateListeners.forEach { it.onStateChanged(value) }
        }
    }

    private class ImmediateSession : RuntimeSession {
        override val id: String = "session"
        override val startedAtEpochMillis: Long = 1
        override fun execute(request: RuntimeCommandRequest): CompletionStage<RuntimeCommandResult> =
            CompletableFuture.completedFuture(RuntimeCommandResult(0, "ok".toByteArray()))
        override fun openTerminal(request: RuntimeTerminalRequest): CompletionStage<RuntimeTerminalSession> =
            CompletableFuture.completedFuture(ImmediateTerminal())
        override fun listProcesses(): CompletionStage<List<RuntimeProcessInfo>> =
            CompletableFuture.completedFuture(emptyList())
        override fun health(): CompletionStage<RuntimeHealth> = CompletableFuture.completedFuture(
            RuntimeHealth(true, RuntimeLifecycleState.RUNNING, 1),
        )
        override fun stop(reason: RuntimeStopReason): CompletionStage<Void> =
            CompletableFuture.completedFuture(null)
    }

    private class ImmediateTerminal : RuntimeTerminalSession {
        private val listeners = CopyOnWriteArrayList<RuntimeTerminalOutputListener>()
        override val id: String = "terminal"
        override var isOpen: Boolean = true
        override fun addOutputListener(listener: RuntimeTerminalOutputListener): RuntimeSubscription {
            listeners += listener
            return RuntimeSubscription { listeners -= listener }
        }
        override fun write(bytes: ByteArray): CompletionStage<Void> {
            listeners.forEach { it.onOutput(bytes) }
            return CompletableFuture.completedFuture(null)
        }
        override fun resize(columns: Int, rows: Int): CompletionStage<Void> =
            CompletableFuture.completedFuture(null)
        override fun signal(signal: RuntimeTerminalSignal): CompletionStage<Void> =
            CompletableFuture.completedFuture(null)
        override fun closeAsync(): CompletionStage<Void> {
            isOpen = false
            return CompletableFuture.completedFuture(null)
        }
    }
}
