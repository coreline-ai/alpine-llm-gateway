package dev.alpine.runtime.testkit

import dev.alpine.runtime.api.RuntimeCommandRequest
import dev.alpine.runtime.api.RuntimeInstallRequest
import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.api.RuntimeStartRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAlpineRuntimeManagerTest {
    @Test
    fun `operations complete only when deterministic dispatcher advances`() {
        val runtime = FakeAlpineRuntimeManager()

        val install = runtime.install(RuntimeInstallRequest()).toCompletableFuture()
        assertFalse(install.isDone)
        assertEquals(RuntimeLifecycleState.INSTALLING, runtime.currentState().lifecycle)

        runtime.testDispatcher().runUntilIdle()
        assertTrue(install.isDone)
        assertEquals(RuntimeLifecycleState.READY, runtime.currentState().lifecycle)
    }

    @Test
    fun `fake session executes configured result`() {
        val runtime = FakeAlpineRuntimeManager()
        runtime.install(RuntimeInstallRequest())
        runtime.testDispatcher().runUntilIdle()
        val start = runtime.start(RuntimeStartRequest()).toCompletableFuture()
        runtime.testDispatcher().runUntilIdle()

        val command = start.join().execute(RuntimeCommandRequest("/bin/true")).toCompletableFuture()
        runtime.testDispatcher().runUntilIdle()

        assertEquals(0, command.join().exitCode)
    }
}
