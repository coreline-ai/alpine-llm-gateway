package dev.alpine.runtime.testkit

import dev.alpine.runtime.api.RuntimeEvent
import dev.alpine.runtime.api.RuntimeEventSink
import java.util.concurrent.CopyOnWriteArrayList

class RecordingRuntimeEventSink : RuntimeEventSink {
    private val recorded = CopyOnWriteArrayList<RuntimeEvent>()

    override fun emit(event: RuntimeEvent) {
        recorded += event
    }

    fun events(): List<RuntimeEvent> = recorded.toList()

    fun clear() {
        recorded.clear()
    }
}
