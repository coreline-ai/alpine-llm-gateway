package dev.alpine.runtime.background.android

import dev.alpine.runtime.api.RuntimeHostProcessEvent
import dev.alpine.runtime.api.RuntimeHostProcessListener

/** Starts foreground ownership for the first process and releases it after the last process. */
class RuntimeForegroundProcessListener private constructor(
    private val startForeground: () -> RuntimeBackgroundStartResult,
    private val stopForeground: () -> Unit,
    private val onStartRejected: Runnable,
) : RuntimeHostProcessListener {
    @JvmOverloads
    constructor(
        controller: RuntimeForegroundServiceController,
        onStartRejected: Runnable = Runnable { },
    ) : this(
        startForeground = controller::startUserVisibleWork,
        stopForeground = controller::stop,
        onStartRejected = onStartRejected,
    )

    /** Internal injection boundary for deterministic process-lease regressions. */
    internal constructor(
        startForeground: () -> RuntimeBackgroundStartResult,
        stopForeground: () -> Unit,
        onStartRejected: Runnable = Runnable { },
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(startForeground, stopForeground, onStartRejected)

    private val stateMachine = RuntimeProcessLeaseStateMachine()

    override fun onProcessEvent(event: RuntimeHostProcessEvent) {
        when (stateMachine.accept(event)) {
            RuntimeProcessLeaseAction.START_FOREGROUND -> {
                when (startForeground()) {
                    RuntimeBackgroundStartResult.START_NOT_ALLOWED,
                    RuntimeBackgroundStartResult.START_FAILED,
                    -> onStartRejected.run()

                    RuntimeBackgroundStartResult.START_REQUESTED,
                    RuntimeBackgroundStartResult.ALREADY_REQUESTED_OR_ACTIVE,
                    -> Unit
                }
            }

            RuntimeProcessLeaseAction.STOP_FOREGROUND -> stopForeground()
            RuntimeProcessLeaseAction.NONE -> Unit
        }
    }
}
