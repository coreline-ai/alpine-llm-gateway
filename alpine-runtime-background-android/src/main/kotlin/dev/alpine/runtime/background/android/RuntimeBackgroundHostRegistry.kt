package dev.alpine.runtime.background.android

import dev.alpine.runtime.api.RuntimeSubscription
import java.util.concurrent.atomic.AtomicReference

/** Process-local binding used by the notification Stop action; it never persists a command or token. */
object RuntimeBackgroundHostRegistry {
    private val stopCallback = AtomicReference<(() -> Unit)?>(null)

    @JvmStatic
    fun bind(stopRuntime: () -> Unit): RuntimeSubscription {
        require(stopCallback.compareAndSet(null, stopRuntime)) {
            "A runtime background host is already bound in this process"
        }
        return RuntimeSubscription { stopCallback.compareAndSet(stopRuntime, null) }
    }

    internal fun requestStop(): Boolean {
        val callback = stopCallback.get() ?: return false
        runCatching(callback).getOrElse { return false }
        return true
    }
}
