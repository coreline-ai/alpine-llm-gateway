package dev.alpine.runtime.ui.compose

import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.api.RuntimeState
import dev.alpine.runtime.host.RuntimeHostOperation
import dev.alpine.runtime.host.RuntimeHostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeUiPolicyTest {
    @Test
    fun `running idle runtime enables stop and health only`() {
        val actions = RuntimeHostState(
            runtimeState = RuntimeState(RuntimeLifecycleState.RUNNING),
            operation = RuntimeHostOperation.IDLE,
            sessionActive = true,
        ).actionAvailability()

        assertTrue(actions.stop)
        assertTrue(actions.health)
        assertFalse(actions.install)
        assertFalse(actions.start)
    }

    @Test
    fun `package input requires exact allowlist entries`() {
        val allowed = setOf("git", "python3")

        assertEquals(listOf("git", "python3"), parseRuntimePackageInput("git, python3", allowed).packages)
        assertTrue(parseRuntimePackageInput("git python3", allowed).valid)
        assertFalse(parseRuntimePackageInput("git curl", allowed).valid)
        assertFalse(parseRuntimePackageInput("git;id", allowed).valid)
    }
}
