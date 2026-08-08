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

    @Test
    fun `foreground listener releases only after terminal and command are both closed`() {
        var startRequests = 0
        var stopRequests = 0
        val listener = RuntimeForegroundProcessListener(
            startForeground = {
                startRequests += 1
                RuntimeBackgroundStartResult.START_REQUESTED
            },
            stopForeground = { stopRequests += 1 },
        )

        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STARTED, "terminal", 11))
        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STARTED, "command", 12))
        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STOPPED, "terminal", 11))

        assertEquals(1, startRequests)
        assertEquals(0, stopRequests)

        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STOPPED, "command", 12))
        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STOPPED, "command", 12))

        assertEquals(1, stopRequests)
    }

    @Test
    fun `foreground start rejection requests host policy stop once and never retries`() {
        var startRequests = 0
        var stopRequests = 0
        var rejected = 0
        val listener = RuntimeForegroundProcessListener(
            startForeground = {
                startRequests += 1
                RuntimeBackgroundStartResult.START_NOT_ALLOWED
            },
            stopForeground = { stopRequests += 1 },
            onStartRejected = Runnable { rejected += 1 },
        )

        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STARTED, "terminal", 21))
        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STARTED, "terminal", 21))
        listener.onProcessEvent(event(RuntimeHostProcessEventKind.STOPPED, "terminal", 21))

        assertEquals(1, startRequests)
        assertEquals(1, rejected)
        assertEquals(1, stopRequests)
    }

    private fun accept(kind: RuntimeHostProcessEventKind, session: String, process: Long) =
        machine.accept(RuntimeHostProcessEvent(kind, session, process, 1L))

    private fun event(kind: RuntimeHostProcessEventKind, session: String, process: Long) =
        RuntimeHostProcessEvent(kind, session, process, 1L)
}
