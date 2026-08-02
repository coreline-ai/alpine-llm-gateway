package dev.alpine.runtime.background.android

import dev.alpine.runtime.api.RuntimeHostProcessEvent
import dev.alpine.runtime.api.RuntimeHostProcessListener

/** Starts foreground ownership for the first process and releases it after the last process. */
class RuntimeForegroundProcessListener @JvmOverloads constructor(
    private val controller: RuntimeForegroundServiceController,
    private val onStartRejected: Runnable = Runnable { },
) : RuntimeHostProcessListener {
    private val stateMachine = RuntimeProcessLeaseStateMachine()

    override fun onProcessEvent(event: RuntimeHostProcessEvent) {
        when (stateMachine.accept(event)) {
            RuntimeProcessLeaseAction.START_FOREGROUND -> {
                when (controller.startUserVisibleWork()) {
                    RuntimeBackgroundStartResult.START_NOT_ALLOWED,
                    RuntimeBackgroundStartResult.START_FAILED,
                    -> onStartRejected.run()

                    RuntimeBackgroundStartResult.START_REQUESTED,
                    RuntimeBackgroundStartResult.ALREADY_REQUESTED_OR_ACTIVE,
                    -> Unit
                }
            }

            RuntimeProcessLeaseAction.STOP_FOREGROUND -> controller.stop()
            RuntimeProcessLeaseAction.NONE -> Unit
        }
    }
}
