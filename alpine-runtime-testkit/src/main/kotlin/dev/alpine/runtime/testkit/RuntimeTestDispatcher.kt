package dev.alpine.runtime.testkit

import java.util.ArrayDeque

/** Deterministic FIFO dispatcher used by fake runtime operations. */
class RuntimeTestDispatcher {
    private val tasks = ArrayDeque<Runnable>()

    @Synchronized
    fun dispatch(task: Runnable) {
        tasks.addLast(task)
    }

    @Synchronized
    fun pendingCount(): Int = tasks.size

    fun runNext(): Boolean {
        val task = synchronized(this) { if (tasks.isEmpty()) null else tasks.removeFirst() }
        task?.run()
        return task != null
    }

    fun runUntilIdle() {
        while (runNext()) {
            // Drain deterministically.
        }
    }
}
