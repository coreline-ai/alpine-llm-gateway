package dev.alpine.runtime.background.android

import dev.alpine.runtime.api.RuntimeHostProcessEvent
import dev.alpine.runtime.api.RuntimeHostProcessEventKind

internal enum class RuntimeProcessLeaseAction {
    NONE,
    START_FOREGROUND,
    STOP_FOREGROUND,
}

internal class RuntimeProcessLeaseStateMachine {
    private val active = linkedSetOf<Pair<String, Long>>()

    @Synchronized
    fun accept(event: RuntimeHostProcessEvent): RuntimeProcessLeaseAction {
        val key = event.sessionId to event.processId
        return when (event.kind) {
            RuntimeHostProcessEventKind.STARTED -> {
                val wasEmpty = active.isEmpty()
                val added = active.add(key)
                if (wasEmpty && added) RuntimeProcessLeaseAction.START_FOREGROUND
                else RuntimeProcessLeaseAction.NONE
            }

            RuntimeHostProcessEventKind.STOPPED -> {
                val removed = active.remove(key)
                if (removed && active.isEmpty()) RuntimeProcessLeaseAction.STOP_FOREGROUND
                else RuntimeProcessLeaseAction.NONE
            }
        }
    }

    @Synchronized
    fun activeProcessCount(): Int = active.size
}
