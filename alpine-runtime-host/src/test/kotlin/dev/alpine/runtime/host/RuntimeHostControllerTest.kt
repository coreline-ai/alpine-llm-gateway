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
    fun `terminal presentation preserves safe scrollback and exposes styled ansi screen`() {
        val manager = ImmediateRuntimeManager()
        val controller = RuntimeHostController(manager)
        controller.install().toCompletableFuture().join()
        controller.start().toCompletableFuture().join()
        controller.openTerminal().toCompletableFuture().join()

        controller.sendTerminalInput("\u001b[31m한글 red\u001b[0m", appendNewline = false)
            .toCompletableFuture().join()

        val state = controller.currentState()
        assertEquals("한글 red", state.terminalText)
        assertEquals("한글 red", state.terminalScreen?.plainText)
        assertEquals(RuntimeTerminalColor.RED, state.terminalScreen?.lines?.single()?.spans?.first()?.style?.foreground)
    }

    @Test
    fun `external owner session binding enables terminal without transferring lifecycle ownership`() {
        val manager = ImmediateRuntimeManager()
        val controller = RuntimeHostController(manager)
        controller.install().toCompletableFuture().join()
        val external = manager.start(RuntimeStartRequest()).toCompletableFuture().join()

        val binding = controller.bindExternalSession(external)
        controller.openTerminal().toCompletableFuture().join()
        assertTrue(controller.currentState().sessionActive)
        assertTrue(controller.currentState().terminalActive)

        binding.close()

        assertFalse(controller.currentState().sessionActive)
        assertFalse(controller.currentState().terminalActive)
        assertEquals(RuntimeLifecycleState.RUNNING, manager.currentState().lifecycle)
    }

    @Test
    fun `terminal tabs isolate output selection rename and close`() {
        val manager = ImmediateRuntimeManager()
        val controller = RuntimeHostController(manager)
        controller.install().toCompletableFuture().join()
        controller.start().toCompletableFuture().join()

        val first = controller.openTerminal().toCompletableFuture().join()
        controller.sendTerminalInput("first", appendNewline = false).toCompletableFuture().join()
        val second = controller.openAdditionalTerminal().toCompletableFuture().join()
        controller.sendTerminalInput("second", appendNewline = false).toCompletableFuture().join()

        assertEquals(2, controller.currentState().terminalSessions.size)
        assertEquals(second.id, controller.currentState().selectedTerminalId)
        assertEquals("second", controller.currentState().terminalText)
        assertTrue(controller.renameTerminal(first.id, "빌드 셸"))
        assertTrue(controller.selectTerminal(first.id))
        assertEquals("first", controller.currentState().terminalText)
        assertEquals("빌드 셸", controller.currentState().terminalSessions.first { it.id == first.id }.title)

        controller.closeTerminal(first.id).toCompletableFuture().join()
        assertEquals(1, controller.currentState().terminalSessions.size)
        assertEquals(second.id, controller.currentState().selectedTerminalId)
        assertEquals("second", controller.currentState().terminalText)
    }

    @Test
    fun `terminal close event keeps only safe last exit summary after tab removal`() {
        val manager = ImmediateRuntimeManager()
        val controller = RuntimeHostController(manager)
        controller.install().toCompletableFuture().join()
        controller.start().toCompletableFuture().join()
        val terminal = controller.openTerminal().toCompletableFuture().join()
        controller.renameTerminal(terminal.id, "빌드 셸")

        manager.emitTerminalClosed(terminal.id, exitCode = 23)

        val state = controller.currentState()
        assertFalse(state.terminalActive)
        assertTrue(state.terminalSessions.isEmpty())
        assertEquals("빌드 셸", state.lastTerminalExit?.title)
        assertEquals(23, state.lastTerminalExit?.exitCode)
    }

    @Test
    fun `package mutation exposes only the completed fixed action state`() {
        val manager = ImmediateRuntimeManager()
        val controller = RuntimeHostController(manager)
        controller.install().toCompletableFuture().join()
        controller.start().toCompletableFuture().join()

        val result = controller.mutatePackages(
            request = RuntimePackageMutationRequest(RuntimePackageAction.UPDATE, listOf("git")),
            policy = RuntimePackageMutationAllowlistPolicy(setOf("git"), setOf("git")),
            approval = RuntimePackageApproval { CompletableFuture.completedFuture(true) },
        ).toCompletableFuture().join()

        assertEquals(RuntimePackageMutationOutcome.COMPLETED, result.outcome)
        assertEquals(RuntimePackageAction.UPDATE, controller.currentState().packageMutationAction)
        assertEquals(RuntimePackageMutationOutcome.COMPLETED, controller.currentState().packageMutationOutcome)
    }

    @Test
    fun `developer tool smoke dispatches only its fixed argv and records no output`() {
        val manager = ImmediateRuntimeManager()
        val controller = RuntimeHostController(manager)
        controller.install().toCompletableFuture().join()
        controller.start().toCompletableFuture().join()
        val profile = DefaultRuntimeDeveloperToolProfiles.first { it.id == "git" }

        controller.runToolSmoke(profile).toCompletableFuture().join()

        assertEquals(profile.smokeRequest, manager.startedSession?.lastRequest)
        assertEquals("git", controller.currentState().toolSmokeProfileId)
        assertEquals(RuntimeToolSmokeOutcome.COMPLETED, controller.currentState().toolSmokeOutcome)
        assertEquals("", controller.currentState().commandOutput)
    }

    private class ImmediateRuntimeManager : AlpineRuntimeManager {
        private val stateListeners = CopyOnWriteArrayList<RuntimeStateListener>()
        private val eventListeners = CopyOnWriteArrayList<RuntimeEventListener>()
        private var state = RuntimeState(RuntimeLifecycleState.NOT_INSTALLED)
        private var session: ImmediateSession? = null
        var startedSession: ImmediateSession? = null
            private set

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
                startedSession = started
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
        fun emitTerminalClosed(terminalId: String, exitCode: Int?) {
            startedSession?.markTerminalClosed(terminalId)
            val event = RuntimeEvent(
                kind = RuntimeEventKind.TERMINAL_CLOSED,
                timestampEpochMillis = 1,
                sessionId = startedSession?.id,
                attributes = buildMap {
                    put("terminal_id", terminalId)
                    exitCode?.let { put("exit_code", it.toString()) }
                },
            )
            eventListeners.forEach { it.onEvent(event) }
        }
        private fun setState(value: RuntimeState) {
            state = value
            stateListeners.forEach { it.onStateChanged(value) }
        }
    }

    private class ImmediateSession : RuntimeSession {
        private var nextTerminalId = 1
        private val terminals = linkedMapOf<String, ImmediateTerminal>()
        override val id: String = "session"
        override val startedAtEpochMillis: Long = 1
        var lastRequest: RuntimeCommandRequest? = null
            private set
        override fun execute(request: RuntimeCommandRequest): CompletionStage<RuntimeCommandResult> {
            lastRequest = request
            return CompletableFuture.completedFuture(RuntimeCommandResult(0, "ok".toByteArray()))
        }
        override fun openTerminal(request: RuntimeTerminalRequest): CompletionStage<RuntimeTerminalSession> =
            ImmediateTerminal("terminal-${nextTerminalId++}").also { terminals[it.id] = it }
                .let { CompletableFuture.completedFuture<RuntimeTerminalSession>(it) }
        fun markTerminalClosed(id: String) {
            terminals[id]?.markClosed()
        }
        override fun listProcesses(): CompletionStage<List<RuntimeProcessInfo>> =
            CompletableFuture.completedFuture(emptyList())
        override fun health(): CompletionStage<RuntimeHealth> = CompletableFuture.completedFuture(
            RuntimeHealth(true, RuntimeLifecycleState.RUNNING, 1),
        )
        override fun stop(reason: RuntimeStopReason): CompletionStage<Void> =
            CompletableFuture.completedFuture(null)
    }

    private class ImmediateTerminal(override val id: String) : RuntimeTerminalSession {
        private val listeners = CopyOnWriteArrayList<RuntimeTerminalOutputListener>()
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
            markClosed()
            return CompletableFuture.completedFuture(null)
        }
        fun markClosed() {
            isOpen = false
        }
    }
}
