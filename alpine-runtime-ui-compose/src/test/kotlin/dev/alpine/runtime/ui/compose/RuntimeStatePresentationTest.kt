package dev.alpine.runtime.ui.compose

import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.api.RuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStatePresentationTest {
    @Test
    fun `installing state shows progress and blocks actions`() {
        val presentation = RuntimeState(RuntimeLifecycleState.INSTALLING, 42).toPresentation()

        assertEquals("설치 중 42%", presentation.label)
        assertFalse(presentation.actionable)
    }

    @Test
    fun `repair state is actionable`() {
        assertTrue(RuntimeState(RuntimeLifecycleState.REPAIR_REQUIRED).toPresentation().actionable)
    }
}
