package dev.alpine.runtime.background.android

import dev.alpine.runtime.api.RuntimeHostProcessEvent
import dev.alpine.runtime.api.RuntimeHostProcessEventKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeProcessLeaseStateMachineTest {
    private val machine = RuntimeProcessLeaseStateMachine()

    @Test
    fun `first start and last stop own one foreground lease`() {
        assertEquals(RuntimeProcessLeaseAction.START_FOREGROUND, accept(RuntimeHostProcessEventKind.STARTED, "a", 1))
        assertEquals(RuntimeProcessLeaseAction.NONE, accept(RuntimeHostProcessEventKind.STARTED, "a", 2))
        assertEquals(RuntimeProcessLeaseAction.NONE, accept(RuntimeHostProcessEventKind.STOPPED, "a", 1))
        assertEquals(RuntimeProcessLeaseAction.STOP_FOREGROUND, accept(RuntimeHostProcessEventKind.STOPPED, "a", 2))
        assertEquals(0, machine.activeProcessCount())
    }

    @Test
    fun `duplicate and unknown events do not underflow the lease`() {
        assertEquals(RuntimeProcessLeaseAction.START_FOREGROUND, accept(RuntimeHostProcessEventKind.STARTED, "a", 1))
        assertEquals(RuntimeProcessLeaseAction.NONE, accept(RuntimeHostProcessEventKind.STARTED, "a", 1))
        assertEquals(RuntimeProcessLeaseAction.NONE, accept(RuntimeHostProcessEventKind.STOPPED, "other", 99))
        assertEquals(RuntimeProcessLeaseAction.STOP_FOREGROUND, accept(RuntimeHostProcessEventKind.STOPPED, "a", 1))
        assertEquals(RuntimeProcessLeaseAction.NONE, accept(RuntimeHostProcessEventKind.STOPPED, "a", 1))
    }

    private fun accept(kind: RuntimeHostProcessEventKind, session: String, process: Long) =
        machine.accept(RuntimeHostProcessEvent(kind, session, process, 1L))
}
